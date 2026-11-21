package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the app-lock setting, lock-screen visibility and the unlock grace baseline.
 *
 * The app state supplies its main-thread mutation scope and the host effects that a lock change
 * releases or suppresses; this class decides when the lock shows and when those effects run.
 */
internal class AppLockController(
    private val context: Context,
    preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val persistSetting: (SharedPreferences.Editor.() -> Unit) -> Unit,
    private val timestampWrite: ((Long) -> Unit)?,
    private val persistenceDispatcher: CoroutineDispatcher,
    private val appInForeground: () -> Boolean,
    private val stopSpeaking: () -> Unit,
    private val presentNotice: (titleRes: Int) -> Unit,
    /** Resumes foreground dictation after the lock hides while the app is visible. */
    private val resumeForegroundWork: () -> Unit,
    /** Resumes work deferred while the lock hid private content. */
    private val resumeDeferredWork: () -> Unit,
    /** Runs after a successful unlock, once deferred work has resumed. */
    private val onUnlockSucceeded: (dismissRetainedVisibleConversation: Boolean) -> Unit,
) {
    var required by mutableStateOf(preferences.getBoolean(REQUIRE_APP_UNLOCK_KEY, false))
        private set
    var lockDelay by mutableStateOf(AppLockDelay.fromPreference(preferences.getString(APP_LOCK_DELAY_KEY, null)))
        private set
    var credentialAvailable by mutableStateOf(isAppLockCredentialAvailable(context))
        private set
    var screenVisible by mutableStateOf(false)
        private set

    /** Notification admission re-evaluates the monotonic deadline from the runtime's IO dispatcher. */
    val notificationActionsAllowed: Boolean
        get() =
            notificationActionsAllowed(
                appLockScreenVisible = screenVisible,
                requireUnlock = required,
                credentialAvailable = credentialAvailable,
                authenticatedSessionAvailable =
                    (notificationForegroundEvaluated && appInForeground()) ||
                        notificationBackgroundGraceAllows(
                            notificationBackgroundedAt,
                            SystemClock.elapsedRealtime(),
                            lockDelay,
                        ),
            )

    @Volatile
    private var notificationBackgroundedAt: Long? = null

    @Volatile
    private var notificationForegroundEvaluated = false

    var unlockError by mutableStateOf<AppText?>(null)
        private set

    // True while the foreground lock decision waits for off-main unlock time. The secure scrim remains visible,
    // and the prompt waits for the real value so the grace period is not falsely treated as expired.
    var evaluationPending by mutableStateOf(false)
        private set

    val sessions = AppUnlockSessionController()
    val promptRequestId: Long
        get() = sessions.latestSessionId

    private var lastUnlockAtMillisBacking: Long? = null
    private var lastUnlockAtMillis: Long
        get() = lastUnlockAtMillisBacking ?: 0L
        set(value) {
            lastUnlockAtMillisBacking = value
        }
    private val timestampWriteMutex = Mutex()
    private var ttsBoundaryJob: Job? = null

    fun refreshCredentialAvailability() {
        credentialAvailable = isAppLockCredentialAvailable(context)
        if (!credentialAvailable) clearLock()
    }

    fun updateRequired(enabled: Boolean) {
        refreshCredentialAvailability()
        if (enabled && !credentialAvailable) {
            required = false
            persistSetting { putBoolean(REQUIRE_APP_UNLOCK_KEY, false) }
            presentNotice(R.string.toast_app_lock_screen_lock_required)
            return
        }
        required = enabled
        persistSetting { putBoolean(REQUIRE_APP_UNLOCK_KEY, enabled) }
        if (enabled) {
            requestUnlock()
        } else {
            clearLock()
        }
    }

    fun updateDelay(delay: AppLockDelay) {
        lockDelay = delay
        persistSetting { putString(APP_LOCK_DELAY_KEY, delay.preferenceValue) }
    }

    fun requestUnlock() {
        refreshCredentialAvailability()
        if (!required || !credentialAvailable) return
        showScreen()
        if (sessions.begin()) unlockError = null
    }

    /** Publish successful authentication immediately; persist its captured grace baseline asynchronously. */
    fun markUnlockSucceeded(
        nowMillis: Long = System.currentTimeMillis(),
        dismissRetainedVisibleConversation: Boolean = true,
    ) {
        val normalizedNow = nowMillis.coerceAtLeast(0L)
        lastUnlockAtMillis = normalizedNow
        persistTimestamp(normalizedNow)
        screenVisible = false
        unlockError = null
        resumeForegroundWork()
        resumeDeferredWork()
        onUnlockSucceeded(dismissRetainedVisibleConversation)
    }

    fun markUnlockFailed(message: AppText = AppText.Resource(R.string.app_lock_auth_cancelled)) {
        if (!screenVisible) return
        unlockError = message
    }

    fun shouldSecureWindowWhileBackgrounded(): Boolean = shouldSecureAppLockWindowWhileBackgrounded(required)

    /** Cancels the background TTS boundary and decides whether returning to the app shows the lock. */
    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        ttsBoundaryJob?.cancel()
        ttsBoundaryJob = null
        notificationBackgroundedAt = null
        maybeShowForForeground(nowMillis)
        notificationForegroundEvaluated = true
    }

    fun maybeShowForForeground(nowMillis: Long = System.currentTimeMillis()) {
        refreshCredentialAvailability()
        // Skip timestamp work when app lock is disabled or the device credential is unavailable.
        if (!required || !credentialAvailable) return
        val promptActive = sessions.activeSessionId != null
        if (promptActive) showScreen()
        if (promptActive || sessions.consumeForegroundReturn(SystemClock.elapsedRealtime())) return
        val knownLastUnlock = lastUnlockAtMillisBacking
        if (knownLastUnlock == null) {
            deferDecisionUntilTimestampLoads(nowMillis)
        } else if (shouldShowAt(knownLastUnlock, nowMillis)) {
            requestUnlock()
        }
    }

    /** Start the away-time grace period only for an authenticated, credentialed background transition. */
    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        notificationForegroundEvaluated = false
        sessions.clearForegroundReturn()
        notificationBackgroundedAt = null
        refreshCredentialAvailability()
        if (
            !shouldRefreshAppLockDelayBaselineOnBackground(
                requireUnlock = required,
                credentialAvailable = credentialAvailable,
                lockScreenVisible = screenVisible,
            )
        ) {
            return
        }
        val normalizedNow = nowMillis.coerceAtLeast(0L)
        notificationBackgroundedAt = SystemClock.elapsedRealtime()
        // The delay means time spent away from the app, not time since the
        // last credential prompt while the user was actively reading chats.
        lastUnlockAtMillis = normalizedNow
        persistTimestamp(normalizedNow)
    }

    /** Stops speech when the configured lock delay elapses while the app stays in the background. */
    fun scheduleTtsStopAtLockBoundary() {
        ttsBoundaryJob?.cancel()
        ttsBoundaryJob = null
        if (!required || !credentialAvailable) return
        val delayMillis = lockDelay.delayMillis
        if (delayMillis == 0L) {
            stopSpeaking()
            return
        }
        ttsBoundaryJob =
            scope.launch {
                delay(delayMillis)
                if (!appInForeground() && required && credentialAvailable) {
                    stopSpeaking()
                }
            }
    }

    /**
     * Pre-warm the Keystore-backed unlock timestamp off-main so the first foreground lock evaluation
     * is a cache hit. Assigned on Main, and only if an unlock hasn't already stamped a newer value.
     */
    fun prewarmUnlockTimestamp() {
        if (!required) return
        scope.launch {
            val warmed = withContext(Dispatchers.IO) { AppLockPreferences.readLastUnlockedAtMillis(context) }
            if (lastUnlockAtMillisBacking == null) lastUnlockAtMillisBacking = warmed
        }
    }

    private fun clearLock() {
        sessions.clear()
        screenVisible = false
        unlockError = null
        resumeDeferredWork()
    }

    private fun showScreen() {
        screenVisible = true
        stopSpeaking()
    }

    private fun shouldShowAt(
        lastUnlockedAtMillis: Long,
        nowMillis: Long,
    ): Boolean =
        shouldShowAppLock(
            requireUnlock = required,
            credentialAvailable = credentialAvailable,
            lastUnlockedAtMillis = lastUnlockedAtMillis,
            nowMillis = nowMillis,
            delay = lockDelay,
        )

    private fun deferDecisionUntilTimestampLoads(nowMillis: Long) {
        if (evaluationPending) return
        evaluationPending = true
        showScreen()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { AppLockPreferences.readLastUnlockedAtMillis(context) }
            if (lastUnlockAtMillisBacking == null) lastUnlockAtMillisBacking = loaded
            evaluationPending = false
            // Re-read the clock after IO so a grace period that expired during loading cannot dismiss the lock.
            val decisionNowMillis = maxOf(nowMillis, System.currentTimeMillis())
            if (shouldShowAt(lastUnlockAtMillis, decisionNowMillis)) {
                requestUnlock()
            } else {
                screenVisible = false
                resumeForegroundWork()
                resumeDeferredWork()
            }
        }
    }

    /** Capture on Main; serialize before dispatch so a slower old write cannot overtake a newer event. */
    private fun persistTimestamp(value: Long) {
        scope.launch {
            timestampWriteMutex.withLock {
                withContext(persistenceDispatcher) {
                    val write = timestampWrite
                    if (write != null) {
                        write(value)
                    } else {
                        AppLockPreferences.writeLastUnlockedAtMillis(context, value)
                    }
                }
            }
        }
    }

    internal companion object {
        const val REQUIRE_APP_UNLOCK_KEY = "require_app_unlock"
        const val APP_LOCK_DELAY_KEY = "app_lock_delay"
    }
}
