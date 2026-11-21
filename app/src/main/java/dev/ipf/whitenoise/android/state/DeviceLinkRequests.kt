package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A link request on one of this installation's profiles, named by MDK's opaque prompt id. [open] says whether it
 * still needs this user or is still in progress; a settled request only reports its outcome.
 */
internal data class DeviceLinkRequest(
    val accountRef: String,
    val promptId: String,
    val requestedAtSeconds: Long,
    val open: Boolean = true,
    val comparing: Boolean = false,
)

/**
 * Whether a prompt still needs this user or is still in progress. A prompt that linked, was added by another device
 * or ended is settled and only reports its outcome.
 */
internal fun DeviceLinkPromptStateFfi.isOpen(): Boolean =
    when (this) {
        DeviceLinkPromptStateFfi.Preparing,
        is DeviceLinkPromptStateFfi.Comparing,
        DeviceLinkPromptStateFfi.CompetingRequests,
        is DeviceLinkPromptStateFfi.RateLimited,
        is DeviceLinkPromptStateFfi.CannotLink,
        DeviceLinkPromptStateFfi.TooManyDevices,
        DeviceLinkPromptStateFfi.Linking,
        -> true
        DeviceLinkPromptStateFfi.Linked,
        is DeviceLinkPromptStateFfi.AddedElsewhere,
        is DeviceLinkPromptStateFfi.Ended,
        -> false
    }

/**
 * Every link request across [snapshots] (one per profile), oldest first. This only reads: a request on a profile
 * that is not active is listed so the user can be told about it, and is answered only after that profile is activated
 * and its prompt re-read.
 */
internal fun deviceLinkRequests(snapshots: Map<String, DeviceLinkSnapshotFfi>): List<DeviceLinkRequest> =
    snapshots
        .flatMap { (accountRef, snapshot) ->
            snapshot.prompts.map { prompt ->
                DeviceLinkRequest(
                    accountRef,
                    prompt.promptId,
                    prompt.requestedAt.toLong(),
                    open = prompt.state.isOpen(),
                    comparing = prompt.state is DeviceLinkPromptStateFfi.Comparing,
                )
            }
        }.sortedWith(
            compareBy(DeviceLinkRequest::requestedAtSeconds)
                .thenBy(DeviceLinkRequest::accountRef)
                .thenBy(DeviceLinkRequest::promptId),
        )

/**
 * Chooses which link request the app shell presents. Only an open request starts being presented, oldest first. A
 * presented request stays presented while MDK still lists it, through to its outcome, so a newer request never
 * replaces a comparison in progress. Nothing new is presented while [offer] is told the shell is blocked (the app is
 * locked or another protected flow is on screen). Closing the prompt hides that request here and answers nothing:
 * declining is a separate native action.
 */
internal data class DeviceLinkRequestQueue(
    val presented: DeviceLinkRequest? = null,
    val hidden: Map<Pair<String, String>, HiddenUntil> = emptyMap(),
) {
    /** The queue after MDK's latest [requests]. Requests MDK no longer lists are forgotten. */
    fun offer(
        requests: List<DeviceLinkRequest>,
        blocked: Boolean,
    ): DeviceLinkRequestQueue {
        val listed = requests.associateBy { it.accountRef to it.promptId }
        val stillHidden =
            hidden.filter { (id, until) ->
                val request = listed[id]
                request != null && !(until == HiddenUntil.Comparison && request.comparing)
            }
        val kept = presented?.let { listed[it.accountRef to it.promptId] }
        val startable = requests.firstOrNull { it.open && (it.accountRef to it.promptId) !in stillHidden }
        val next = kept ?: startable.takeUnless { blocked }
        return DeviceLinkRequestQueue(presented = next, hidden = stillHidden)
    }

    /** The queue after the user closed the presented request without answering it. */
    fun hidePresented(reopenForComparison: Boolean = true): DeviceLinkRequestQueue {
        val closed = presented ?: return this
        val until = if (reopenForComparison && !closed.comparing) HiddenUntil.Comparison else HiddenUntil.End
        val id = closed.accountRef to closed.promptId
        return DeviceLinkRequestQueue(presented = null, hidden = hidden + (id to until))
    }

    /** An active comparison can reopen after preparation; Not now on another profile lasts for this request. */
    enum class HiddenUntil {
        Comparison,
        End,
    }
}

/**
 * Watches link requests on every signed-in profile for the app shell. It only reads MDK's snapshots: nothing is
 * answered here, and a request on a profile that is not active waits until the user activates that profile. Reads
 * happen when observation starts (which is each time the app returns to the foreground) and when MDK reports a
 * change, never on a timer, so an expired request is never revived or extended by the host.
 */
