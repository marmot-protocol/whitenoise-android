package dev.ipf.whitenoise.android.updates

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.notificationPermissionGranted

class AppUpdateNotifier(
    private val context: Context,
) {
    fun canPostNotifications(): Boolean = notificationPermissionGranted(context)

    @SuppressLint("MissingPermission")
    fun show(info: AppUpdateInfo): Boolean {
        val latest = info.latestVersion?.takeIf { info.isUpdateAvailable } ?: return false
        if (!canPostNotifications()) return false
        NotificationChannels.ensureChannels(context)
        val body = context.getString(R.string.app_update_available_description, latest)
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannelSpec.APP_UPDATES.id)
                .setSmallIcon(R.drawable.ic_stat_whitenoise)
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

        // Stable id for the single app-update notification; not tied to release versions.
        private const val NOTIFICATION_ID = 410
    }
}

object AppUpdateNavigation {
    const val ACTION_OPEN_UPDATE = "dev.ipf.whitenoise.android.action.OPEN_APP_UPDATE"
    private const val URI_SCHEME = "darkmatter-update"
    private const val URI_HOST_AVAILABLE = "available"

    fun applyToIntent(
        intent: Intent,
        version: String,
    ) {
        intent.action = ACTION_OPEN_UPDATE
        intent.data = Uri.parse("$URI_SCHEME://$URI_HOST_AVAILABLE/${Uri.encode(version)}")
    }

    fun isUpdateTap(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN_UPDATE) return false
        val data = intent.data ?: return false
        return data.scheme == URI_SCHEME &&
            data.host == URI_HOST_AVAILABLE &&
            data.pathSegments.size == 1 &&
            !data.lastPathSegment.isNullOrBlank()
    }
}
