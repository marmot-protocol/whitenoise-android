package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One optimistic change to the active account's reaction on a message. */
internal data class OptimisticReactionChange(
    val targetMessageId: String,
    val emoji: String,
    val add: Boolean,
)

/** Latest optimistic intent for one message and emoji. */
internal data class ReactionIntentSnapshot(
    val desiredMine: Boolean,
    val revision: Long,
)

/** Result of submitting a reaction intent; only the first caller drains the shared state. */
internal data class ReactionIntentSubmission(
    val shouldDrain: Boolean,
)

/**
 * Conflates rapid taps for the same message and emoji into their latest desired state. The first
 * caller owns native convergence; later callers update the intent and return immediately.
 */
internal class ReactionIntentConflator {
    private data class State(
        var desiredMine: Boolean,
        var revision: Long,
    )

    private val states = mutableMapOf<Pair<String, String>, State>()

    /** Publishes [desiredMine] and reports whether this caller must start the drain loop. */
    fun submit(
        key: Pair<String, String>,
        desiredMine: Boolean,
    ): ReactionIntentSubmission =
        synchronized(states) {
            val shouldDrain = key !in states
            val state = states.getOrPut(key) { State(desiredMine, revision = 0L) }
            state.desiredMine = desiredMine
            state.revision += 1L
            ReactionIntentSubmission(shouldDrain)
        }

    /** Returns the newest intent while the key has an active drain owner. */
    fun latest(key: Pair<String, String>): ReactionIntentSnapshot? =
        synchronized(states) {
            states[key]?.let { ReactionIntentSnapshot(it.desiredMine, it.revision) }
        }

    /** Finishes only when no newer tap superseded [revision]. */
    fun finishIfCurrent(
        key: Pair<String, String>,
        revision: Long,
    ): Boolean =
        synchronized(states) {
            val state = states[key] ?: return@synchronized true
            if (state.revision != revision) return@synchronized false
            states.remove(key)
            true
        }

    /** Releases a drain owner after cancellation or a terminal failure. */
    fun abandon(key: Pair<String, String>) {
        synchronized(states) { states.remove(key) }
    }
}

/** Final result of converging one conflated reaction intent. */
internal sealed interface ReactionIntentDrainOutcome {
    data class Settled(
        val finalMine: Boolean,
        val mutated: Boolean,
        val addedReaction: Boolean,
    ) : ReactionIntentDrainOutcome

    data class Failed(
        val throwable: Throwable,
    ) : ReactionIntentDrainOutcome
}

/**
 * Converges native state to the latest tap while allowing newer taps to supersede in-flight work.
 * The short quiet period collapses a rapid add/remove pair before it reaches Marmot.
 */
internal suspend fun drainReactionIntent(
    key: Pair<String, String>,
    conflator: ReactionIntentConflator,
    initialMine: Boolean,
    settleDelayMillis: Long = 150L,
    commit: suspend (desiredMine: Boolean) -> Unit,
): ReactionIntentDrainOutcome =
    try {
        drainReactionIntentLoop(key, conflator, initialMine, settleDelayMillis, commit)
    } catch (cancel: CancellationException) {
        conflator.abandon(key)
        throw cancel
    }

/** Runs the conflated reaction state machine after cancellation ownership has been established. */
private suspend fun drainReactionIntentLoop(
    key: Pair<String, String>,
    conflator: ReactionIntentConflator,
    initialMine: Boolean,
    settleDelayMillis: Long,
    commit: suspend (desiredMine: Boolean) -> Unit,
): ReactionIntentDrainOutcome {
    var committedMine = initialMine
    var mutated = false
    var addedReaction = false
    var outcome: ReactionIntentDrainOutcome? = null
    delay(settleDelayMillis)
    while (outcome == null) {
        val intent = conflator.latest(key)
        if (intent == null) {
            outcome = ReactionIntentDrainOutcome.Settled(committedMine, mutated, addedReaction)
        } else {
            var supersededFailure = false
            if (intent.desiredMine != committedMine) {
                val failure = runReactionIntentCommit(intent.desiredMine, commit).exceptionOrNull()
                if (failure == null) {
                    committedMine = intent.desiredMine
                    mutated = true
                    addedReaction = addedReaction || committedMine
                } else if (conflator.latest(key)?.revision == intent.revision) {
                    conflator.abandon(key)
                    outcome = ReactionIntentDrainOutcome.Failed(failure)
                } else {
                    supersededFailure = true
                }
            }
            if (outcome == null && !supersededFailure && conflator.finishIfCurrent(key, intent.revision)) {
                outcome = ReactionIntentDrainOutcome.Settled(committedMine, mutated, addedReaction)
            } else if (outcome == null) {
                delay(settleDelayMillis)
            }
        }
    }
    return outcome
}

/** Converts a native reaction failure to a value while preserving structured cancellation. */
@Suppress("TooGenericExceptionCaught")
private suspend fun runReactionIntentCommit(
    desiredMine: Boolean,
    commit: suspend (desiredMine: Boolean) -> Unit,
): Result<Unit> =
    try {
        commit(desiredMine)
        Result.success(Unit)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

/** True only for native back-pressure that rejected the mutation before accepting it. */
internal fun isRetryableReactionMutationFailure(throwable: Throwable): Boolean {
    when (throwable) {
        is MarmotKitException.AccountWorkerBusy,
        is MarmotKitException.RuntimeBusy,
        is MarmotKitException.AccountSessionBusy,
        is MarmotKitException.StorageBusy,
        is MarmotKitException.GroupSendQueueFull,
        -> return true
        else -> Unit
    }
    val detail = throwable.message.orEmpty().lowercase()
    return "pendingpublish" in detail || "pending publish" in detail || "mutation is in flight" in detail
}

/** Retries only explicit native back-pressure, stopping as soon as the user changes intent. */
@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal suspend fun <T> retryBusyReactionMutation(
    attempts: Int = 4,
    retryDelayMillis: Long = 100L,
    stillDesired: () -> Boolean = { true },
    block: suspend () -> T,
): T {
    require(attempts > 0) { "attempts must be positive" }
    var retryableFailure: Throwable? = null
    repeat(attempts) { attempt ->
        retryableFailure?.let { failure ->
            if (!stillDesired()) throw failure
        }
        try {
            return block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            if (!stillDesired() || !isRetryableReactionMutationFailure(throwable)) throw throwable
            retryableFailure = throwable
            if (attempt == attempts - 1) throw throwable
            delay(retryDelayMillis * (attempt + 1L))
        }
    }
    error("unreachable reaction retry state")
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
