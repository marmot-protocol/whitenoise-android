package dev.ipf.whitenoise.android.notifications

import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/** Copies a MessagingStyle while replacing every line whose stable sender key matches. */
internal fun copiedMessagingStyle(
    existing: NotificationCompat.MessagingStyle,
    replacement: Person,
): NotificationCompat.MessagingStyle =
    NotificationCompat.MessagingStyle(existing.user).also { copy ->
        copy.isGroupConversation = existing.isGroupConversation
        existing.conversationTitle?.let { copy.conversationTitle = it }
        existing.messages.forEach { message ->
            copy.addMessage(
                copiedMessage(
                    message,
                    replacement.takeIf { message.person?.key == it.key },
                ),
            )
        }
        existing.historicMessages.forEach { message ->
            copy.addHistoricMessage(
                copiedMessage(
                    message,
                    replacement.takeIf { message.person?.key == it.key },
                ),
            )
        }
    }

/** Copies one message without dropping attachment metadata. */
internal fun copiedMessage(
    message: NotificationCompat.MessagingStyle.Message,
    replacement: Person?,
    boundedText: Boolean = false,
): NotificationCompat.MessagingStyle.Message =
    NotificationCompat.MessagingStyle
        .Message(
            if (boundedText) boundedNotificationMessageText(message.text ?: "") else message.text,
            message.timestamp,
            replacement ?: message.person,
        ).also { copy ->
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
