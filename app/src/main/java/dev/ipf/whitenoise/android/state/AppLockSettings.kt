package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import dev.ipf.whitenoise.android.R

internal val APP_LOCK_ALLOWED_AUTHENTICATORS: Int =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

enum class AppLockDelay(
    val preferenceValue: String,
    val delayMillis: Long,
    @param:StringRes val labelRes: Int,
) {
    Immediately("immediately", 0L, R.string.app_lock_delay_immediately),
    OneMinute("1m", 60_000L, R.string.app_lock_delay_one_minute),
    FiveMinutes("5m", 5L * 60_000L, R.string.app_lock_delay_five_minutes),
    FifteenMinutes("15m", 15L * 60_000L, R.string.app_lock_delay_fifteen_minutes),
    ;

    companion object {
        fun fromPreference(value: String?): AppLockDelay = entries.firstOrNull { it.preferenceValue == value } ?: Immediately
    }
}

internal fun isAppLockCredentialAvailable(context: Context): Boolean =
    BiometricManager.from(context.applicationContext).canAuthenticate(APP_LOCK_ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

internal fun shouldShowAppLock(
    requireUnlock: Boolean,
    credentialAvailable: Boolean,
    lastUnlockedAtMillis: Long,
    nowMillis: Long,
    delay: AppLockDelay,
): Boolean {
    if (!requireUnlock || !credentialAvailable) return false
    val elapsedMillis = (nowMillis - lastUnlockedAtMillis).coerceAtLeast(0L)
    return elapsedMillis >= delay.delayMillis
}

internal fun shouldRefreshAppLockDelayBaselineOnBackground(
    requireUnlock: Boolean,
    credentialAvailable: Boolean,
    lockScreenVisible: Boolean,
): Boolean = requireUnlock && credentialAvailable && !lockScreenVisible

internal fun shouldSecureAppLockWindowWhileBackgrounded(requireUnlock: Boolean): Boolean = requireUnlock
