package dev.ipf.darkmatter.updates

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.ipf.darkmatter.MainActivity
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.notifications.NotificationChannelSpec
import dev.ipf.darkmatter.notifications.NotificationChannels

class AppUpdateNotifier(
    private val context: Context,
) {
    fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(info: AppUpdateInfo): Boolean {
        val latest = info.latestVersion?.takeIf { info.isUpdateAvailable } ?: return false
        if (!canPostNotifications()) return false
        NotificationChannels.ensureChannels(context)
        val body = context.getString(R.string.app_update_available_description, latest)
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannelSpec.APP_UPDATES.id)
                .setSmallIcon(R.drawable.ic_stat_darkmatter)
                .setContentTitle(context.getString(R.string.app_update_available_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(updatePendingIntent(latest))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setSilent(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        return true
    }

    private fun updatePendingIntent(version: String): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                AppUpdateNavigation.applyToIntent(this, version)
            }
        return PendingIntent.getActivity(
            context,
            version.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val NOTIFICATION_TAG = "app_update"
        private const val NOTIFICATION_ID = 410
    }
}

object AppUpdateNavigation {
    const val ACTION_OPEN_UPDATE = "dev.ipf.darkmatter.action.OPEN_APP_UPDATE"
    private const val URI_SCHEME = "darkmatter-update"

    fun applyToIntent(
        intent: Intent,
        version: String,
    ) {
        intent.action = ACTION_OPEN_UPDATE
        intent.data = Uri.parse("$URI_SCHEME://available/${Uri.encode(version)}")
    }

    fun isUpdateTap(intent: Intent?): Boolean = intent?.action == ACTION_OPEN_UPDATE
}
