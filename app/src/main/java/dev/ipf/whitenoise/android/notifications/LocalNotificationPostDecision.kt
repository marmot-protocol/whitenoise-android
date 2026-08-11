package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

internal const val MAX_NOTIFICATION_MESSAGE_HISTORY = 25
internal const val CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP = MAX_NOTIFICATION_MESSAGE_HISTORY - 1
internal const val MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS = 1_000
internal const val MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS = 160
internal const val CONVERSATION_SHORTCUT_PREFIX = "conversation-"

internal data class NotificationPostDecision(
    val channelId: String,
    val importance: ChannelImportance,
    val category: String,
    val style: NotificationStyleChoice,
    val actions: List<NotificationActionKind>,
    val historyCap: Int,
)

internal sealed class NotificationStyleChoice {
    object Plain : NotificationStyleChoice()

    object Messaging : NotificationStyleChoice()

    data class InviteWithExtras(
        val accountRef: String,
        val groupIdHex: String,
    ) : NotificationStyleChoice()
}

internal fun decideNotificationPost(
    update: NotificationUpdateFfi,
    canPost: Boolean,
    formatterReturnedContent: Boolean,
    spec: NotificationChannelSpec = NotificationChannelSpec.forUpdate(update),
): NotificationPostDecision? {
    if (!formatterReturnedContent || !canPost) return null

    val style =
        when {
            spec == NotificationChannelSpec.REACTIONS ||
                spec == NotificationChannelSpec.AGENT_ACTIVITY -> NotificationStyleChoice.Plain
            update.trigger == NotificationTriggerFfi.NEW_MESSAGE -> NotificationStyleChoice.Messaging
            update.trigger == NotificationTriggerFfi.GROUP_INVITE && update.groupIdHex.isNotBlank() ->
                NotificationStyleChoice.InviteWithExtras(update.accountRef, update.groupIdHex)
            else -> NotificationStyleChoice.Plain
        }

    return NotificationPostDecision(
        channelId = spec.id,
        importance = spec.importance,
        category = categoryFor(update.trigger),
        style = style,
        actions =
            when (style) {
                NotificationStyleChoice.Messaging -> listOf(NotificationActionKind.REPLY, NotificationActionKind.REACT)
                else -> emptyList()
            },
        historyCap =
            when (style) {
                NotificationStyleChoice.Messaging -> CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP
                else -> 0
            },
    )
}

internal fun <T> capNotificationHistory(
    history: List<T>,
    historyCap: Int,
): List<T> = history.takeLast(historyCap.coerceAtLeast(0))

internal fun boundedNotificationMessageText(text: CharSequence): String {
    val value = text.toString()
    if (value.codePointCount(0, value.length) <= MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS) return value
    val endIndex = value.offsetByCodePoints(0, MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS)
    return value.substring(0, endIndex)
}

internal fun shouldUseExpandedSingleMessageStyle(
    body: CharSequence,
    carriedMessageCount: Int,
    redactContent: Boolean,
): Boolean =
    !redactContent &&
        carriedMessageCount == 0 &&
        body.codePointCount() >= MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS

private fun CharSequence.codePointCount(): Int {
    val value = toString()
    return value.codePointCount(0, value.length)
}

internal fun conversationShortcutRemovalOrder(
    existingShortcutIds: Set<String>,
    lastUsed: Map<String, Long>,
    protectedShortcutId: String,
): List<String> =
    existingShortcutIds
        .filterNot { it == protectedShortcutId }
        .sortedWith(
            compareBy<String> { lastUsed[it] ?: Long.MIN_VALUE }
                .thenBy { it },
        )

internal fun shouldDismissInvite(
    extraAccountRef: String?,
    extraGroupIdHex: String?,
    accountRef: String,
    groupIdHex: String,
): Boolean {
    if (accountRef.isBlank() || groupIdHex.isBlank()) return false
    return extraAccountRef == accountRef && extraGroupIdHex == groupIdHex
}

internal fun conversationCardMessageIdHex(notification: Notification?): String? =
    notification
        ?.extras
        ?.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX)
        ?.takeIf { it.isNotBlank() }

// Cancel the replied card only when its stamped latest-message id still matches
// the replied action target. Fail closed to data loss when either id is absent.
internal fun shouldCancelRepliedConversationCard(
    repliedMessageIdHex: String?,
    liveCardMessageIdHex: String?,
): Boolean {
    val replied = repliedMessageIdHex?.takeIf { it.isNotBlank() } ?: return false
    val live = liveCardMessageIdHex?.takeIf { it.isNotBlank() } ?: return false
    return replied == live
}

private fun categoryFor(trigger: NotificationTriggerFfi): String =
    when (trigger) {
        NotificationTriggerFfi.NEW_MESSAGE -> NotificationCompat.CATEGORY_MESSAGE
        NotificationTriggerFfi.GROUP_INVITE -> NotificationCompat.CATEGORY_EVENT
    }
