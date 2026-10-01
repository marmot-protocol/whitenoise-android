package dev.ipf.whitenoise.android.audio

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R

/** Metadata-free rendering reads only the current controller; it never owns capture or a service lease. */
internal fun buildConversationDictationNotification(
    context: Context,
    controller: ConversationDictationController,
    actionIntent: (String, String) -> PendingIntent,
): Notification =
    Notification
        .Builder(context, ConversationDictationForegroundService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(context.getString(R.string.dictation_notification_title))
        .setContentText(context.getString(dictationNotificationStatus(controller)))
        // Status names the phase; an indeterminate progress bar looked stuck during normal provider restarts.
        .setContentIntent(openDictationAppIntent(context))
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setStyle(Notification.DecoratedCustomViewStyle())
        .setCustomContentView(compactDictationControls(context, controller, actionIntent))
        .build()

private fun compactDictationControls(
    context: Context,
    controller: ConversationDictationController,
    actionIntent: (String, String) -> PendingIntent,
): RemoteViews {
    val token = requireNotNull(controller.notificationSessionToken)
    return RemoteViews(context.packageName, R.layout.notification_dictation_compact).apply {
        val cancelEnabled = !controller.deliveryInProgress
        val completionEnabled = controller.completionActionsEnabled
        setBoolean(R.id.dictation_notification_cancel, "setEnabled", cancelEnabled)
        setBoolean(R.id.dictation_notification_paste, "setEnabled", completionEnabled)
        setBoolean(R.id.dictation_notification_send, "setEnabled", completionEnabled)
        if (cancelEnabled) {
            setOnClickPendingIntent(
                R.id.dictation_notification_cancel,
                actionIntent(ConversationDictationForegroundService.ACTION_CANCEL, token),
            )
        }
        if (completionEnabled) {
            setOnClickPendingIntent(
                R.id.dictation_notification_paste,
                actionIntent(ConversationDictationForegroundService.ACTION_PASTE, token),
            )
            setOnClickPendingIntent(
                R.id.dictation_notification_send,
                actionIntent(ConversationDictationForegroundService.ACTION_SEND, token),
            )
        }
    }
}

private fun openDictationAppIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** Describes actual readiness/finalization, never a model download or invented percentage. */
private fun dictationNotificationStatus(controller: ConversationDictationController): Int =
    when {
        controller.deliveryInProgress -> R.string.message_status_pending
        controller.state is ConversationDictationState.Starting && controller.captureInProgress ->
            R.string.dictation_starting
        controller.state is ConversationDictationState.Processing || !controller.captureInProgress ->
            R.string.dictation_processing
        else -> R.string.dictation_notification_text
    }
