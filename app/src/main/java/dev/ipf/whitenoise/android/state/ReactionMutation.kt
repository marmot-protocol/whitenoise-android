package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** One optimistic change to the active account's reaction on a message. */
internal data class OptimisticReactionChange(
    val targetMessageId: String,
    val emoji: String,
    val add: Boolean,
)

/** Transient coordination between an optimistic reaction change and its native commit. */
internal data class ReactionMutationCoordination(
    val key: Pair<String, String>,
    val removeBeforeProjection: Boolean,
    val precedingAdd: Deferred<String?>?,
    val addCompletion: CompletableDeferred<String?>?,
)

/**
 * Applies a reaction overlay before waiting for the engine, rolling it back only when the
 * authoritative mutation fails.
 */
@Suppress("TooGenericExceptionCaught") // The FFI boundary can raise unchecked failures; cancellation is rethrown first.
internal suspend fun runOptimisticReactionMutation(
    applyOptimistic: () -> Unit,
    commit: suspend () -> Boolean,
    rollback: () -> Unit,
): Result<Boolean> {
    applyOptimistic()
    return try {
        Result.success(commit())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (throwable: Throwable) {
        rollback()
        Result.failure(throwable)
    }
}

/** Engine mutation chosen to remove an own reaction without affecting unrelated emoji. */
internal sealed interface OwnReactionRetractionPlan {
    data class DeleteReactionMessage(
        val messageIdHex: String,
    ) : OwnReactionRetractionPlan

    data object UnreactTarget : OwnReactionRetractionPlan

    data object Unavailable : OwnReactionRetractionPlan
}

/**
 * Selects the safest removal for an own reaction. [preferredEventId] is used only when an immediate
 * removal is paired with the exact event returned by its still-unprojected add. Otherwise,
 * target-wide unreact is preferred for a sole projected reaction, while multiple own reactions
 * require an event-scoped delete so another emoji is not cleared.
 */
internal fun planOwnReactionRetraction(
    emoji: String,
    knownEventIdByEmoji: Map<String, String>,
    ownEmojisBeforeMutation: Set<String>,
    preferredEventId: String? = null,
): OwnReactionRetractionPlan {
    val preferredReactionMessageId = preferredEventId?.takeIf(String::isNotBlank)
    val reactionMessageId = knownEventIdByEmoji[emoji]?.takeIf(String::isNotBlank)
    return when {
        preferredReactionMessageId != null ->
            OwnReactionRetractionPlan.DeleteReactionMessage(preferredReactionMessageId)
        ownEmojisBeforeMutation == setOf(emoji) -> OwnReactionRetractionPlan.UnreactTarget
        reactionMessageId != null -> OwnReactionRetractionPlan.DeleteReactionMessage(reactionMessageId)
        else -> OwnReactionRetractionPlan.Unavailable
    }
}

/** Waits for an in-flight add result, falling back to its already-cached event id. */
internal suspend fun awaitImmediateReactionEventId(
    precedingAdd: Deferred<String?>?,
    cachedEventId: () -> String?,
): String? = if (precedingAdd != null) precedingAdd.await() else cachedEventId()

/**
 * Finds the newest active reaction event for [emoji] in Marmot's raw local history. Same-author
 * delete events suppress older reactions so a stale event id is never retried after an unreact.
 */
internal fun activeOwnReactionEventId(
    records: List<AppMessageRecordFfi>,
    activeAccountIdHex: String,
    targetMessageIdHex: String,
    emoji: String,
): String? {
    val deletedOwnEventIds =
        records
            .asSequence()
            .filter { record ->
                MessageProjector.isDelete(record) && record.sender.equals(activeAccountIdHex, ignoreCase = true)
            }.flatMap { MessageProjector.deletedTargetMessageIds(it).asSequence() }
            .map(String::lowercase)
            .toSet()
    return records
        .asSequence()
        .filter(MessageProjector::isReaction)
        .filter { it.sender.equals(activeAccountIdHex, ignoreCase = true) }
        .filter { it.plaintext == emoji }
        .filter { MessageProjector.reactedToMessageId(it)?.equals(targetMessageIdHex, ignoreCase = true) == true }
        .filter { it.messageIdHex.lowercase() !in deletedOwnEventIds }
        .maxWithOrNull(compareBy<AppMessageRecordFfi>({ it.recordedAt }, { it.receivedAt }, { it.messageIdHex }))
        ?.messageIdHex
}

/** Returns optimistic overlays whose intended state now matches the authoritative sender sets. */
internal fun confirmedOptimisticReactionKeys(
    activeAccountIdHex: String?,
    optimisticChanges: Map<String, OptimisticReactionChange>,
    confirmedSendersByTarget: Map<String, Map<String, Set<String>>>,
): Set<String> {
    val mine = activeAccountIdHex?.lowercase() ?: return emptySet()
    return optimisticChanges
        .filterValues { change ->
            val senders = confirmedSendersByTarget[change.targetMessageId]?.get(change.emoji).orEmpty()
            senders.any { it.equals(mine, ignoreCase = true) } == change.add
        }.keys
}

/** Applies ordered optimistic overlays to authoritative sender sets and returns sorted tallies. */
internal fun reactionTalliesForSenders(
    activeAccountIdHex: String?,
    confirmedSendersByEmoji: Map<String, Set<String>>,
    optimisticChanges: Collection<OptimisticReactionChange>,
): List<ReactionTally> {
    val mine = activeAccountIdHex?.lowercase()
    val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
    confirmedSendersByEmoji.forEach { (emoji, senders) ->
        sendersByEmoji.getOrPut(emoji) { linkedSetOf() }.addAll(senders.map(String::lowercase))
    }
    if (mine != null) {
        optimisticChanges.forEach { change ->
            val senders = sendersByEmoji.getOrPut(change.emoji) { linkedSetOf() }
            if (change.add) {
                senders.add(mine)
            } else {
                senders.remove(mine)
            }
        }
    }
    return sendersByEmoji
        .mapNotNull { (emoji, senders) ->
            if (senders.isEmpty()) {
                null
            } else {
                ReactionTally(
                    emoji = emoji,
                    count = senders.size,
                    mine = mine != null && senders.contains(mine),
                )
            }
        }.sortedWith(
            compareByDescending<ReactionTally> { it.count }
                .thenByDescending { it.mine }
                .thenBy { it.emoji },
        )
}
