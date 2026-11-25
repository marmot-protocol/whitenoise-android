package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageProjector

private const val EVENT_ID_HEX_LENGTH = 64
private const val LOCAL_SEND_KEY_PREFIX = "msg:"

/**
 * The reply selection a poll or sticker send captured when the user submitted it. [selectionVersion]
 * lets a finished send clear only that selection, never a reply the user picked while it was running.
 */
internal data class ReplySnapshot(
    val targetMessageIdHex: String?,
    val selectionVersion: Long,
) {
    /** True when a reply is selected but its message has no event id to reference yet. */
    val unpublishable: Boolean
        get() = targetMessageIdHex != null && !isPublishableReplyTarget(targetMessageIdHex)
}

/** True for the lowercase 32-byte hex event id MDK projects; a local send's client token never qualifies. */
internal fun isPublishableReplyTarget(messageIdHex: String?): Boolean =
    messageIdHex != null &&
        messageIdHex.length == EVENT_ID_HEX_LENGTH &&
        messageIdHex.all { it in '0'..'9' || it in 'a'..'f' }

/** Builds the `e` and `q` pair MDK writes for a text reply, so a poll or sticker reply projects the same way. */
internal fun replyMessageTags(targetMessageIdHex: String): List<List<String>> {
    require(isPublishableReplyTarget(targetMessageIdHex)) { "reply target has no event id yet" }
    return listOf(
        listOf(MessageProjector.EventRefTag, targetMessageIdHex),
        listOf(MessageProjector.QuoteRefTag, targetMessageIdHex),
    )
}

/** Captures the composer's current reply selection for a poll or sticker send. */
internal fun ConversationController.snapshotReply(): ReplySnapshot =
    ReplySnapshot(
        targetMessageIdHex = replyingTo?.messageIdHex?.takeIf(String::isNotBlank)?.let { confirmedReplyTarget(it) },
        selectionVersion = replySelectionVersion,
    )

/**
 * A reply picked on the user's own message while it was still sending keeps that send's client token. Once
 * the accepted row is in the timeline, under the same row identity or carrying the token, its event id is
 * the target. A text MDK accepted before its row was projected keeps the token on that row, so its event id
 * comes from the acceptance map instead. Until then the token is returned unchanged and stays unpublishable.
 * Only the loaded window is searched, so if the accepted row has paged out the actions stay hidden; the token
 * is never published. A message action queued on a bubble whose send has since confirmed resolves its target the
 * same way.
 */
internal fun ConversationController.confirmedReplyTarget(selected: String): String {
    if (isPublishableReplyTarget(selected)) return selected
    val localSendKey = LOCAL_SEND_KEY_PREFIX + selected
    return timeline
        .firstOrNull { it.presentationId == localSendKey || it.projected?.clientToken == selected }
        ?.record
        ?.messageIdHex
        ?.takeIf(::isPublishableReplyTarget)
        ?: acceptedPendingTextOptimisticIds.entries
            .firstOrNull { it.value == selected }
            ?.key
            ?.takeIf(::isPublishableReplyTarget)
        ?: selected
}

/**
 * The reply target a text or media send publishes: none stays none, an event id is kept, and the client token
 * of the user's own send resolves to that send's event id. While it has none, the user is told to wait for that
 * message or retry it, and the result is a failure, so the caller sends nothing and the composer keeps its text,
 * media and reply.
 */
internal fun ConversationController.replyTargetForSend(selected: String?): Result<String?> {
    val target = selected?.takeIf(String::isNotBlank)?.let { confirmedReplyTarget(it) }
    if (target == null || isPublishableReplyTarget(target)) return Result.success(target)
    appState.present(R.string.reply_target_not_sent)
    return Result.failure(IllegalStateException("reply target has no event id yet"))
}

/**
 * The reply tags a poll or sticker send carries for [reply]: none without a reply, or the `e` and `q` pair for the
 * event id [replyTargetForSend] resolves. While its message has none, the result is a failure after that notice, so
 * the send publishes nothing and the reply stays selected.
 */
internal fun ConversationController.replyTagsForSend(reply: ReplySnapshot): Result<List<List<String>>> =
    replyTargetForSend(reply.targetMessageIdHex).map { target -> target?.let(::replyMessageTags).orEmpty() }

/** True when the composer's reply, if any, can be carried by a poll or sticker. */
internal fun ConversationController.replyAllowsTaggedSend(): Boolean = !snapshotReply().unpublishable

/** Clears the reply a send carried once MDK accepted it, unless the user picked another one since. */
internal fun ConversationController.clearSentReply(reply: ReplySnapshot) {
    if (reply.targetMessageIdHex != null && replySelectionVersion == reply.selectionVersion) replyingTo = null
}
