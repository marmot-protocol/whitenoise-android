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
 * still needs this user or is still in progress; a settled request only reports its outcome. [repeated] says Link is
 * no longer offered for it, because its profile had more requests lately than one person linking would make.
 */
internal data class DeviceLinkRequest(
    val accountRef: String,
    val promptId: String,
    val requestedAtSeconds: Long,
    val open: Boolean = true,
    val comparing: Boolean = false,
    val repeated: Boolean = false,
) {
    val id: Pair<String, String> get() = accountRef to promptId
}

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
 * and its prompt re-read. A request still waiting for an answer on a profile in [repeatedAccounts] is marked repeated.
 */
internal fun deviceLinkRequests(
    snapshots: Map<String, DeviceLinkSnapshotFfi>,
    repeatedAccounts: Set<String> = emptySet(),
): List<DeviceLinkRequest> =
    snapshots
        .flatMap { (accountRef, snapshot) ->
            snapshot.prompts.map { prompt ->
                DeviceLinkRequest(
                    accountRef,
                    prompt.promptId,
                    prompt.requestedAt.toLong(),
                    open = prompt.state.isOpen(),
                    comparing = prompt.state is DeviceLinkPromptStateFfi.Comparing,
                    repeated = accountRef in repeatedAccounts && prompt.state.isUnanswered(),
                )
            }
        }.sortedWith(
            compareBy(DeviceLinkRequest::requestedAtSeconds)
                .thenBy(DeviceLinkRequest::accountRef)
                .thenBy(DeviceLinkRequest::promptId),
        )

/**
 * Which link request the chat list announces and which one the app shell presents. Nothing is presented on its own:
 * MDK's requests only feed the chat list's [notice], and a request is presented only when the user [open]s it there.
 * A presented request stays presented while MDK still lists it, through to its outcome, so a newer request never
 * replaces a comparison in progress. Closing the prompt only returns the request to the notice, and putting the notice
 * off hides that request until MDK drops it. Neither answers anything: declining is a separate native action.
 */
internal data class DeviceLinkRequestQueue(
    val requests: List<DeviceLinkRequest> = emptyList(),
    val presented: DeviceLinkRequest? = null,
    val dismissed: Set<Pair<String, String>> = emptySet(),
) {
    /**
     * The one request the chat list announces: an open request that is neither on screen nor put off, a repeated one
     * first and otherwise the oldest, so a burst of requests still makes one notice.
     */
    val notice: DeviceLinkRequest?
        get() =
            requests
                .filter { it.open && it.id != presented?.id && it.id !in dismissed }
                .sortedByDescending(DeviceLinkRequest::repeated)
                .firstOrNull()

    /** The queue after MDK's latest [requests]. Requests MDK no longer lists are forgotten. */
    fun offer(requests: List<DeviceLinkRequest>): DeviceLinkRequestQueue {
        val listed = requests.associateBy(DeviceLinkRequest::id)
        return DeviceLinkRequestQueue(
            requests = requests,
            presented = presented?.let { listed[it.id] },
            dismissed = dismissed.filterTo(mutableSetOf()) { it in listed },
        )
    }

    /**
     * The queue after the user tapped the notice for [request]. Only a request MDK still lists as open opens, never
     * while the shell is [blocked] and never over a request already on screen.
     */
    fun open(
        request: DeviceLinkRequest,
        blocked: Boolean,
    ): DeviceLinkRequestQueue {
        if (blocked || presented != null) return this
        val current = requests.firstOrNull { it.id == request.id && it.open } ?: return this
        return copy(presented = current, dismissed = dismissed - current.id)
    }

    /** The queue after the user put the notice for [request] off: hidden until MDK drops it, and answered nothing. */
    fun dismiss(request: DeviceLinkRequest): DeviceLinkRequestQueue =
        if (requests.none { it.id == request.id }) this else copy(dismissed = dismissed + request.id)

    /** The queue after the user closed the presented request without answering it: it is announced again. */
    fun close(): DeviceLinkRequestQueue = copy(presented = null)
}

/**
 * Watches link requests on every signed-in profile for the app shell. It only reads MDK's snapshots: nothing is
 * answered here, and a request on a profile that is not active waits until the user activates that profile. Reads
 * happen when observation starts (which is each time the app returns to the foreground) and when MDK reports a
 * change, never on a timer of the watcher's own, so an expired request is never revived or extended by the host. The
 * one timed read is Review's: while a reviewed request is unchecked and the app is started, its profile is read
 * again until one read succeeds.
 *
 * MDK forgets a declined request at once and a settled one minutes after it settles. For one hour, the watcher
 * keeps when it first saw each unanswered request, including across process restarts. That is
 * all it needs to tell repeated requests apart; see [unansweredLinkRequests].
 */
