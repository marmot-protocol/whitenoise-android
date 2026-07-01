package dev.ipf.whitenoise.android.notifications

import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

internal const val MAX_NOTIFICATION_MESSAGE_HISTORY = 25
internal const val CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP = MAX_NOTIFICATION_MESSAGE_HISTORY - 1

internal data class NotificationPostDecision(
    val channelId: String,
    val importance: ChannelImportance,
    val category: String,
    val style: NotificationStyleChoice,
    val actions: List<NotificationActionKind>,
    val historyCap: Int,
    val replaceExistingBeforePost: Boolean,
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
            spec == NotificationChannelSpec.REACTIONS -> NotificationStyleChoice.Plain
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
                NotificationStyleChoice.Messaging -> listOf(NotificationActionKind.REPLY, NotificationActionKind.MARK_READ)
                else -> emptyList()
            },
        historyCap =
            when (style) {
                NotificationStyleChoice.Messaging -> CARRIED_NOTIFICATION_MESSAGE_HISTORY_CAP
                else -> 0
            },
        replaceExistingBeforePost = style == NotificationStyleChoice.Messaging,
    )
}

internal fun <T> capNotificationHistory(
    history: List<T>,
    historyCap: Int,
): List<T> = history.takeLast(historyCap.coerceAtLeast(0))

internal fun shouldDismissInvite(
    extraAccountRef: String?,
    extraGroupIdHex: String?,
    accountRef: String,
    groupIdHex: String,
): Boolean {
    if (accountRef.isBlank() || groupIdHex.isBlank()) return false
    return extraAccountRef == accountRef && extraGroupIdHex == groupIdHex
}

private fun categoryFor(trigger: NotificationTriggerFfi): String =
    when (trigger) {
        NotificationTriggerFfi.NEW_MESSAGE -> NotificationCompat.CATEGORY_MESSAGE
        NotificationTriggerFfi.GROUP_INVITE -> NotificationCompat.CATEGORY_EVENT
    }
