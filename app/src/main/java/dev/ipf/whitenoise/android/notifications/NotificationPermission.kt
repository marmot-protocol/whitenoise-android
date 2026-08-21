package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android 12 and below allow notifications without a runtime permission. */
internal fun notificationPermissionGranted(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean {
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) return true
    return notificationPermissionGranted(
        sdkInt = sdkInt,
        runtimePermissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
    )
}

internal fun notificationPermissionGranted(
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU || runtimePermissionGranted