@Stable
internal class DeviceLinkRequestWatcher(
    private val readSnapshot: suspend (accountRef: String) -> DeviceLinkSnapshotFfi,
    private val owns: () -> Boolean = { true },
    private val canPresentOutcome: (String) -> Boolean = { true },
    private val nowMs: () -> Long = ::monotonicNowMs,
    private val historyNowMs: () -> Long = nowMs,
    private val history: DeviceLinkRequestHistory? = null,
) {
    var snapshots by mutableStateOf<Map<String, DeviceLinkSnapshotFfi>>(emptyMap())
        private set

    /** Per profile, how many reads of it have succeeded and been published, so a caller can wait for a fresh one. */
    var successfulReads by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** Per profile, when [awaitingChatCount] saw its own link become Linked, by [monotonicNowMs]. */
    var linkedAwaitingCount by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    var queue by mutableStateOf(DeviceLinkRequestQueue())
        private set

    /** Whether the shell was last reported blocked, so a tap on the chat list's notice opens nothing until unlock. */
    var blocked by mutableStateOf(false)
        private set

    /** Per profile, when each unanswered request was first seen, by [historyNowMs]. */
    private var unanswered = history?.read().orEmpty()

    private var watchedAccounts: Set<String>? = null

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
    suspend fun refresh(accounts: List<String>) {
        reads.withLock {
            val before = accepted
            val read = accounts.mapNotNull { account -> read(account)?.let { account to it } }.toMap()
            if (!owns()) return
            // A command result published meanwhile is newer than these reads, so it stays.
            val fresh = read.filterKeys { accepted[it] == before[it] }
            successfulReads = successfulReads.counted(fresh.keys)
            watchedAccounts = accounts.toSet()
            publish(snapshots.filterKeys { it in accounts } + fresh)
        }
    }

    /** Re-reads the one profile [event] concerns, when it is a link change for a watched profile. */
    suspend fun onEvent(
        event: MarmotEventFfi,
        accounts: List<String>,
    ) {
        val account = (event as? MarmotEventFfi.DeviceLinkChanged)?.accountLabel?.takeIf { it in accounts } ?: return
        reread(account)
    }

    /**
     * Re-reads [accountRef] alone. The host calls it after [accept], because the command's snapshot can be older
     * than a read MDK's change event already published, and no further event is due once a link has settled.
     */
    suspend fun reread(accountRef: String) {
        reads.withLock {
            val before = accepted[accountRef]
            val snapshot = read(accountRef)
            if (snapshot != null && owns() && accepted[accountRef] == before) {
                successfulReads = successfulReads.counted(setOf(accountRef))
                publish(snapshots + (accountRef to snapshot))
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
        beforeCommand: DeviceLinkSnapshotFfi?,
    ) {
        if (!owns() || snapshots[accountRef] !== beforeCommand) return
        accepted = accepted + (accountRef to (accepted[accountRef] ?: 0L) + 1L)
        publish(snapshots + (accountRef to snapshot))
    }

    /** The shell owns blocking. A suspended snapshot read must never restore an older value. */
    fun setBlocked(blocked: Boolean) {
        this.blocked = blocked
    }

    /** Re-evaluates settled outcomes after the active profile changes. */
    fun reoffer() = publish(snapshots)

    /** Presents [request] because the user tapped its notice. Nothing opens while the shell is blocked. */
    fun open(request: DeviceLinkRequest) {
        queue = queue.open(request, blocked)
    }

    /** Puts the notice for [request] off until MDK drops it, without answering it. */
    fun dismiss(request: DeviceLinkRequest) {
        queue = queue.dismiss(request)
    }

    /** Closes the presented request without answering it; the chat list announces it again. */
    fun close() {
        queue = queue.close()
    }

    private fun publish(next: Map<String, DeviceLinkSnapshotFfi>) {
        val now = nowMs()
        linkedAwaitingCount = awaitingChatCount(snapshots, next, linkedAwaitingCount, now)
        val historyTime = historyNowMs()
        val previous = unanswered
        val unreadAccounts = previous.filterKeys { it !in next && watchedAccounts?.contains(it) != false }
        unanswered = unansweredLinkRequests(previous, next, historyTime) +
            unreadAccounts
                .mapValues { (_, prompts) ->
                    prompts.filterValues { historyTime - it in 0 until REPEATED_LINK_REQUEST_WINDOW_MS }
                }.filterValues { it.isNotEmpty() }
        if (unanswered != previous) history?.write(unanswered)
        snapshots = next
        val repeated = repeatedLinkRequestAccounts(unanswered)
        val visible = deviceLinkRequests(next, repeated).filter { it.open || canPresentOutcome(it.accountRef) }
        queue = queue.offer(visible)
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

/** These read counts with one more successful read of each of [accounts]. */
private fun Map<String, Long>.counted(accounts: Set<String>): Map<String, Long> = this + accounts.associateWith { (this[it] ?: 0L) + 1L }

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
            historyNowMs = System::currentTimeMillis,
            history = DeviceLinkRequestHistory(appContext),
        )
    }
