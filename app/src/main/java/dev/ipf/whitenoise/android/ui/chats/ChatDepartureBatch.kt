package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.withTimeoutOrNull

private const val CHAT_DEPARTURE_STEP_TIMEOUT_MS = 30_000L

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
    for (target in targets) {
        if (!isCurrent()) break
        if (target.groupId !in prepared.successors) continue
        val completed = prepared.outcomes.values.count { it == ChatDepartureOutcome.COMPLETED }
        callbacks.onProgress(target.title, completed, targets.size)
        val removed =
            departureStep { callbacks.remove(target, prepared.successors[target.groupId]) }.getOrDefault(false)
        // Replaced accounts cannot receive a late success or a retry bound to the replacement.
        if (!isCurrent()) break
        prepared.outcomes[target.groupId] = if (removed) ChatDepartureOutcome.COMPLETED else ChatDepartureOutcome.FAILED
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
        val preflight = departureStep { if (target.isDm) emptyList() else callbacks.prepare(target.groupId) }
        if (!isCurrent()) break
        if (preflight.isFailure) {
            prepared.outcomes[target.groupId] = ChatDepartureOutcome.FAILED
            continue
        }
        val candidates = preflight.getOrThrow()
        val successor = if (candidates.isEmpty()) null else callbacks.choose(target, candidates)
        if (!isCurrent()) break
        if (candidates.isNotEmpty() && successor == null) {
            prepared.outcomes[target.groupId] = ChatDepartureOutcome.SKIPPED
        } else {
            prepared.successors[target.groupId] = successor
        }
    }
    return prepared
}

/** A UI deadline means unfinished/uncertain; retry reconciles native state before any further mutation. */
private suspend fun <T> departureStep(block: suspend () -> T): Result<T> =
    runCatchingCancellable {
        withTimeoutOrNull(CHAT_DEPARTURE_STEP_TIMEOUT_MS) { Result.success(block()) }
            ?: Result.failure(IllegalStateException("Native operation has not settled within the UI budget"))
    }.getOrElse { Result.failure(it) }
