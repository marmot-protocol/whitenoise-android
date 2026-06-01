package dev.ipf.darkmatter.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.ipf.darkmatter.MainActivity
import dev.ipf.darkmatter.R
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

class LocalNotificationPresenter(private val context: Context) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun show(update: NotificationUpdateFfi): Boolean {
        val content = LocalNotificationFormatter.content(update, context) ?: run {
            notificationDebug { "skip key=${update.notificationKey.take(16)} reason=formatter" }
            return false
        }
        if (!canPostNotifications()) {
            notificationDebug { "skip key=${update.notificationKey.take(16)} reason=permission" }
            return false
        }
        ensureChannels()

        val pendingIntent = PendingIntent.getActivity(
            context,
            content.notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val category = when (update.trigger) {
            NotificationTriggerFfi.NEW_MESSAGE -> NotificationCompat.CATEGORY_MESSAGE
            NotificationTriggerFfi.GROUP_INVITE -> NotificationCompat.CATEGORY_EVENT
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_darkmatter)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(pendingIntent)
            .setCategory(category)
            .setWhen(update.timestampMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(false)
            .apply {
                content.groupKey?.let { group ->
                    setGroup(group)
                    setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(content.notificationId, notification)
        notificationDebug {
            "posted id=${content.notificationId} trigger=${update.trigger} group=${update.groupIdHex.take(8)} title=${content.title}"
        }
        return true
    }

    companion object {
        const val CHANNEL_MESSAGES = "darkmatter.messages.v2"
    }
}

private inline fun notificationDebug(message: () -> String) {
    Log.i("DMLocalNotify", message())
}
