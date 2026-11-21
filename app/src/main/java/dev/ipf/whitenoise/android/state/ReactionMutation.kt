package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ConversationMessageReferencesFfi
import dev.ipf.marmotkit.ConversationReactionsFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Captures native identity and a presentation admission check for delayed reaction dispatch. */
internal data class ReactionMutationTarget(
    val account: String,
    val messageId: String,
    val stillAvailable: () -> Boolean,
) {
    fun requireAvailable() {
        check(stillAvailable()) { "Reaction target is no longer available" }
    }
}

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

/** The viewer’s state for a chip; an omitted kind in a bounded reference has no known state. */
internal fun ConversationReactionsFfi.viewerReactedTo(emoji: String): Boolean? =
    items.firstOrNull { it.emoji == emoji }?.viewerReacted ?: false.takeIf { omittedKinds == 0uL }

/** Settles from the window reference when present, otherwise from a loaded legacy sender summary. */
internal fun confirmedOptimisticReactionKeys(
    activeAccountIdHex: String?,
    optimisticChanges: Map<String, OptimisticReactionChange>,
    confirmedSendersByTarget: Map<String, Map<String, Set<String>>>,
    references: Map<String, ConversationMessageReferencesFfi>?,
): Set<String> {
    val mine = activeAccountIdHex?.lowercase() ?: return emptySet()
    return optimisticChanges
        .filterValues { change ->
            val reference = references?.get(change.targetMessageId)
            val viewerReacted =
                if (reference != null) {
                    reference.reactions.viewerReactedTo(change.emoji)
                } else {
                    confirmedSendersByTarget[change.targetMessageId]?.let { byEmoji ->
                        byEmoji[change.emoji].orEmpty().any { it.equals(mine, ignoreCase = true) }
                    }
                }
            viewerReacted == change.add
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
