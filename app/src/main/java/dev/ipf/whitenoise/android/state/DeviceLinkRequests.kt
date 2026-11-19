package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotEventFfi
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
                DeviceLinkRequest(accountRef, prompt.promptId, prompt.requestedAt.toLong(), prompt.state.isOpen())
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
    val hidden: Set<Pair<String, String>> = emptySet(),
) {
    /** The queue after MDK's latest [requests]. Requests MDK no longer lists are forgotten. */
    fun offer(
        requests: List<DeviceLinkRequest>,
        blocked: Boolean,
    ): DeviceLinkRequestQueue {
        val listed = requests.associateBy { it.accountRef to it.promptId }
        val stillHidden = hidden.intersect(listed.keys)
        val kept = presented?.let { listed[it.accountRef to it.promptId] }
        val startable = requests.firstOrNull { it.open && (it.accountRef to it.promptId) !in stillHidden }
        val next = kept ?: startable.takeUnless { blocked }
        return DeviceLinkRequestQueue(presented = next, hidden = stillHidden)
    }

    /** The queue after the user closed the presented request without answering it. */
    fun hidePresented(): DeviceLinkRequestQueue {
        val closed = presented ?: return this
        return DeviceLinkRequestQueue(presented = null, hidden = hidden + (closed.accountRef to closed.promptId))
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
) {
    var snapshots by mutableStateOf<Map<String, DeviceLinkSnapshotFfi>>(emptyMap())
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
        reads.withLock {
            val before = accepted[account]
            val snapshot = read(account)
            if (snapshot != null && owns() && accepted[account] == before) {
                publish(snapshots + (account to snapshot), blocked)
            }
        }
    }

    /**
     * Applies a snapshot a command on [accountRef] returned, so the prompt updates without waiting for an event. A
     * read of that profile still in flight began before the command finished, so its result is dropped.
     */
    fun accept(
        accountRef: String,
        snapshot: DeviceLinkSnapshotFfi,
        blocked: Boolean,
    ) {
        if (!owns()) return
        accepted = accepted + (accountRef to (accepted[accountRef] ?: 0L) + 1L)
        publish(snapshots + (accountRef to snapshot), blocked)
    }

    /** Re-evaluates what to present after the shell became blocked or unblocked. */
    fun reoffer(blocked: Boolean) = publish(snapshots, blocked)

    /** Hides the presented request without answering it. */
    fun hidePresented(blocked: Boolean) {
        queue = queue.hidePresented().offer(deviceLinkRequests(snapshots), blocked)
    }

    private fun publish(
        next: Map<String, DeviceLinkSnapshotFfi>,
        blocked: Boolean,
    ) {
        snapshots = next
        queue = queue.offer(deviceLinkRequests(next), blocked)
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
