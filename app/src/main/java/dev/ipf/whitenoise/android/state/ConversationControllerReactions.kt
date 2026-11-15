package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.BuildConfig
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

/** Commits an add and retains its exact event id for an immediate queued removal. */
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
        val summary =
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
        val messageIdHex = summary.messageIds.firstOrNull()?.takeIf(String::isNotBlank)
        // A committed add remains present while older bindings/projections have no exact event id yet.
        unprojectedOwnReactionEventIds[key] = messageIdHex.orEmpty()
    } finally {
        appState.untrackInFlightMediaUpload(account, group.groupIdHex, uploadKey, uploadJob)
    }
}

/** Commits a removal, using history when a just-added reaction did not return an event id. */
private suspend fun ConversationController.commitReactionRemoval(
    owner: ReactionMutationTarget,
    emoji: String,
    ownEmojisBeforeMutation: Set<String>,
    removeBeforeProjection: Boolean,
) {
    val account = owner.account
    val target = owner.messageId
    val key = target.lowercase() to emoji
    val preferredEventId =
        if (removeBeforeProjection) {
            unprojectedOwnReactionEventIds[key]
        } else {
            null
        }
    if (removeBeforeProjection && preferredEventId.isNullOrBlank()) {
        // This is only a visibility barrier. retractOwnReaction deliberately rereads history
        // under the group commit lock before choosing event-scoped delete versus unreact.
        awaitReactionEventHistory(emoji) {
            resolveActiveOwnReactionEventIds(account, target, requireNotNull(conversationAccountIdHex))
        }
    }
    appState.withGroupCommitLock(account, group.groupIdHex) {
        retractOwnReaction(owner, emoji, ownEmojisBeforeMutation, preferredEventId)
    }
}

/** Resolves active own reaction ids from Marmot's authoritative local event history. */
private suspend fun ConversationController.resolveActiveOwnReactionEventIds(
    account: String,
    target: String,
    activeAccountIdHex: String,
): Map<String, String>? =
    runCatchingCancellable {
        appState.marmotIo {
            messages(
                account,
                group.groupIdHex,
                null,
                listOf(MARMOT_DELETE_EVENT_KIND, MARMOT_REACTION_EVENT_KIND),
            )
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMConversation", "reaction event lookup failed", it)
    }.getOrNull()
        ?.let { records -> activeOwnReactionEventIdsByEmoji(records, activeAccountIdHex, target) }

/** Removes the tapped own reaction without clearing a different emoji when several are active. */
private suspend fun ConversationController.retractOwnReaction(
    owner: ReactionMutationTarget,
    emoji: String,
    ownEmojisBeforeMutation: Set<String>,
    preferredEventId: String? = null,
) {
    val account = owner.account
    val target = owner.messageId
    // Retract just the tapped emoji by deleting its own reaction event; the
    // FFI target-only unreact would drop the wrong emoji when the user holds
    // more than one reaction on the same message.
    val me = conversationAccountIdHex ?: error("no active account to retract reaction")
    val projectedEventIdByEmoji = projectedOwnReactionEventIds(timelineRecords[target], me)
    val preferredReactionEventId = preferredEventId?.takeIf(String::isNotBlank)
    val requiresAuthoritativeHistory =
        preferredReactionEventId == null &&
            (ownEmojisBeforeMutation == setOf(emoji) || projectedEventIdByEmoji[emoji].isNullOrBlank())
    val authoritativeEventIds =
        if (requiresAuthoritativeHistory) {
            resolveActiveOwnReactionEventIds(account, target, me)
        } else {
            null
        }
    val knownEventIdByEmoji =
        knownReactionEventIds(
            projectedEventIds = projectedEventIdByEmoji,
            emoji = emoji,
            unprojectedEventId = unprojectedOwnReactionEventIds[target.lowercase() to emoji],
            authoritativeEventIds = authoritativeEventIds,
        )
    val authoritativeOwnEmojis =
        when {
            authoritativeEventIds != null -> authoritativeEventIds.keys
            ownEmojisBeforeMutation.size > 1 -> ownEmojisBeforeMutation
            else -> null
        }
    val plan =
        planOwnReactionRetraction(
            emoji = emoji,
            knownEventIdByEmoji = knownEventIdByEmoji,
            authoritativeOwnEmojis = authoritativeOwnEmojis,
            preferredEventId = preferredReactionEventId,
        )
    // History reads and lock admission can suspend after the reaction's quiet period.
    owner.requireAvailable()
    when (plan) {
        is OwnReactionRetractionPlan.DeleteReactionMessage -> {
            appState.marmotIo { deleteMessage(account, group.groupIdHex, plan.messageIdHex) }
            unprojectedOwnReactionEventIds.remove(target.lowercase() to emoji)
        }
        OwnReactionRetractionPlan.UnreactTarget -> {
            appState.marmotIo { unreactFromMessage(account, group.groupIdHex, target) }
            unprojectedOwnReactionEventIds.keys.removeAll { (messageId, _) ->
                messageId.equals(target, ignoreCase = true)
            }
        }
        OwnReactionRetractionPlan.Unavailable -> error("no reaction event to retract for $emoji")
    }
}

/** Own reactions known by projection plus locally committed additions awaiting projection. */
private fun ConversationController.authoritativeOwnReactionEmojis(target: String): Set<String> =
    conversationAccountIdHex?.let { me ->
        projectedOwnReactionEmojis(window.references(target), timelineRecords[target], me)
            .includeUnprojectedReactionEmojis(target, unprojectedOwnReactionEventIds.keys)
    } ?: emptySet()

/** Drives native state toward the newest intent without blocking later optimistic taps. */
internal suspend fun ConversationController.convergeReactionIntent(
    owner: ReactionMutationTarget,
    emoji: String,
    key: Pair<String, String>,
): ReactionIntentDrainOutcome {
    val target = owner.messageId
    val ownEmojis = authoritativeOwnReactionEmojis(target).toMutableSet()
    var addedBeforeProjection = key in unprojectedOwnReactionEventIds
    return drainReactionIntent(
        key = key,
        conflator = reactionIntentConflator,
        initialMine = emoji in ownEmojis,
    ) { commitMine ->
        retryBusyReactionMutation(
            stillDesired = { reactionIntentConflator.latest(key)?.desiredMine == commitMine },
        ) {
            if (commitMine) {
                commitReactionAdd(owner, emoji)
                ownEmojis += emoji
                addedBeforeProjection = true
            } else {
                commitReactionRemoval(
                    owner = owner,
                    emoji = emoji,
                    ownEmojisBeforeMutation = ownEmojis.toSet(),
                    removeBeforeProjection = addedBeforeProjection,
                )
                ownEmojis -= emoji
                addedBeforeProjection = false
            }
        }
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