@Stable
internal class DeviceLinkRequestWatcher(
    private val readSnapshot: suspend (accountRef: String) -> DeviceLinkSnapshotFfi,
    private val owns: () -> Boolean = { true },
    private val canPresentOutcome: (String) -> Boolean = { true },
    private val nowMs: () -> Long = ::monotonicNowMs,
) {
    var snapshots by mutableStateOf<Map<String, DeviceLinkSnapshotFfi>>(emptyMap())
        private set

    /** Per profile, when [awaitingChatCount] saw its own link become Linked, by [monotonicNowMs]. */
    var linkedAwaitingCount by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    var queue by mutableStateOf(DeviceLinkRequestQueue())
        private set

    /** How many command results each profile has had published, so a read begun before one can tell it is stale. */
    private var accepted = emptyMap<String, Long>()

    // Serializes read-and-publish so an older read can never replace a newer one.
    private val reads = Mutex()

    /** The prompt behind the presented request as MDK last described it, or null once MDK dropped it. */
    val presentedPrompt: DeviceLinkPromptFfi?
        get() = queue.presented?.let { request -> prompt(request.accountRef, request.promptId) }

    /** MDK's latest description of [promptId] on [accountRef], or null when it is not listed. */
    fun prompt(
        accountRef: String,
        promptId: String,
    ): DeviceLinkPromptFfi? = snapshots[accountRef]?.prompts?.firstOrNull { it.promptId == promptId }

    /**
     * Re-reads [accounts] and drops profiles no longer signed in. A profile whose read fails keeps its last
     * snapshot, so a transient failure never dismisses a comparison on screen.
     */
    suspend fun refresh(
        accounts: List<String>,
        blocked: Boolean,
    ) {
        reads.withLock {
            val before = accepted
            val read = accounts.mapNotNull { account -> read(account)?.let { account to it } }.toMap()
            if (!owns()) return
            // A command result published meanwhile is newer than these reads, so it stays.
            val fresh = read.filterKeys { accepted[it] == before[it] }
            publish(snapshots.filterKeys { it in accounts } + fresh, blocked)
        }
    }

    /** Re-reads the one profile [event] concerns, when it is a link change for a watched profile. */
    suspend fun onEvent(
        event: MarmotEventFfi,
        accounts: List<String>,
        blocked: Boolean,
    ) {
        val account = (event as? MarmotEventFfi.DeviceLinkChanged)?.accountLabel?.takeIf { it in accounts } ?: return
        reread(account, blocked)
    }

    /**
     * Re-reads [accountRef] alone. The host calls it after [accept], because the command's snapshot can be older
     * than a read MDK's change event already published, and no further event is due once a link has settled.
     */
    suspend fun reread(
        accountRef: String,
        blocked: Boolean,
    ) {
        reads.withLock {
            val before = accepted[accountRef]
            val snapshot = read(accountRef)
            if (snapshot != null && owns() && accepted[accountRef] == before) {
                publish(snapshots + (accountRef to snapshot), blocked)
            }
        }
    }

    /**
     * Applies a snapshot a command on [accountRef] returned, so the prompt updates without waiting for an event. A
     * read of that profile still in flight began before the command finished, so its result is dropped. Follow it
     * with [reread], which settles which of the two was newer.
     */
    fun accept(
        accountRef: String,
        snapshot: DeviceLinkSnapshotFfi,
        blocked: Boolean,
        beforeCommand: DeviceLinkSnapshotFfi?,
    ) {
        if (!owns() || snapshots[accountRef] !== beforeCommand) return
        accepted = accepted + (accountRef to (accepted[accountRef] ?: 0L) + 1L)
        publish(snapshots + (accountRef to snapshot), blocked)
    }

    /** Re-evaluates what to present after the shell became blocked or unblocked. */
    fun reoffer(blocked: Boolean) = publish(snapshots, blocked)

    /** Hides the presented request without answering it. */
    fun hidePresented(blocked: Boolean) {
        val presented = queue.presented ?: return
        queue = queue.hidePresented(reopenForComparison = canPresentOutcome(presented.accountRef))
        reoffer(blocked)
    }

    private fun publish(
        next: Map<String, DeviceLinkSnapshotFfi>,
        blocked: Boolean,
    ) {
        linkedAwaitingCount = awaitingChatCount(snapshots, next, linkedAwaitingCount, nowMs())
        snapshots = next
        val visible = deviceLinkRequests(next).filter { it.open || canPresentOutcome(it.accountRef) }
        queue = queue.offer(visible, blocked)
    }

    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure keeps the prior snapshot.
    private suspend fun read(accountRef: String): DeviceLinkSnapshotFfi? =
        try {
            readSnapshot(accountRef)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
}

/** Milliseconds from a monotonic clock, the one [DeviceLinkRequestWatcher.linkedAwaitingCount] is measured on. */
internal fun monotonicNowMs(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Profiles whose own link just became Linked and that have no chat count from MDK yet, with when that was seen
 * ([nowMs]). MDK counts the chats a new device is still owed only once the approving device's chat inventory
 * arrives, which can follow its first Adds, so until then the chat list can only say chats are on their way. A
 * link already Linked when first read is not new, and the first count ends a profile's wait for good.
 */
internal fun awaitingChatCount(
    before: Map<String, DeviceLinkSnapshotFfi>,
    after: Map<String, DeviceLinkSnapshotFfi>,
    awaiting: Map<String, Long>,
    nowMs: Long,
): Map<String, Long> =
    after
        .mapNotNull { (account, snapshot) ->
            val wasLinking = before[account]?.newDevice.let { it != null && it !is NewDeviceLinkStateFfi.Linked }
            when {
                snapshot.newDevice !is NewDeviceLinkStateFfi.Linked -> null
                snapshot.chatProgress.any { it.onNewDevice } -> null
                wasLinking -> account to nowMs
                else -> awaiting[account]?.let { account to it }
            }
        }.toMap()

/** Share the foreground watcher with chat-list readers and discard its snapshots when the native runtime changes. */
internal fun WhiteNoiseAppState.runtimeLinkRequests() =
    derivedStateOf {
        val epoch = runtimeGeneration
        DeviceLinkRequestWatcher(
            readSnapshot = { account -> marmotIo { deviceLinkSnapshot(account) } },
            owns = { runtimeGeneration == epoch },
            canPresentOutcome = { account -> activeAccountRef == account },
        )
    }
