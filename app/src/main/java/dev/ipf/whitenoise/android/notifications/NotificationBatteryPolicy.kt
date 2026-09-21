package dev.ipf.whitenoise.android.notifications

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** User-visible background battery policy states exposed by Android. */
internal enum class NotificationBatteryPolicy {
    Optimized,
    Unrestricted,
    Restricted,
    Unknown,
}

/** Resolves policy precedence without initiating work or requesting an exemption. */
internal fun notificationBatteryPolicy(
    backgroundRestricted: Boolean?,
    ignoringBatteryOptimizations: Boolean?,
): NotificationBatteryPolicy =
    when {
        backgroundRestricted == true -> NotificationBatteryPolicy.Restricted
        ignoringBatteryOptimizations == true -> NotificationBatteryPolicy.Unrestricted
        ignoringBatteryOptimizations == false -> NotificationBatteryPolicy.Optimized
        else -> NotificationBatteryPolicy.Unknown
    }

/** Reads live system policy only when the settings surface is resumed. */
internal fun readNotificationBatteryPolicy(context: Context): NotificationBatteryPolicy {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    return notificationBatteryPolicy(
        backgroundRestricted = activityManager?.isBackgroundRestricted,
        ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName),
    )
}

/** Opens a user-initiated app battery settings surface, falling back to app details. */
internal fun openNotificationBatterySettings(context: Context): Boolean {
    val packageUri = Uri.parse("package:${context.packageName}")
    val appBatteryIntent =
        Intent(ACTION_APP_BATTERY_SETTINGS, packageUri)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (startSettingsActivity(context, appBatteryIntent)) return true
    return startSettingsActivity(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Returns false when Android cannot resolve or permit the requested settings surface. */
private fun startSettingsActivity(
    context: Context,
    intent: Intent,
): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

private const val ACTION_APP_BATTERY_SETTINGS = "android.settings.APP_BATTERY_SETTINGS"
