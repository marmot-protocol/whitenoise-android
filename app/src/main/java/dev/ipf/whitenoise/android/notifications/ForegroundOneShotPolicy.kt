package dev.ipf.whitenoise.android.notifications

internal fun shouldStopAfterOneShotForegroundStart(
    oneShotRequested: Boolean,
    backgroundConnectionEnabled: Boolean,
): Boolean = oneShotRequested && !backgroundConnectionEnabled

internal fun isOneShotForegroundStart(
    syncNativePushRegistrationRequested: Boolean,
    trigger: ForegroundStartTrigger,
): Boolean = syncNativePushRegistrationRequested || trigger == ForegroundStartTrigger.PushWake
