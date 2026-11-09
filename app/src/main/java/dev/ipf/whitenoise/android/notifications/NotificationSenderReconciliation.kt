package dev.ipf.whitenoise.android.notifications

import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * Copies a MessagingStyle while replacing every line whose stable sender key matches. With [repaint], each replaced
 * line also moves one millisecond later (#3635): SystemUI keeps a line whose text, sender name and time are
 * unchanged, together with the sender picture it first drew, so only a moved line shows a new picture. The lines of
 * an image and text pair move together, and the shift stays far below the minute a card shows.
 */
internal fun copiedMessagingStyle(
    existing: NotificationCompat.MessagingStyle,
    replacement: Person,
    repaint: Boolean = false,
): NotificationCompat.MessagingStyle =
    NotificationCompat.MessagingStyle(existing.user).also { copy ->
        copy.isGroupConversation = existing.isGroupConversation
        existing.conversationTitle?.let { copy.conversationTitle = it }
        val replaced = { message: NotificationCompat.MessagingStyle.Message ->
            val person = replacement.takeIf { message.person?.key == it.key }
            copiedMessage(message, person, timeShiftMs = if (repaint && person != null) REPAINT_SHIFT_MS else 0L)
        }
        existing.messages.forEach { copy.addMessage(replaced(it)) }
        existing.historicMessages.forEach { copy.addHistoricMessage(replaced(it)) }
    }

/** Copies one message without dropping attachment metadata, [timeShiftMs] later than the original. */
internal fun copiedMessage(
    message: NotificationCompat.MessagingStyle.Message,
    replacement: Person?,
    boundedText: Boolean = false,
    timeShiftMs: Long = 0L,
): NotificationCompat.MessagingStyle.Message =
    NotificationCompat.MessagingStyle
        .Message(
            if (boundedText) boundedNotificationMessageText(message.text ?: "") else message.text,
            message.timestamp + timeShiftMs,
            replacement ?: message.person,
        ).also { copy ->
            copy.extras.putAll(message.extras)
            val mimeType = message.dataMimeType
            val dataUri = message.dataUri
            if (mimeType != null && dataUri != null) copy.setData(mimeType, dataUri)
        }

/** Changes only a Person's visible name while retaining its stable identity and presentation metadata. */
internal fun renamedPerson(
    person: Person,
    name: String,
): Person =
    Person
        .Builder()
        .setName(name)
        .setKey(person.key)
        .setUri(person.uri)
        .setBot(person.isBot)
        .setImportant(person.isImportant)
        .apply { person.icon?.let(::setIcon) }
        .build()

/** Replaces only contact pixels, preserving routing identity and every non-image Person attribute. */
internal fun contactAvatarPerson(
    person: Person,
    bitmap: android.graphics.Bitmap?,
): Person =
    Person
        .Builder()
        .setName(person.name)
        .setKey(person.key)
        .setUri(person.uri)
        .setBot(person.isBot)
        .setImportant(person.isImportant)
        .setIcon(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(
                bitmap ?: notificationMonogramBitmap(person.name?.toString().orEmpty(), person.key.orEmpty()),
            ),
        ).build()

/** How far a repainted line moves so SystemUI draws it again (#3635); far below the minute a card shows. */
private const val REPAINT_SHIFT_MS = 1L
