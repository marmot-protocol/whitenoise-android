package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** Serializes mutations for the same message and emoji while leaving unrelated reactions independent. */
internal class ReactionMutationSingleFlight {
    private val mutexes = mutableMapOf<Pair<String, String>, Mutex>()

    /** Runs [mutation] after any earlier mutation for [key] has fully settled. */
    suspend fun <T> run(
        key: Pair<String, String>,
        mutation: suspend () -> T,
    ): T {
        val mutex = synchronized(mutexes) { mutexes.getOrPut(key) { Mutex() } }
        return mutex.withLock { mutation() }
    }
}

/** Waits briefly for an immediate add's event to become visible in authoritative local history. */
internal suspend fun awaitReactionEventHistory(
    expectedEmoji: String,
    attempts: Int = 6,
    retryDelayMillis: Long = 75L,
    read: suspend () -> Map<String, String>?,
): Map<String, String>? {
    require(attempts > 0) { "attempts must be positive" }
    var latest: Map<String, String>? = null
    repeat(attempts) { attempt ->
        latest = read()
        if (!latest?.get(expectedEmoji).isNullOrBlank()) return latest
        if (attempt < attempts - 1) delay(retryDelayMillis * (attempt + 1))
    }
    return latest
}

/**
 * Applies a reaction overlay before waiting for the engine, rolling it back when the authoritative
 * mutation fails or its caller is cancelled.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runOptimisticReactionMutation(
    applyOptimistic: () -> Unit,
    commit: suspend () -> Boolean,
    rollback: () -> Unit,
): Result<Boolean> {
    applyOptimistic()
    return try {
        Result.success(commit())
    } catch (cancel: CancellationException) {
        rollback()
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
 * target-wide unreact requires authoritative confirmation that [emoji] is the account's only
 * active reaction; an event-scoped delete is used whenever the tapped event is known.
 */
internal fun planOwnReactionRetraction(
    emoji: String,
    knownEventIdByEmoji: Map<String, String>,
    authoritativeOwnEmojis: Set<String>?,
    preferredEventId: String? = null,
): OwnReactionRetractionPlan {
    val preferredReactionMessageId = preferredEventId?.takeIf(String::isNotBlank)
    val reactionMessageId = knownEventIdByEmoji[emoji]?.takeIf(String::isNotBlank)
    return when {
        preferredReactionMessageId != null ->
            OwnReactionRetractionPlan.DeleteReactionMessage(preferredReactionMessageId)
        authoritativeOwnEmojis == setOf(emoji) -> OwnReactionRetractionPlan.UnreactTarget
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
 * Finds the newest active own event for each emoji in Marmot's raw local history. Same-author
 * delete events suppress older reactions so stale event ids are never retried after an unreact.
 */
internal fun activeOwnReactionEventIdsByEmoji(
    records: List<AppMessageRecordFfi>,
    activeAccountIdHex: String,
    targetMessageIdHex: String,
): Map<String, String> {
    val deletedOwnEventIds =
        records
            .asSequence()
            .filter { record ->
                MessageProjector.isDelete(record) && record.sender.equals(activeAccountIdHex, ignoreCase = true)
            }.flatMap { MessageProjector.deletedTargetMessageIds(it).asSequence() }
            .map(String::lowercase)
            .toSet()
    val newestFirst =
        compareByDescending<AppMessageRecordFfi> { it.recordedAt }
            .thenByDescending { it.receivedAt }
            .thenByDescending { it.messageIdHex }
    return records
        .asSequence()
        .filter(MessageProjector::isReaction)
        .filter { it.sender.equals(activeAccountIdHex, ignoreCase = true) }
        .filter { it.plaintext.isNotBlank() }
        .filter { MessageProjector.reactedToMessageId(it)?.equals(targetMessageIdHex, ignoreCase = true) == true }
        .filter { it.messageIdHex.lowercase() !in deletedOwnEventIds }
        .sortedWith(newestFirst)
        .distinctBy { it.plaintext }
        .associate { it.plaintext to it.messageIdHex }
}

/** Returns the newest active own reaction event for one [emoji] from authoritative history. */
internal fun activeOwnReactionEventId(
    records: List<AppMessageRecordFfi>,
    activeAccountIdHex: String,
    targetMessageIdHex: String,
    emoji: String,
): String? = activeOwnReactionEventIdsByEmoji(records, activeAccountIdHex, targetMessageIdHex)[emoji]

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
