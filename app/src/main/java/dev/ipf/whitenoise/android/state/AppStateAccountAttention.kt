package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountAttentionSnapshotFfi
import dev.ipf.marmotkit.AccountAttentionStateFfi
import dev.ipf.marmotkit.AccountAttentionTotalFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Badge total for one account: unread messages plus conversations that need attention without unread messages. */
internal fun AccountAttentionTotalFfi.badgeCount(): ULong = unreadCount + attentionOnlyConversations

/**
 * Mirrors MDK's live per-account attention summary into the unread store.
 *
 * The summary is account-independent, so one subscription outlives account switches and ends when the
 * runtime closes. Ready totals become confirmed badge counts; an unavailable account keeps its retained
 * count but is marked provisional rather than read as zero. Pending invitations are already counted by
 * MDK, so nothing is added here.
 */
internal class AccountAttentionMirror {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** The running receive loop, for a finishing loop to check it still owns the mirror. */
    val currentJob: Job? get() = job

    /** Newest replacement received from the runtime, or null before the first one. */
    var latest: AccountAttentionSnapshotFfi? = null
        private set

    /** Replaces any running mirror with one bound to [marmot]; the previous loop is cancelled first. */
    fun start(
        appState: WhiteNoiseAppState,
        marmot: MarmotInterface,
    ) {
        job?.cancel()
        latest = null
        job = scope.launch { appState.runAccountAttentionMirror(this@AccountAttentionMirror, marmot) }
    }

    /** Stops the mirror; the unread store keeps its last values as provisional evidence. */
    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun install(snapshot: AccountAttentionSnapshotFfi) {
        latest = snapshot
    }
}

/** Badge counts keyed by account id from the newest live summary, or the legacy query before the first one. */
internal suspend fun WhiteNoiseAppState.accountAttentionCounts(): Map<String, ULong> {
    accountAttentionMirror.latest?.let { snapshot -> return snapshot.readyBadgeCounts() }
    return marmotIo(MarmotTraceSection.UNREAD_SUMMARY) {
        accountUnreadSummary().associate { it.accountIdHex to it.unreadCount }
    }
}

/** Ready accounts only; unavailable entries are omitted so callers treat them as unconfirmed. */
internal fun AccountAttentionSnapshotFfi.readyBadgeCounts(): Map<String, ULong> =
    accounts
        .mapNotNull { entry ->
            val ready = entry.state as? AccountAttentionStateFfi.Ready ?: return@mapNotNull null
            entry.accountIdHex to ready.total.badgeCount()
        }.toMap()

/** Applies one live summary to the unread store using the signed-in account list to map ids to refs. */
internal fun WhiteNoiseAppState.applyAccountAttention(snapshot: AccountAttentionSnapshotFfi) {
    accountAttentionMirror.install(snapshot)
    val refsByAccountId = accounts.associate { it.accountIdHex to it.label }
    snapshot.accounts.forEach { entry ->
        val ref = refsByAccountId[entry.accountIdHex] ?: return@forEach
        when (val state = entry.state) {
            is AccountAttentionStateFfi.Ready -> updateAccountUnreadCount(ref, state.total.badgeCount())
            is AccountAttentionStateFfi.Unavailable -> accountUnreadStore.markUnknown(ref)
        }
    }
}

/**
 * Receive loop: install the initial summary once, then replace it from `next()` until the stream ends.
 * A failing summary must never take the runtime down, so any failure is logged and the mirror ends.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun WhiteNoiseAppState.runAccountAttentionMirror(
    mirror: AccountAttentionMirror,
    marmot: MarmotInterface,
) {
    val subscription =
        try {
            withContext(Dispatchers.IO) { marmot.subscribeAccountAttention() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            appStateDebug(failure) { "account attention subscription unavailable" }
            return
        }
    try {
        withContext(Dispatchers.IO) { subscription.snapshot() }?.let(::applyAccountAttention)
        while (currentCoroutineContext().isActive) {
            val next = withContext(Dispatchers.IO) { subscription.next() } ?: break
            applyAccountAttention(next)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Throwable) {
        appStateDebug(failure) { "account attention mirror ended" }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { subscription.close() } }
        mirror.stopIfCurrent(currentCoroutineContext()[Job])
    }
}

/** Clears the mirror's job only when the ending loop is still the one it owns. */
private fun AccountAttentionMirror.stopIfCurrent(job: Job?) {
    if (job != null && job === currentJob) stop()
}
