package dev.ipf.whitenoise.android.state

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.HistoryNoticeCauseFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Re-reads group recovery after each authoritative group-state edge. The parked flag is read separately, by the
 * raw-event loop, so this open-path read stays a single account-worker command.
 */
@Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure is classified for presentation.
internal suspend fun ConversationController.refreshGroupRecoveryStatus() {
    val accountRef = conversationAccountRef ?: return
    val groupIdHex = group.groupIdHex
    val runtimeGeneration = appState.runtimeGeneration
    val recoveryEpoch = groupRecoveryLifetime.capture()
    val status =
        try {
            retryTransientGroupRecoveryRead {
                groupRecoveryStatusReader(accountRef, groupIdHex)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            // An advisory read that has never succeeded for a group this account just created
            // is not evidence of anything; see groupRecoveryReadFailureIsPresentable.
            val presentable =
                groupRecoveryReadFailureIsPresentable(
                    lastConfirmedStatus = groupRecoveryStatus,
                    freshlyCreated = isFreshlyCreatedGroup(groupIdHex),
                    failure = failure,
                )
            groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
                if (ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)) {
                    groupRecoveryReadFailed = presentable
                }
            }
            return
        }
    val rejoined = firstGroupNoticeRejoined(accountRef, status)
    groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
        if (
            ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration) &&
            status.groupIdHex == groupIdHex
        ) {
            groupRecoveryStatus = status
            groupHistoryNoticeRejoined = rejoined
            groupRecoveryReadFailed = false
            appState.freshGroupCreations.settle(accountRef, groupIdHex, runtimeGeneration)
        }
    }
}

/**
 * Whether the first of [status]'s notice ids is a rejoin notice, the one the card words. The cause lives only in
 * the account's notice list, and a failed advisory read falls back to the general wording.
 */
@Suppress("TooGenericExceptionCaught") // Any non-cancellation native failure keeps the general wording.
internal suspend fun ConversationController.firstGroupNoticeRejoined(
    accountRef: String,
    status: GroupRecoveryStatusFfi,
): Boolean {
    val first = status.historyNoticeIds.firstOrNull() ?: return false
    val notices =
        try {
            historyNoticesReader(accountRef)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            emptyList()
        }
    return notices.firstOrNull { it.noticeId == first }?.cause == HistoryNoticeCauseFfi.BEFORE_REJOIN
}

/** Whether this conversation is the group whose creation was opened most recently. */
private fun ConversationController.isFreshlyCreatedGroup(groupIdHex: String): Boolean =
    appState.freshGroupCreations.isFresh(conversationAccountRef, groupIdHex, appState.runtimeGeneration)

/**
 * Reads the parked flag now, or, when a read is already running, makes that reader read once more after it
 * finishes. A burst of events therefore costs at most one read in flight and one after it.
 */
@VisibleForTesting
internal suspend fun ConversationController.requestGroupConvergenceRead() {
    groupConvergenceReadRequested.set(true)
    while (groupConvergenceReadRequested.get() && groupConvergenceReadGate.tryLock()) {
        try {
            while (groupConvergenceReadRequested.getAndSet(false)) refreshGroupConvergenceParked()
        } finally {
            groupConvergenceReadGate.unlock()
        }
    }
}

/**
 * Reads the parked flag for Retry through the same gate as event reads, so a reread that a stale answer asks for
 * still runs. Returns the newest published answer, or null when none was published.
 */
internal suspend fun ConversationController.readGroupConvergenceParkedAfterRetry(): Boolean? {
    val answer =
        groupConvergenceReadGate.withLock {
            var latest = refreshGroupConvergenceParked()
            while (groupConvergenceReadRequested.getAndSet(false)) {
                refreshGroupConvergenceParked()?.let { latest = it }
            }
            latest
        }
    // A request made just as the gate was released still gets its read.
    if (groupConvergenceReadRequested.get()) requestGroupConvergenceRead()
    return answer
}

/**
 * Reads whether MDK parked this chat's convergence and publishes the answer under the recovery guards. Returns
 * the published answer, or null when the read failed or lost ownership. A failed read keeps the last known
 * answer, since it says nothing about whether the chat is parked.
 */
