package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiShortcodes

/** Rebuild after final privacy/contact reconciliation, including every rename, prune and recovery post. */
internal fun withExpandedReactionChoices(
    context: Context,
    notification: Notification,
    tag: String,
    id: Int,
    storedChoices: List<String> = notificationQuickReactionChoices(context),
): Notification {
    val clean = clearExpandedReactionChoices(context, notification)
    val choices = notificationQuickReactionChoices(storedChoices)
    val content = expandedReactionContent(context, clean)
    val hasArtwork = choices.any { EmojiShortcodes.builtinDrawable(it) != null }
    return expandedReactionTarget(clean, tag, id)?.takeIf { content != null && hasArtwork }?.let { target ->
        try {
            val view = expandedReactionView(context, clean, checkNotNull(content), target, choices)
            NotificationCompat
                .Builder(context, clean)
                .setCustomBigContentView(view)
                .addExtras(Bundle().apply { putBoolean(EXTRA_EXPANDED_REACTION_CHOICES, true) })
                .build()
        } catch (
            @Suppress("TooGenericExceptionCaught")
            _: RuntimeException,
        ) {
            clean
        }
    } ?: clean
}

/** Existing bounded history and URI leases remain authoritative; no new artwork is resolved here. */
private fun expandedReactionContent(
    context: Context,
    notification: Notification,
): ExpandedReactionContent? {
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    val messages = style?.let { it.historicMessages + it.messages }.orEmpty()
    val supported =
        messages.all { message ->
            message.dataUri?.let { uri ->
                message.dataMimeType == "image/png" && ownedNotificationEmojiUri(context, uri)
            } != false
        }
    val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
    return if (supported && (messages.isNotEmpty() || body != null)) {
        ExpandedReactionContent(
            style?.conversationTitle ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            body,
            messages,
            notification.extras
                .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                .orEmpty()
                .toList(),
        )
    } else {
        null
    }
}

/** Only the same app-owned immutable exports accepted by the existing history lease are rendered. */
private fun ownedNotificationEmojiUri(
    context: Context,
    uri: Uri,
): Boolean {
    val provider = uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider"
    val path = uri.pathSegments
    val ownedPath = path.size == 2 && path.first() == "notification_emoji"
    return provider && ownedPath && NOTIFICATION_CHOICE_ARTWORK_NAME.matches(path.last())
}

private data class ExpandedReactionContent(
    val title: CharSequence?,
    val body: CharSequence?,
    val messages: List<NotificationCompat.MessagingStyle.Message>,
    val replies: List<CharSequence>,
)

/** Recovered cards must not keep a body or target rendered before a mutation or privacy transition. */
internal fun clearExpandedReactionChoices(
    context: Context,
    notification: Notification,
): Notification {
    if (!notification.extras.getBoolean(EXTRA_EXPANDED_REACTION_CHOICES)) return notification
    return NotificationCompat
        .Builder(context, notification)
        .setCustomBigContentView(null)
        .apply { extras.remove(EXTRA_EXPANDED_REACTION_CHOICES) }
        .build()
}

private fun expandedReactionTarget(
    notification: Notification,
    tag: String,
    id: Int,
): NotificationActionTarget? {
    val extras = notification.extras
    val visible =
        !extras.getBoolean(EXTRA_CONTENT_REDACTED) && !extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN)
    val hasReply =
        notification.actions.orEmpty().any {
            it.semanticAction ==
                NotificationCompat.Action.SEMANTIC_ACTION_REPLY
        }
    val action =
        extras.getBundle(EXTRA_FIXED_REACTION_TARGET)?.let { target ->
            NotificationActions.parse(Intent(NotificationActions.ACTION_REACT).putExtras(target))
        }
    val messageId = extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX)
    val sameCard = action?.let { it.notificationTag == tag && it.notificationId == id }
    val sameMessage = action?.target?.messageIdHex == messageId
    val allowed = visible && hasReply
    return action
        ?.takeIf { allowed && sameCard == true && sameMessage }
        ?.let { NotificationActionTarget(it.target, tag, id) }
}

/** The native style and message extras stay intact; only the expanded content view is supplied. */
private fun expandedReactionView(
    context: Context,
    notification: Notification,
    content: ExpandedReactionContent,
    target: NotificationActionTarget,
    choices: List<String>,
): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_reaction_choices).apply {
        setTextViewText(R.id.reaction_choices_title, content.title)
        addExpandedReactionMessages(context, this, content)
        val identity =
            notification.getLargeIcon()
                ?: NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification)
                    ?.messages
                    ?.lastOrNull()
                    ?.person
                    ?.icon
                    ?.toIcon(context)
        identity?.let { icon -> setImageViewIcon(R.id.reaction_choices_avatar, icon) }
            ?: setViewVisibility(R.id.reaction_choices_avatar, View.GONE)
        choices.forEachIndexed { index, choice ->
            val pending =
                fixedNotificationReactionPendingIntent(context, target, choice, choices) ?: return@forEachIndexed
            val item = RemoteViews(context.packageName, R.layout.notification_reaction_choice)
            val drawable = EmojiShortcodes.builtinDrawable(choice)
            if (drawable == null) {
                item.setTextViewText(R.id.reaction_choice_text, choice)
                item.setViewVisibility(R.id.reaction_choice_image, View.GONE)
            } else {
                item.setImageViewResource(R.id.reaction_choice_image, drawable)
                item.setViewVisibility(R.id.reaction_choice_text, View.GONE)
            }
            item.setContentDescription(R.id.reaction_choice, "${context.getString(R.string.message_react)} $choice")
            item.setOnClickPendingIntent(R.id.reaction_choice, pending)
            addView(if (index < CHOICES_PER_ROW) R.id.reaction_choices_first else R.id.reaction_choices_second, item)
        }
        if (choices.size <= CHOICES_PER_ROW) setViewVisibility(R.id.reaction_choices_second, View.GONE)
    }

private fun addExpandedReactionMessages(
    context: Context,
    view: RemoteViews,
    content: ExpandedReactionContent,
) {
    with(view) {
        if (content.messages.isEmpty()) {
            setTextViewText(R.id.reaction_choices_body, content.body)
        } else {
            setViewVisibility(R.id.reaction_choices_body, View.GONE)
            notificationLogicalMessageGroups(content.messages).forEach { group ->
                val message = group.lastOrNull { it.dataUri == null } ?: group.first()
                val row = RemoteViews(context.packageName, R.layout.notification_reaction_message)
                val sender = message.person?.name.orEmpty()
                row.setTextViewText(R.id.reaction_message_body, "$sender: ${message.text.orEmpty()}")
                group.firstNotNullOfOrNull { it.dataUri }?.let { uri ->
                    row.setImageViewUri(R.id.reaction_message_artwork, uri)
                } ?: row.setViewVisibility(R.id.reaction_message_artwork, View.GONE)
                addView(R.id.reaction_choices_messages, row)
            }
        }
        content.replies.forEach { reply ->
            val row = RemoteViews(context.packageName, R.layout.notification_reaction_message)
            row.setTextViewText(R.id.reaction_message_body, reply)
            row.setViewVisibility(R.id.reaction_message_artwork, View.GONE)
            addView(R.id.reaction_choices_messages, row)
        }
    }
}

internal const val EXTRA_EXPANDED_REACTION_CHOICES = "dev.ipf.whitenoise.android.notification.EXPANDED_REACTION_CHOICES"
private const val CHOICES_PER_ROW = 3

private val NOTIFICATION_CHOICE_ARTWORK_NAME = Regex("[0-9a-f-]{36}\\.png")
