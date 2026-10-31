package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.ipf.whitenoise.android.R

internal const val EXTRA_EXPANDED_SINGLE_MESSAGE_BODY =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_BODY"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_TIMESTAMP"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_SENDER"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_GROUP_TITLE =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_GROUP_TITLE"
internal const val CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA =
    "dev.ipf.whitenoise.android.notify.contact_picture_account_scope"

/** Builds and copies notification payloads; platform ownership and write eligibility stay with the presenter. */
internal class NotificationCardContent(
    private val context: Context,
    private val contactAvatarOverride: (String, String) -> Pair<Boolean, Bitmap?>,
) {
    /** The live card's own Person for [senderKey], from the conversation template or a pre-upgrade text block. */
    fun liveSenderPerson(
        card: Notification,
        senderKey: String,
    ): Person? =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(card)
            ?.let { style -> style.messages + style.historicMessages }
            ?.firstNotNullOfOrNull { message -> message.person?.takeIf { it.key == senderKey } }
            ?: card.extras
                ?.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
                ?.let(Person::fromBundle)
                ?.takeIf { it.key == senderKey }

    /**
     * Rewrites the live card with the enriched sender and identity picture, for the conversation template and the
     * pre-upgrade long-message text block alike. It keeps the card's grouping eligible for heads-up while
     * suppressing a repeat alert, and leaves any other card shape untouched.
     */
    fun enrichedIdentityNotification(
        active: Notification,
        sender: Person,
        identity: NotificationIdentityImage,
    ): Notification? {
        val builder =
            NotificationCompat
                .Builder(context, active)
                .setGroupAlertBehavior(active.groupAlertBehavior)
                .setOnlyAlertOnce(true)
        val enrichedStyle = enrichedMessagingStyle(active, sender)
        when {
            enrichedStyle != null -> builder.setStyle(enrichedStyle)
            active.extras?.containsKey(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER) == true ->
                builder.addExtras(Bundle().apply { putBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER, sender.toBundle()) })
            else -> return null
        }
        return builder.setConversationIdentityImage(identity).build()
    }

    /** Copies an active MessagingStyle while replacing the matching sender's rich Person metadata. */
    private fun enrichedMessagingStyle(
        notification: Notification,
        enrichedSender: Person,
    ): NotificationCompat.MessagingStyle? {
        val existing =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?: return null
        return copiedMessagingStyle(existing, enrichedSender)
    }

    /** Rebuilds an active card only when a stable sender key can be relabeled safely. */
    fun renamedSenderNotification(
        notification: Notification,
        senderAccountIdHex: String,
        senderName: String,
    ): Notification? =
        if (notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) {
            null
        } else {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.let { renamedMessagingNotification(notification, it, senderAccountIdHex, senderName) }
                ?: renamedExpandedSingleNotification(notification, senderAccountIdHex, senderName)
        }

    /** Rebuilds MessagingStyle history only when at least one stable sender key matches. */
    private fun renamedMessagingNotification(
        notification: Notification,
        style: NotificationCompat.MessagingStyle,
        senderAccountIdHex: String,
        senderName: String,
    ): Notification? =
        (style.messages + style.historicMessages)
            .firstNotNullOfOrNull { message -> message.person?.takeIf { it.key == senderAccountIdHex } }
            ?.let { matchedPerson ->
                NotificationCompat
                    .Builder(context, notification)
                    .setStyle(copiedMessagingStyle(style, renamedPerson(matchedPerson, senderName)))
                    .setOnlyAlertOnce(true)
                    .build()
            }

    /** Rebuilds the single-message bridge while keeping a conversation title distinct from the sender. */
    private fun renamedExpandedSingleNotification(
        notification: Notification,
        senderAccountIdHex: String,
        senderName: String,
    ): Notification? =
        notification.extras
            ?.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
            ?.let(Person::fromBundle)
            ?.takeIf { it.key == senderAccountIdHex }
            ?.let { sender ->
                val builder = NotificationCompat.Builder(context, notification)
                val existingTitle = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val groupTitle =
                    notification.extras
                        ?.getCharSequence(EXTRA_EXPANDED_SINGLE_MESSAGE_GROUP_TITLE)
                        ?.toString()
                when {
                    existingTitle == sender.name.toString() -> builder.setContentTitle(senderName)
                    groupTitle != null &&
                        existingTitle ==
                        context.getString(R.string.notification_sender_in_group, sender.name, groupTitle) ->
                        builder.setContentTitle(
                            context.getString(R.string.notification_sender_in_group, senderName, groupTitle),
                        )
                }
                builder
                    .addExtras(
                        Bundle().apply {
                            putBundle(
                                EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER,
                                renamedPerson(sender, senderName).toBundle(),
                            )
                        },
                    ).setOnlyAlertOnce(true)
                    .build()
            }

    /** Refresh private Person pixels at the final write so a concurrent clear cannot revive an older snapshot. */
    @Suppress("ReturnCount") // Missing or redacted payloads must exit before reading private identity.
    private fun withCurrentContactAvatars(notification: Notification): Notification {
        if (notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) return notification
        val account =
            notification.extras?.getString(
                CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA,
            ) ?: return notification
        var style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            var changedAny = false
            // Group cards draw no member's picture (withoutGroupSenderPictures), so their senders' choices go unread.
            val people =
                if (style.isGroupConversation) {
                    emptyList()
                } else {
                    (style.messages + style.historicMessages).mapNotNull { it.person }.distinctBy { it.key }
                }
            people.forEach { person ->
                val (changed, bitmap) = person.key?.let { contactAvatarOverride(account, it) } ?: (false to null)
                if (changed) {
                    changedAny = true
                    style = copiedMessagingStyle(checkNotNull(style), contactAvatarPerson(person, bitmap))
                }
            }
            return if (changedAny) {
                NotificationCompat.Builder(context, notification).setStyle(style).build()
            } else {
                notification
            }
        }
        val sender = notification.extras?.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)?.let(Person::fromBundle)
        val key = sender?.key ?: return notification
        val (changed, bitmap) = contactAvatarOverride(account, key)
        if (!changed) return notification
        return NotificationCompat
            .Builder(context, notification)
            .addExtras(
                Bundle().apply {
                    putBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER, contactAvatarPerson(sender, bitmap).toBundle())
                },
            ).build()
    }

    /** Refreshes the sender Person and a direct chat's identity picture from the current private contact choice. */
    fun withCurrentContactPictures(notification: Notification): Notification {
        val people = withCurrentContactAvatars(notification)
        val account = people.extras?.getString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA)
        return if (account == null || people.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) {
            people
        } else {
            withCurrentIdentityContact(context, people) { contact -> contactAvatarOverride(account, contact) }
        }
    }

    /**
     * Accumulates one conversation under a stable card while normalizing carried lines for the current sender.
     * Attachment data, timestamps, other senders, and the bounded history order remain unchanged.
     */
    fun messagingStyle(
        content: LocalNotificationContent,
        conversationTitleOverride: String?,
        historyCap: Int,
        carriedHistory: List<NotificationCompat.MessagingStyle.Message>?,
        sender: Person,
        newMessageTimestampMs: Long,
        emojiArtwork: android.net.Uri? = null,
        messageIdHex: String? = null,
    ): NotificationCompat.MessagingStyle {
        val self =
            Person
                .Builder()
                .setName(content.selfName)
                .setKey(content.selfKey)
                .build()
        // Cap carried-forward history; otherwise every post serializes it unbounded across Binder.
        val style = NotificationCompat.MessagingStyle(self)
        val newMessages =
            notificationEmojiMessages(
                content.body,
                newMessageTimestampMs,
                sender,
                emojiArtwork,
                context.getString(R.string.custom_emoji),
            )
        messageIdHex?.takeIf(String::isNotBlank)?.let { id ->
            newMessages.forEach { it.extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, id) }
        }
        carriedHistory
            ?.let { capNotificationLogicalHistory(it, historyCap, MAX_NOTIFICATION_MESSAGE_HISTORY - newMessages.size) }
            ?.forEach { message ->
                style.addMessage(
                    copiedMessage(
                        message = message,
                        replacement = sender.takeIf { replacement -> message.person?.key == replacement.key },
                        boundedText = true,
                    ),
                )
            }
        style.isGroupConversation = content.isGroupConversation
        // Prefer the caller-resolved title (chat-list parity, e.g. "Group of N
        // people" for unnamed groups) over the often-empty payload group name.
        (conversationTitleOverride?.takeIf { it.isNotBlank() } ?: content.conversationTitle)?.let {
            style.conversationTitle = it
        }
        newMessages.forEach(style::addMessage)
        return style
    }

    // Read posted pre-upgrade BigTextStyle cards until their next message replaces them with MessagingStyle.
    fun expandedSingleMessage(extras: Bundle?): List<NotificationCompat.MessagingStyle.Message>? =
        extras?.let { bundle ->
            val body = bundle.getCharSequence(EXTRA_EXPANDED_SINGLE_MESSAGE_BODY)
            val senderBundle = bundle.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
            val timestamp = bundle.getLong(EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP, Long.MIN_VALUE)
            if (body == null || senderBundle == null || timestamp == Long.MIN_VALUE) {
                null
            } else {
                listOf(
                    NotificationCompat.MessagingStyle
                        .Message(
                            boundedNotificationMessageText(body),
                            timestamp,
                            Person.fromBundle(senderBundle),
                        ).apply {
                            // The long first message is one entry identified by the card's stamped message.
                            bundle.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX)?.let {
                                extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, it)
                            }
                        },
                )
            }
        }
}
