package dev.ipf.whitenoise.android.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R

internal const val DICTATION_RECOVERY_NOTIFICATION_TAG = "dictation-recovery"
internal const val DICTATION_RECOVERY_NOTIFICATION_ID = 1002

/** Metadata-free rendering reads only the current controller; it never owns capture or a service lease. */
internal fun buildConversationDictationNotification(
    context: Context,
    controller: ConversationDictationController,
    actionIntent: (String, String) -> PendingIntent,
): Notification =
    Notification
        .Builder(context, ConversationDictationForegroundService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(
            context.getString(
                if (controller.state is ConversationDictationState.Failed || controller.recoveryHandedToComposer) {
                    R.string.dictation_recovery_title
                } else {
                    R.string.dictation_notification_title
                },
            ),
        ).setContentText(
            context.getString(
                if (controller.state is ConversationDictationState.Failed || controller.recoveryHandedToComposer) {
                    R.string.dictation_recovery_text
                } else {
                    dictationNotificationStatus(controller)
                },
            ),
        )
        // Status names the phase; an indeterminate progress bar looked stuck during normal provider restarts.
        .setContentIntent(openDictationAppIntent(context))
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .apply {
            if (controller.state is ConversationDictationState.Failed || controller.recoveryHandedToComposer) {
                addRecoveryActions(context, controller, actionIntent)
            } else {
                setStyle(Notification.DecoratedCustomViewStyle())
                setCustomContentView(compactDictationControls(context, controller, actionIntent))
            }
        }.build()

/** Optional audio recovery uses the existing drawer surface without taking composer controls. */
private fun Notification.Builder.addRecoveryActions(
    context: Context,
    controller: ConversationDictationController,
    actionIntent: (String, String) -> PendingIntent,
) {
    addAction(0, context.getString(R.string.dictation_recovery_open), openDictationAppIntent(context))
    if (!controller.recoveryHandedToComposer) return
    val token = requireNotNull(controller.notificationSessionToken)
    if (controller.canRetryRetainedAudio && !controller.foregroundMicrophoneRequired) {
        addAction(
            0,
            context.getString(R.string.retry),
            actionIntent(ConversationDictationForegroundService.ACTION_RETRY_AUDIO, token),
        )
    }
    addAction(
        0,
        context.getString(R.string.dismiss),
        actionIntent(ConversationDictationForegroundService.ACTION_DISCARD_RECOVERY, token),
    )
}

/** Expiry clears recovery data and leaves one ordinary, dismissible notice with no recording actions. */
internal fun notifyConversationDictationRecoveryExpired(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    ConversationDictationForegroundService.ensureChannel(context)
    val notification =
        Notification
            .Builder(context, ConversationDictationForegroundService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(context.getString(R.string.dictation_recovery_expired_title))
            .setContentText(context.getString(R.string.dictation_recovery_expired_text))
            .setContentIntent(openDictationAppIntent(context))
            .setAutoCancel(true)
            .build()
    manager.notify(DICTATION_RECOVERY_NOTIFICATION_TAG, DICTATION_RECOVERY_NOTIFICATION_ID, notification)
}

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
