package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.ipf.whitenoise.android.R

/** Rebuilds generic OS cards from allowlisted routing fields, never from a recovered rich builder. */
internal fun notificationWithoutPreview(
    context: Context,
    original: Notification,
    silent: Boolean = original.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
): Notification {
    val builder =
        NotificationCompat
            .Builder(context, original.channelId)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_hidden_content))
            .setContentIntent(original.contentIntent)
            .setDeleteIntent(original.deleteIntent)
            .setCategory(original.category)
            .setPriority(original.priority)
            .setWhen(original.`when`)
            .setShowWhen(original.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true))
            .setAutoCancel(original.flags and Notification.FLAG_AUTO_CANCEL != 0)
            .setLocalOnly(original.flags and Notification.FLAG_LOCAL_ONLY != 0)
            .setNumber(original.number)
            .setGroup(original.group)
            .setGroupAlertBehavior(original.groupAlertBehavior)
            .setSortKey(original.sortKey)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setAllowSystemGeneratedContextualActions(false)
            .addExtras(previewRoutingExtras(original))
    preserveGenericConversation(context, original, builder)
    addSafeNotificationActions(context, original, builder)
    return builder.build().also { it.publicVersion = genericPublicVersion(context, original) }
}

private fun addSafeNotificationActions(
    context: Context,
    original: Notification,
    builder: NotificationCompat.Builder,
) {
    original.actions.orEmpty().forEach { action ->
        if (
            action.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ &&
            action.remoteInputs.isNullOrEmpty()
        ) {
            builder.addAction(
                NotificationCompat.Action
                    .Builder(
                        R.drawable.ic_stat_whitenoise,
                        context.getString(R.string.chat_row_action_mark_read),
                        action.actionIntent,
                    ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
        }
    }
}

private fun genericPublicVersion(
    context: Context,
    original: Notification,
): Notification =
    NotificationCompat
        .Builder(context, original.channelId)
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_hidden_content))
        .setWhen(original.`when`)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

private fun previewRoutingExtras(original: Notification): Bundle =
    Bundle().apply {
        listOf(
            UserEventNotificationGroup.EXTRA_GENERATION,
            LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF,
            LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID,
            LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
        ).forEach { key -> original.extras.getString(key)?.let { putString(key, it) } }
        putBoolean(
            UserEventNotificationGroup.EXTRA_CHILD,
            original.extras.getBoolean(UserEventNotificationGroup.EXTRA_CHILD),
        )
        if (original.extras.containsKey(UserEventNotificationGroup.EXTRA_LEGACY_POST_TIME)) {
            putLong(
                UserEventNotificationGroup.EXTRA_LEGACY_POST_TIME,
                original.extras.getLong(UserEventNotificationGroup.EXTRA_LEGACY_POST_TIME),
            )
        }
        putBoolean(EXTRA_CONTENT_REDACTED, true)
        putBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN, true)
        original.extras.getString(NotificationPreviewPreferences.EXTRA_SESSION)?.let {
            putString(NotificationPreviewPreferences.EXTRA_SESSION, it)
        }
        putBoolean(
            NotificationPreviewPreferences.EXTRA_ALLOWED,
            original.extras.getBoolean(NotificationPreviewPreferences.EXTRA_ALLOWED),
        )
        if (original.extras.containsKey(NotificationPreviewPreferences.EXTRA_REVISION)) {
            putLong(
                NotificationPreviewPreferences.EXTRA_REVISION,
                original.extras.getLong(NotificationPreviewPreferences.EXTRA_REVISION),
            )
        }
        putBoolean(
            NotificationPreviewPreferences.EXTRA_CORRECTION,
            original.extras.getBoolean(NotificationPreviewPreferences.EXTRA_CORRECTION),
        )
    }

/** Keep Android conversation/DND classification using only generic text and an opaque shortcut. */
private fun preserveGenericConversation(
    context: Context,
    original: Notification,
    builder: NotificationCompat.Builder,
) {
    if (NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(original) == null) return
    val name = context.getString(R.string.app_name)
    val person = Person.Builder().setName(name).build()
    builder.setStyle(
        NotificationCompat
            .MessagingStyle(person)
            .setGroupConversation(false)
            .addMessage(context.getString(R.string.notification_hidden_content), original.`when`, person),
    )
    original.shortcutId?.takeIf(::isConversationShortcutId)?.let { id ->
        if (redactNotificationShortcut(context, id)) builder.setShortcutId(id)
    }
}