private suspend fun ConversationController.refreshGroupConvergenceParked(): Boolean? {
    val accountRef = conversationAccountRef
    val groupIdHex = group.groupIdHex
    val runtimeGeneration = appState.runtimeGeneration
    val recoveryEpoch = groupRecoveryLifetime.capture()
    val readEpoch = groupConvergenceReads.advance()
    // A relay's lasting refusal of a confirmed change is read beside the flag, each keeping its own last answer.
    val refused = accountRef?.let { account -> readGroupCommitRefusal(account, groupIdHex) }
    // Any non-cancellation native failure keeps the last known answer.
    val parked =
        accountRef?.let { account ->
            runCatchingCancellable { groupConvergenceParkedReader(account, groupIdHex) }.getOrNull()
        }
    var published = false
    groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
        if (
            groupConvergenceReads.isCurrent(readEpoch) &&
            accountRef != null &&
            ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)
        ) {
            refused?.let(::publishGroupCommitRefusal)
            if (parked != null) {
                groupConvergenceParked = parked
                if (!parked) groupConvergenceRetryFailure = null
                published = true
            }
        }
    }
    if (parked == null) return null
    // A group-state edge during the read only made this answer stale, so read again while this chat is still owned.
    // The rereads are bounded, so a steady stream of edges can't spin; the next event or foreground return reads.
    if (published) {
        groupConvergenceStaleRereads = 0
    } else if (
        groupConvergenceStaleRereads < MAX_GROUP_CONVERGENCE_STALE_REREADS &&
        groupConvergenceReads.isCurrent(readEpoch) &&
        ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)
    ) {
        groupConvergenceStaleRereads++
        groupConvergenceReadRequested.set(true)
    }
    return if (published) parked else null
}

/**
 * Reads the parked flag on entry and on each raw group-state event for this chat, which MDK fires whenever the
 * group's state changes, parks and resumes included. It starts only after the first open settles, so the open
 * path never waits on this account-worker read. Every successful open, the first included, reads once, since
 * an edge may pass before the stream exists. A stream that ends or fails is destroyed and reopened after a pause.
 */
@VisibleForTesting
internal suspend fun ConversationController.runGroupConvergenceEventLoop(
    account: String,
    awaitOpen: suspend () -> Unit = { groupConvergenceOpenSettled.await() },
    retryMillis: Long = GROUP_CONVERGENCE_EVENT_RETRY_MILLIS,
) {
    val groupId = group.groupIdHex
    val runtime = conversationRuntimeGeneration

    fun ownsStream() = ownsGroupRecoveryRead(account, groupId, runtime)

    // Retirement and publication share the session lock, including a native read that completes after teardown.
    fun invalidateEligibility(): Boolean =
        synchronized(timelineSession.lock) {
            val owned = ownsStream()
            if (owned) accountDevicesGroupRevision++
            owned
        }

    awaitOpen()
    while (currentCoroutineContext().isActive && ownsStream()) {
        val stream = runCatchingCancellable { runtimeEventStreamOpener() }.getOrNull()
        if (stream != null) {
            try {
                if (!invalidateEligibility()) return
                requestGroupConvergenceRead()
                runCatchingCancellable {
                    watchGroupConvergenceEvents(stream, account, groupId, ::ownsStream, ::invalidateEligibility)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.destroy() } }
            }
        }
        if (ownsStream()) delay(retryMillis)
    }
}

/** Checks ownership again after each native read before applying any group capability change. */
private suspend fun ConversationController.watchGroupConvergenceEvents(
    stream: RuntimeEventStream,
    account: String,
    groupId: String,
    ownsStream: () -> Boolean,
    invalidateEligibility: () -> Boolean,
) {
    while (currentCoroutineContext().isActive && ownsStream()) {
        val event = stream.next() ?: break
        val groupUpdated = event.isGroupStateUpdateFor(account, groupId)
        if (groupUpdated || event.isAccountDevicesEpochChange(account, groupId)) {
            if (!invalidateEligibility()) break
            if (groupUpdated) requestGroupConvergenceRead()
        }
    }
}

/** Whether this controller still owns UI publication for the captured group. */
internal fun ConversationController.ownsGroupRecoveryGroup(groupIdHex: String): Boolean =
    !controllerCleared && !isAccountTeardownRequested() && group.groupIdHex == groupIdHex

/** Whether the captured account, group, and runtime still own recovery-read publication. */
internal fun ConversationController.ownsGroupRecoveryRead(
    accountRef: String,
    groupIdHex: String,
    runtimeGeneration: Int,
): Boolean =
    conversationAccountRef == accountRef &&
        appState.runtimeGeneration == runtimeGeneration &&
        ownsGroupRecoveryGroup(groupIdHex)
