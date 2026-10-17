package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.state.GroupChangeDeclinedException
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

private const val CHAT_DEPARTURE_STEP_TIMEOUT_MS = 30_000L
private const val CHAT_DEPARTURE_BUDGET_SLICE_MS = 250L

internal data class ChatDepartureTarget(
    val groupId: String,
    val title: String,
    val isDm: Boolean,
)

internal enum class ChatDepartureOutcome { COMPLETED, FAILED, SKIPPED, NOT_STARTED }

internal data class ChatDepartureBatchResult(
    val outcomes: Map<String, ChatDepartureOutcome>,
) {
    val completed get() = outcomes.values.count { it == ChatDepartureOutcome.COMPLETED }
    val failed get() = outcomes.values.count { it == ChatDepartureOutcome.FAILED }
    val skipped get() = outcomes.values.count { it == ChatDepartureOutcome.SKIPPED }
    val outstanding get() =
        outcomes
            .filterValues {
                it == ChatDepartureOutcome.FAILED || it == ChatDepartureOutcome.NOT_STARTED
            }.keys
}

internal class ChatDepartureCallbacks(
    val prepare: suspend (String) -> List<AppGroupMemberRecordFfi>,
    val choose: suspend (ChatDepartureTarget, List<AppGroupMemberRecordFfi>) -> AppGroupMemberRecordFfi?,
    val remove: suspend (ChatDepartureTarget, AppGroupMemberRecordFfi?) -> Boolean,
    val onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    /** True while a group-change question waits for the user; that time is not native work's to spend. */
    val questionOpen: () -> Boolean = { false },
)

private class ChatDeparturePreparation(
    targets: List<ChatDepartureTarget>,
) {
    val outcomes = targets.associate { it.groupId to ChatDepartureOutcome.NOT_STARTED }.toMutableMap()
    val successors = mutableMapOf<String, AppGroupMemberRecordFfi?>()
}

/** View orchestration only: every retry rechecks MDK; Android retains no membership/history state. */
internal suspend fun runChatDepartureBatch(
    targets: List<ChatDepartureTarget>,
    isCurrent: () -> Boolean,
    callbacks: ChatDepartureCallbacks,
): ChatDepartureBatchResult {
    val prepared = prepareChatDepartures(targets, isCurrent, callbacks)
    for (target in targets.filter { it.groupId in prepared.successors }) {
        if (!isCurrent()) break
        val completed = prepared.outcomes.values.count { it == ChatDepartureOutcome.COMPLETED }
        callbacks.onProgress(target.title, completed, targets.size)
        val removed =
            departureStep(callbacks.questionOpen) {
                callbacks.remove(target, prepared.successors[target.groupId])
            }.getOrDefault(false)
        // Replaced accounts cannot receive a late success or a retry bound to the replacement.
        if (isCurrent()) {
            val outcome = if (removed) ChatDepartureOutcome.COMPLETED else ChatDepartureOutcome.FAILED
            prepared.outcomes[target.groupId] = outcome
        }
    }
    return ChatDepartureBatchResult(prepared.outcomes.toMap())
}

/** Collect every required handover decision before any destructive command starts. */
private suspend fun prepareChatDepartures(
    targets: List<ChatDepartureTarget>,
    isCurrent: () -> Boolean,
    callbacks: ChatDepartureCallbacks,
): ChatDeparturePreparation {
    val prepared = ChatDeparturePreparation(targets)
    for (target in targets) {
        if (!isCurrent()) break
        callbacks.onProgress(target.title, 0, targets.size)
        prepareChatDeparture(target, isCurrent, callbacks, prepared)
    }
    return prepared
}

private suspend fun prepareChatDeparture(
    target: ChatDepartureTarget,
    isCurrent: () -> Boolean,
    callbacks: ChatDepartureCallbacks,
    prepared: ChatDeparturePreparation,
) {
    val preflight =
        departureStep(callbacks.questionOpen) { if (target.isDm) emptyList() else callbacks.prepare(target.groupId) }
    if (!isCurrent()) return
    if (preflight.isFailure) {
        prepared.outcomes[target.groupId] = ChatDepartureOutcome.FAILED
    } else {
        val candidates = preflight.getOrThrow()
        val successor = if (candidates.isEmpty()) null else callbacks.choose(target, candidates)
        if (isCurrent()) {
            if (candidates.isNotEmpty() && successor == null) {
                prepared.outcomes[target.groupId] = ChatDepartureOutcome.SKIPPED
            } else {
                prepared.successors[target.groupId] = successor
            }
        }
    }
}

/**
 * A UI deadline means unfinished/uncertain; retry reconciles native state before any further mutation. The deadline
 * covers native work only: while [questionOpen], the user is reading a group-change question and the clock stops. A
 * group change the user chose to wait on is unfinished too, never a silent end to the whole batch.
 */
private suspend fun <T> departureStep(
    questionOpen: () -> Boolean,
    block: suspend () -> T,
): Result<T> =
    try {
        runCatchingCancellable {
            withNativeTimeoutOrNull(CHAT_DEPARTURE_STEP_TIMEOUT_MS, questionOpen) { Result.success(block()) }
                ?: Result.failure(IllegalStateException("Native operation has not settled within the UI budget"))
        }.getOrElse { Result.failure(it) }
    } catch (declined: GroupChangeDeclinedException) {
        // Waiting on one chat's group change leaves that chat unfinished, with its stage shown, and the batch goes on.
        Result.failure(declined.refusal)
    }

/**
 * Like withTimeoutOrNull, except that time spent while [paused] does not count against [budgetMillis]. The budget is
 * spent in short slices, so a question opening or closing moves the deadline within one slice.
 */
internal suspend fun <T> withNativeTimeoutOrNull(
    budgetMillis: Long,
    paused: () -> Boolean,
    block: suspend () -> T,
): T? =
    coroutineScope {
        val work = async { block() }
        var remaining = budgetMillis
        while (!work.isCompleted && remaining > 0) {
            val slice = minOf(CHAT_DEPARTURE_BUDGET_SLICE_MS, remaining)
            withTimeoutOrNull(slice) { work.join() }
            if (!paused()) remaining -= slice
        }
        if (work.isCompleted) {
            work.await()
        } else {
            work.cancelAndJoin()
            null
        }
    }
