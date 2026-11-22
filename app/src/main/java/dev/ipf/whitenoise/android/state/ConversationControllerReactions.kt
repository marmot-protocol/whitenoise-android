package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Reactions must wait for a blocked leave to be resolved, even with seeded membership. */
internal fun ConversationController.reactionAccountIfAccepted(): String? {
    val accountRef = conversationAccountRef
    if (
        showsBlockedLeave ||
        !canAcceptReaction(
            accountRef = accountRef,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            selfLeft = selfMembership.selfLeft,
            unrecoverable = group.unrecoverable,
            disbanding = group.disbanding,
            disbanded = group.disbanded,
        )
    ) {
        appState.present(R.string.toast_reaction_failed)
        return null
    }
    return accountRef
}

/** Publishes an add and tracks its ownership until the conversation reference catches up. */
@Suppress("LongMethod") // Resolution, upload and publication share one owner.
private suspend fun ConversationController.commitReactionAdd(
    owner: ReactionMutationTarget,
    emoji: String,
) {
    val account = owner.account
    val target = owner.messageId
    val key = target.lowercase() to emoji
    val ensureCurrent = {
        owner.requireAvailable()
        if (appState.activeAccountRef != account ||
            appState.runtimeGeneration != conversationRuntimeGeneration ||
            appState.mediaUploadSessionEpoch() != mediaUploadSessionEpoch
        ) {
            throw CancellationException("reaction artwork owner changed")
        }
    }
    ensureCurrent()
    val artwork =
        if (dev.ipf.whitenoise.android.core.Nip30Emoji
                .shortcodesIn(emoji)
                .singleOrNull() == emoji
        ) {
            emojiArtworkForSend(account, emoji)
        } else {
            emptyList()
        }
    ensureCurrent()
    val uploadKey = "reaction:$target:$emoji"
    val uploadJob =
        if (artwork.isEmpty()) null else appState.trackInFlightMediaUpload(account, group.groupIdHex, uploadKey)
    try {
        val controllerContext = currentCoroutineContext()
        val publishLock: EmojiPublishLock = { publish ->
            withContext(controllerContext) {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    ensureCurrent()
                    appState.untrackInFlightMediaUpload(account, group.groupIdHex, uploadKey, uploadJob)
                    appState.marmotIo(MarmotTraceSection.MESSAGE_REACT) { publish() }
                }
            }
        }
        if (artwork.isEmpty()) {
            publishLock { appState.marmot().reactToMessage(account, group.groupIdHex, target, emoji) }
        } else {
            appState.marmotIo(MarmotTraceSection.MESSAGE_REACT) {
                sendReactionWithCustomEmoji(
                    account,
                    group.groupIdHex,
                    target,
                    emoji,
                    artwork,
                    appState.emojiUploads,
                    ensureCurrent = { withContext(controllerContext) { ensureCurrent() } },
                    publishLock = publishLock,
                )
            }
        }
        if (window.references(target)?.reactions?.viewerReactedTo(emoji) != true) {
            unprojectedOwnReactions.add(key)
        }
    } finally {
        appState.untrackInFlightMediaUpload(account, group.groupIdHex, uploadKey, uploadJob)
    }
}

/** Retracts every matching own event, including duplicates from linked devices, in one native mutation. */
private suspend fun ConversationController.commitReactionRemoval(
    owner: ReactionMutationTarget,
    emoji: String,
) {
    val target = owner.messageId
    appState.withGroupCommitLock(owner.account, group.groupIdHex) {
        owner.requireAvailable()
        try {
            appState.marmotIo { unreactFromMessageMatching(owner.account, group.groupIdHex, target, emoji) }
        } catch (absent: MarmotKitException.ReactionNotFound) {
            // A sibling may have removed it already. Confirm absence on this device before settling the tap.
            if (readOwnReaction(target, emoji)) throw absent
        }
        unprojectedOwnReactions.remove(target.lowercase() to emoji)
    }
}

/** Drives native state toward the newest intent without blocking later optimistic taps. */
internal suspend fun ConversationController.convergeReactionIntent(
    owner: ReactionMutationTarget,
    emoji: String,
    key: Pair<String, String>,
    initialMine: Boolean,
): ReactionIntentDrainOutcome =
    drainReactionIntent(key, reactionIntentConflator, initialMine) { commitMine ->
        retryBusyReactionMutation(
            stillDesired = { reactionIntentConflator.latest(key)?.desiredMine == commitMine },
        ) {
            if (commitMine) commitReactionAdd(owner, emoji) else commitReactionRemoval(owner, emoji)
        }
    }

internal fun ConversationController.recomputeReactions() {
    // Lowercased to match baseReactionSenders(): hex account-id casing can
    // drift between the active account and reaction senders, and a mismatch
    // would render your own reaction as not-mine. See #143.
    val mine = conversationAccountIdHex?.lowercase()
    val sendersByTarget = baseReactionSenders()
    if (mine != null) {
        optimisticReactionChanges.values.forEach { change ->
            val sendersByEmoji = sendersByTarget.getOrPut(change.targetMessageId) { linkedMapOf() }
            val senders = sendersByEmoji.getOrPut(change.emoji) { linkedSetOf() }
            if (change.add) {
                senders.add(mine)
            } else {
                senders.remove(mine)
            }
        }
    }
    val computed =
        reactionTalliesForWindow(sendersByTarget, window.frame?.references, optimisticReactionChanges.values, mine)
    reactionsState.keys.retainAll(computed.keys)
    reactionsState.putAll(computed)
}

internal fun ConversationController.recomputeReactions(targetMessageIds: Set<String>) {
    if (targetMessageIds.isEmpty()) return
    targetMessageIds.forEach { target ->
        val tallies = reactionTalliesFor(target)
        if (tallies.isEmpty()) {
            reactionsState.remove(target)
        } else {
            reactionsState[target] = tallies
        }
    }
}

/** Chips for one message: window references when retained, otherwise confirmed senders plus optimistic changes. */
private fun ConversationController.reactionTalliesFor(targetMessageId: String): List<ReactionTally> {
    val changes = optimisticReactionChanges.values.filter { it.targetMessageId == targetMessageId }
    window.references(targetMessageId)?.let { return windowReactionTallies(it.reactions, changes) }
    val confirmed = linkedMapOf<String, MutableSet<String>>()
    timelineRecords[targetMessageId]?.reactions?.byEmoji.orEmpty().forEach { summary ->
        confirmed.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders)
    }
    return reactionTalliesForSenders(
        activeAccountIdHex = conversationAccountIdHex,
        confirmedSendersByEmoji = confirmed,
        optimisticChanges = optimisticReactionChanges.values.filter { it.targetMessageId == targetMessageId },
    )
}

internal fun ConversationController.baseReactionSenders(): LinkedHashMap<String, LinkedHashMap<String, MutableSet<String>>> {
    val result = linkedMapOf<String, LinkedHashMap<String, MutableSet<String>>>()
    timelineRecords.values.forEach { record ->
        val byEmoji = result.getOrPut(record.messageIdHex) { linkedMapOf() }
        record.reactions.byEmoji.forEach { summary ->
            // Lowercased so the optimistic add/remove and the `mine` check in
            // recomputeReactions() match regardless of casing drift. See #143.
            byEmoji.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders.map { it.lowercase() })
        }
    }
    return result
}
