package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

internal fun shouldReattachAppUnlockPrompt(
    hasActiveSession: Boolean,
    hasAttachedHost: Boolean,
    retainedHostSessionMatches: Boolean,
): Boolean = hasActiveSession && hasAttachedHost && retainedHostSessionMatches

/**
 * Process-local ownership for one AndroidX biometric/device-credential prompt.
 *
 * The prompt itself survives Activity recreation inside AndroidX. Keeping its
 * logical session here lets the replacement Activity atomically own the new
 * callback without launching a second prompt. The host token rejects callbacks
 * from the replaced Activity, while the session token makes terminal callbacks
 * idempotent.
 */
internal data class AppUnlockSessionState(
    val latestSessionId: Long = 0L,
    val activeSessionId: Long? = null,
    val activeHostId: Long? = null,
    val foregroundReturnExpiresAtElapsedRealtime: Long? = null,
) {
    fun begin(): AppUnlockSessionState {
        if (activeSessionId != null) return this
        val nextSessionId = if (latestSessionId == Long.MAX_VALUE) 1L else latestSessionId + 1L
        return copy(
            latestSessionId = nextSessionId,
            activeSessionId = nextSessionId,
            activeHostId = null,
            foregroundReturnExpiresAtElapsedRealtime = null,
        )
    }

    fun attachHost(
        sessionId: Long,
        hostId: Long,
        replaceExistingHost: Boolean,
    ): AppUnlockSessionState? {
        val occupiedByAnotherHost = activeHostId != null && activeHostId != hostId && !replaceExistingHost
        if (activeSessionId != sessionId || occupiedByAnotherHost) return null
        return copy(activeHostId = hostId)
    }

    fun owns(
        sessionId: Long,
        hostId: Long,
    ): Boolean = activeSessionId == sessionId && activeHostId == hostId

    fun complete(
        sessionId: Long,
        hostId: Long,
        foregroundReturnExpiresAtElapsedRealtime: Long?,
    ): AppUnlockSessionState? =
        if (!owns(sessionId, hostId)) {
            null
        } else {
            copy(
                activeSessionId = null,
                activeHostId = null,
                foregroundReturnExpiresAtElapsedRealtime = foregroundReturnExpiresAtElapsedRealtime,
            )
        }

    fun terminate(
        sessionId: Long,
        hostId: Long,
    ): AppUnlockSessionState? =
        if (!owns(sessionId, hostId)) {
            null
        } else {
            copy(activeSessionId = null, activeHostId = null, foregroundReturnExpiresAtElapsedRealtime = null)
        }

    fun foregroundReturnIsValid(nowElapsedRealtime: Long): Boolean {
        val expiresAt = foregroundReturnExpiresAtElapsedRealtime ?: return false
        return nowElapsedRealtime <= expiresAt
    }

    fun clearForegroundReturn(): AppUnlockSessionState = copy(foregroundReturnExpiresAtElapsedRealtime = null)

    fun clear(): AppUnlockSessionState =
        copy(
            activeSessionId = null,
            activeHostId = null,
            foregroundReturnExpiresAtElapsedRealtime = null,
        )
}

/** Observable process owner around the immutable app-unlock session state. */
internal class AppUnlockSessionController {
    private var state by mutableStateOf(AppUnlockSessionState())

    val latestSessionId: Long get() = state.latestSessionId
    val activeSessionId: Long? get() = state.activeSessionId
    val hasAttachedHost: Boolean get() = state.activeHostId != null

    fun begin(): Boolean = updateIfChanged(state.begin())

    fun clear() {
        state = state.clear()
    }

    fun attachHost(
        sessionId: Long,
        hostId: Long,
        replaceExistingHost: Boolean,
    ): Boolean = state.attachHost(sessionId, hostId, replaceExistingHost)?.let(::update) ?: false

    fun owns(
        sessionId: Long,
        hostId: Long,
    ): Boolean = state.owns(sessionId, hostId)

    fun complete(
        sessionId: Long,
        hostId: Long,
        foregroundReturnExpiresAtElapsedRealtime: Long?,
    ): Boolean = state.complete(sessionId, hostId, foregroundReturnExpiresAtElapsedRealtime)?.let(::update) ?: false

    fun terminate(
        sessionId: Long,
        hostId: Long,
    ): Boolean = state.terminate(sessionId, hostId)?.let(::update) ?: false

    fun consumeForegroundReturn(nowElapsedRealtime: Long): Boolean {
        if (state.foregroundReturnExpiresAtElapsedRealtime == null) return false
        val valid = state.foregroundReturnIsValid(nowElapsedRealtime)
        state = state.clearForegroundReturn()
        return valid
    }

    fun clearForegroundReturn() {
        state = state.clearForegroundReturn()
    }

    private fun updateIfChanged(next: AppUnlockSessionState): Boolean {
        if (next == state) return false
        state = next
        return true
    }

    private fun update(next: AppUnlockSessionState): Boolean {
        state = next
        return true
    }
}
