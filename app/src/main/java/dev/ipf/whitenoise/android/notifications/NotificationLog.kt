package dev.ipf.whitenoise.android.notifications

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig

internal inline fun notificationWarning(
    tag: String,
    message: String,
    throwable: Throwable? = null,
    debugDetails: () -> String,
) {
    val rendered = notificationWarningMessage(message, BuildConfig.DEBUG, debugDetails)
    if (throwable == null || !BuildConfig.DEBUG) {
        Log.w(tag, rendered)
    } else {
        Log.w(tag, rendered, throwable)
    }
}

internal inline fun notificationWarningMessage(
    message: String,
    includeDebugDetails: Boolean,
    debugDetails: () -> String,
): String =
    if (includeDebugDetails) {
        debugDetails().takeIf { it.isNotBlank() }?.let { "$message $it" } ?: message
    } else {
        message
    }
