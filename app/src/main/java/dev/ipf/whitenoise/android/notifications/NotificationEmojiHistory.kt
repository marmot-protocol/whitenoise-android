package dev.ipf.whitenoise.android.notifications

import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.util.UUID

private const val EXTRA_EMOJI_MESSAGE_PAIR = "dev.ipf.whitenoise.notification.emoji_pair"

/** A platform image row and its accessible text are one logical message, never two unread messages. */
internal fun notificationEmojiMessages(
    text: String,
    timestamp: Long,
    sender: Person,
    artwork: Uri?,
    artworkDescription: String = "",
): List<NotificationCompat.MessagingStyle.Message> {
    val textMessage = NotificationCompat.MessagingStyle.Message(text, timestamp, sender)
    if (artwork == null) return listOf(textMessage)
    val pair = UUID.randomUUID().toString()
    val imageMessage =
        NotificationCompat.MessagingStyle
            .Message(
                artworkDescription,
                timestamp,
                sender,
            ).setData("image/png", artwork)
    imageMessage.extras.putString(EXTRA_EMOJI_MESSAGE_PAIR, pair)
    textMessage.extras.putString(EXTRA_EMOJI_MESSAGE_PAIR, pair)
    return listOf(imageMessage, textMessage)
}

/** Retain complete presentation pairs while leaving legacy text/image messages independently counted. */
internal fun notificationLogicalMessageGroups(
    messages: List<NotificationCompat.MessagingStyle.Message>,
): List<List<NotificationCompat.MessagingStyle.Message>> {
    val groups = mutableListOf<List<NotificationCompat.MessagingStyle.Message>>()
    var index = 0
    while (index < messages.size) {
        val first = messages[index]
        val second = messages.getOrNull(index + 1)
        val pair = first.extras.getString(EXTRA_EMOJI_MESSAGE_PAIR)
        val imageThenText = first.dataUri != null && second != null && second.dataUri == null
        val samePair = pair != null && pair == second?.extras?.getString(EXTRA_EMOJI_MESSAGE_PAIR)
        val sameSenderAndTime = first.timestamp == second?.timestamp && first.person?.key == second?.person?.key
        val matches = imageThenText && samePair && sameSenderAndTime
        groups += if (matches) listOf(first, requireNotNull(second)) else listOf(first)
        index += if (matches) 2 else 1
    }
    return groups
}

/** Stay within Android's 25-row transport cap without retaining half of an image/text pair. */
internal fun capNotificationLogicalHistory(
    messages: List<NotificationCompat.MessagingStyle.Message>,
    logicalCap: Int,
    rowCap: Int = MAX_NOTIFICATION_MESSAGE_HISTORY,
): List<NotificationCompat.MessagingStyle.Message> {
    val groups = notificationLogicalMessageGroups(messages).takeLast(logicalCap.coerceAtLeast(0))
    val retained = mutableListOf<List<NotificationCompat.MessagingStyle.Message>>()
    var rows = 0
    for (group in groups.asReversed()) {
        if (rows + group.size > rowCap.coerceAtLeast(0)) break
        retained.add(0, group)
        rows += group.size
    }
    return retained.flatten()
}

/** A silent replacement removes both image and text, not just the last platform row. */
internal fun dropLastNotificationLogicalMessage(
    messages: List<NotificationCompat.MessagingStyle.Message>,
): List<NotificationCompat.MessagingStyle.Message> =
    notificationLogicalMessageGroups(messages).dropLast(1).flatten()
