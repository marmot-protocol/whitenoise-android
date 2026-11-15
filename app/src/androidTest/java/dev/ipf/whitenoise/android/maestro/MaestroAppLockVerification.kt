package dev.ipf.whitenoise.android.maestro

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.state.AppLockDelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isAppLockCredentialAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Check real device credential absence; these journeys never configure or clear an OS credential. */
internal suspend fun verifyMaestroNoAppLockCredential(
    context: Context,
    state: WhiteNoiseAppState,
    preferences: SharedPreferences,
    postcondition: String?,
): Boolean {
    if (postcondition != "app-lock-unavailable") return false
    return withTimeout(15_000L) {
        check(!checkNotNull(context.getSystemService(KeyguardManager::class.java)).isDeviceSecure)
        check(!isAppLockCredentialAvailable(context))
        withContext(Dispatchers.Main.immediate) {
            check(!state.appLock.credentialAvailable)
            check(!state.appLock.required)
            check(!state.appLock.screenVisible)
            check(!state.appLock.evaluationPending)
            check(state.appLock.unlockError == null)
            check(state.appLock.sessions.activeSessionId == null)
            check(state.appLock.lockDelay == AppLockDelay.Immediately)
        }
        check(!preferences.getBoolean("require_app_unlock", false))
        check(preferences.getString("app_lock_delay", null) == null)
        true
    }
}
