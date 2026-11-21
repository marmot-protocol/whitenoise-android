package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineUserReactionFfi

/** Reads the complete local reaction set for one message while it belongs to this conversation. */
internal suspend fun <T> ConversationController.readReactionRecords(
    target: String,
    project: (List<TimelineUserReactionFfi>) -> T,
): T {
    val account = requireNotNull(conversationAccountRef)
    check(acceptsConversationActionOwner(account, group.groupIdHex))
    val rows = appState.marmotIo { project(messageReactions(account, group.groupIdHex, target)) }
    check(acceptsConversationActionOwner(account, group.groupIdHex))
    return rows
}

/** Only the tapped message and emoji wait for an omitted-kind lookup. Later taps invert the latest submitted intent. */
internal suspend fun ConversationController.admitReactionTap(
    owner: ReactionMutationTarget,
    emoji: String,
    onLocalIntent: (Boolean) -> Unit,
): Boolean? {
    val target = owner.messageId
    val key = target.lowercase() to emoji
    val overlay = reactionIntentOverlayId(target, emoji)
    return reactionTapAdmissions.withLock(overlay) {
        if (!ownsRetainedForegroundPresentation) return@withLock null
        val prior = reactionIntentConflator.latest(key)?.desiredMine
        val base =
            runCatchingCancellable { resolveReactionTapBase(owner, emoji, prior) }.getOrElse {
                if (ownsRetainedForegroundPresentation) appState.presentReactionMutationFailure(it)
                return@withLock null
            }
        val desired = !base
        optimisticReactionChanges[overlay] = OptimisticReactionChange(target, emoji, add = desired)
        recomputeReactions(setOf(target))
        val submitted = reactionIntentConflator.submit(key, desired)
        onLocalIntent(desired)
        base.takeIf { submitted.shouldDrain }
    }
}

/** Checks effective local ownership; reaction detail event IDs only represent a sender/emoji pair. */
internal suspend fun ConversationController.readOwnReaction(
    target: String,
    emoji: String,
): Boolean {
    val me = requireNotNull(conversationAccountIdHex)
    return readReactionRecords(target) { rows ->
        rows.any { it.emoji == emoji && it.sender.equals(me, ignoreCase = true) }
    }
}

/** A clipped summary has no answer for an omitted emoji; a newer reference can supersede its exact read. */
private suspend fun ConversationController.resolveReactionTapBase(
    owner: ReactionMutationTarget,
    emoji: String,
    prior: Boolean?,
): Boolean {
    if (prior != null) return prior
    val target = owner.messageId
    val key = target.lowercase() to emoji
    val summary = window.references(target)?.reactions
    val viewerReacted = summary?.viewerReactedTo(emoji)
    return if (summary != null && viewerReacted == null) {
        val mine = readOwnReaction(target, emoji)
        check(ownsRetainedForegroundPresentation)
        owner.requireAvailable()
        unprojectedOwnReactions.remove(key)
        window.references(target)?.reactions?.viewerReactedTo(emoji) ?: mine
    } else {
        val overlay = optimisticReactionChanges[reactionIntentOverlayId(target, emoji)]
        val projected =
            viewerReacted ?: timelineRecords[target]?.reactions?.userReactions.orEmpty().any {
                it.emoji == emoji && it.sender.equals(conversationAccountIdHex, ignoreCase = true)
            }
        overlay?.add ?: (key in unprojectedOwnReactions || projected)
    }
}
