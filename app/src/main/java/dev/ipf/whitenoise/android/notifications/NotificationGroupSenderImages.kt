package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

/**
 * A non-null transparent icon stops Android from generating sender initials while keeping genuine group
 * MessagingStyle and its native actions. SystemUI may still reserve its avatar gutter or draw a blank circle.
 * The immutable pixels remain alive for posted cards; the group picture is owned separately by the identity image.
 */
private val transparentGroupSenderBitmap: Bitmap by lazy {
    Bitmap.createBitmap(intArrayOf(Color.TRANSPARENT), 1, 1, Bitmap.Config.ARGB_8888)
}

/** Removes picture pixels without changing the sender's name or routing/importance metadata. */
internal fun groupNotificationPerson(person: Person): Person = person.toBuilder().setIcon(groupSenderIcon()).build()

/** A fresh icon wrapper shares immutable pixels, so one Person cannot retint another Person's icon. */
internal fun groupSenderIcon(): IconCompat = IconCompat.createWithBitmap(transparentGroupSenderBitmap)

/** Initial group publication supplies both fields that an ordinary icon-only refresh intentionally omits. */
internal data class InitialGroupShortcutPresentation(
    val icon: IconCompat,
    val sender: Person,
)

/** Private overrides are resolved before this final sender policy enters the public shortcut factory. */
internal fun notificationShortcutSender(
    content: LocalNotificationContent,
    sender: Person,
): Person =
    if (content.isGroupConversation) {
        groupNotificationPerson(sender)
    } else {
        sender
    }

/** Only an initial rich group publication may add a group identity and sender to an icon-only update. */
internal fun initialGroupShortcutPresentation(
    content: LocalNotificationContent,
    title: String,
    shortcutId: String,
    groupPicture: Bitmap?,
    sender: Person,
): InitialGroupShortcutPresentation? =
    if (content.isGroupConversation) {
        InitialGroupShortcutPresentation(notificationConversationIcon(title, shortcutId, groupPicture), sender)
    } else {
        null
    }

/**
 * Final rich-group presentation fence after private overrides and privacy reconciliation. Normalize every retained
 * sender and the self Person, including history carried from older builds. Null message senders remain self rows.
 */
internal fun withoutGroupSenderPictures(
    context: Context,
    notification: Notification,
): Notification {
    val existing =
        if (notification.extras.getBoolean(EXTRA_CONTENT_REDACTED)) {
            null
        } else {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }
    if (existing == null || !existing.isGroupConversation) return notification
    val style =
        NotificationCompat
            .MessagingStyle(groupNotificationPerson(existing.user))
            .setGroupConversation(true)
            .also { copy ->
                existing.conversationTitle?.let { copy.setConversationTitle(it) }
                existing.messages.forEach { message ->
                    copy.addMessage(copiedMessage(message, message.person?.let(::groupNotificationPerson)))
                }
                existing.historicMessages.forEach {
                    copy.addHistoricMessage(copiedMessage(it, it.person?.let(::groupNotificationPerson)))
                }
            }
    val builder =
        NotificationCompat
            .Builder(context, notification)
            .setStyle(style)
            .setGroupAlertBehavior(notification.groupAlertBehavior)
    // API 30 recovery cannot reconstruct legacy history once its unsafe structured duplicate was removed.
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
        notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.let {
            builder.setRemoteInputHistory(it)
        }
    }
    return builder
        .build()
        .apply {
            flags = notification.flags
            actions = notification.actions
            extras = Bundle(extras)
            groupNotificationPeople(notification)?.let { people ->
                extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, people)
            }
        }.withoutApi30RemoteInputHistoryItems()
}

/** Rich Person metadata follows the same policy; legacy URI people and unrelated extras are retained. */
@Suppress("DEPRECATION") // The API 30-compatible typed-list overload is required on every supported Android version.
private fun groupNotificationPeople(notification: Notification): ArrayList<android.app.Person>? =
    notification.extras
        .getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
        ?.mapTo(arrayListOf()) {
            it.toBuilder().setIcon(Icon.createWithBitmap(transparentGroupSenderBitmap)).build()
        }
