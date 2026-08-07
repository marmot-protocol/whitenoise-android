package dev.ipf.whitenoise.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import dev.ipf.whitenoise.android.amber.AmberActivityCoordinator
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTapTokens
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.inboundNotificationHandledMatchesCurrent
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.parseShareRequest
import dev.ipf.whitenoise.android.state.APP_LOCK_ALLOWED_AUTHENTICATORS
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.ChatScreenshotPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.common.releaseSecureFlag
import dev.ipf.whitenoise.android.ui.common.retainSecureFlag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateNavigation

class MainActivity : FragmentActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private var inboundNotificationTarget by mutableStateOf<NotificationTarget?>(null)
    private var inboundNotificationRequestId by mutableStateOf(0L)
    private var inboundShareRequest by mutableStateOf<ShareRequest?>(null)
    private var inboundAppUpdateTap by mutableStateOf(0)
    private var appUnlockPromptActive = false
    private var appLockBackgroundSecureFlagRetained = false
    private var recentsPreferenceSecureFlagRetained = false
    private val allowChatScreenshotsCallback: (Boolean) -> Unit = { enabled ->
        applyRecentsPreferenceSecureFlag(allowChatScreenshots = enabled)
    }
    private lateinit var notificationTapTokens: NotificationTapTokens
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var amberSignerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val initialSystemDarkTheme = resources.configuration.isNightModeActive
        // Apply the pre-Compose theme here, not in attachBaseContext: the window
        // doesn't exist that early, so Activity.setTheme() NPEs on getWindow().
        // onCreate (before super) still runs before the first frame.
        setTheme(preComposeThemeFor(readPersistedThemeMode(), initialSystemDarkTheme))
        super.onCreate(savedInstanceState)
        appState = (application as WhiteNoiseApplication).appState
        holdSplashThroughBootstrap(splashScreen)
        appState.onAllowChatScreenshotsChanged = allowChatScreenshotsCallback
        applyRecentsPreferenceSecureFlag(
            allowChatScreenshots = ChatScreenshotPreferences.readAllowChatScreenshots(this),
        )
        notificationTapTokens = NotificationTapTokens.create(this)
        registerAmberSignerLauncher()
        consumeIntent(intent)
        enableEdgeToEdge()
        applyPreComposeWindowBackground(appState.themeMode, initialSystemDarkTheme)
        setContent {
            val state = remember { appState }
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = state.themeMode.resolveDarkTheme(systemDarkTheme)
            // The in-app theme can override the system theme (e.g. AMOLED while
            // the system is light), so the pre-Compose fallback and system-bar
            // icons must follow the resolved app theme. Left on the edge-to-edge
            // default, dark icons land on a black background and disappear.
            DisposableEffect(state.themeMode, systemDarkTheme) {
                applyPreComposeWindowBackground(state.themeMode, systemDarkTheme)
                onDispose { }
            }
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            WhiteNoiseTheme(
                darkTheme = darkTheme,
                amoled = state.themeMode.isAmoled,
                fontScale = state.fontScale.factor,
                appFont = state.appFont,
            ) {
                WhiteNoiseApp(
                    appState = state,
                    inboundProfilePayload = inboundProfilePayload,
                    onProfilePayloadHandled = { handled ->
                        if (inboundProfilePayload == handled) inboundProfilePayload = null
                    },
                    inboundNotificationTarget = inboundNotificationTarget,
                    inboundNotificationRequestId = inboundNotificationRequestId,
                    onNotificationTargetHandled = ::handleNotificationTarget,
                    inboundShareRequest = inboundShareRequest,
                    onShareRequestHandled = { handled ->
                        if (inboundShareRequest == handled) inboundShareRequest = null
                    },
                    inboundAppUpdateTap = inboundAppUpdateTap,
                    onAppUpdateTapHandled = { handled ->
                        if (inboundAppUpdateTap == handled) inboundAppUpdateTap = 0
                    },
                    onRequestAppUnlock = ::requestAppUnlock,
                )
            }
        }
    }

    private fun handleNotificationTarget(
        handledTarget: NotificationTarget,
        handledRequestId: Long,
    ) {
        if (
            inboundNotificationHandledMatchesCurrent(
                inboundTarget = inboundNotificationTarget,
                inboundRequestId = inboundNotificationRequestId,
                handledTarget = handledTarget,
                handledRequestId = handledRequestId,
            )
        ) {
            inboundNotificationTarget = null
        }
    }

    // NIP-55 (Amber) approval prompts route through this launcher. Registered
    // here and attached to the app-scoped coordinator; results are fed back on
    // the main thread. Detached in onDestroy so a background signer callback
    // finds no launcher (and throws a typed unavailable error) rather than a
    // stale one.
    private fun registerAmberSignerLauncher() {
        amberSignerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                AmberActivityCoordinator.deliverResult(result.resultCode == RESULT_OK, result.data)
            }
        AmberActivityCoordinator.attach(amberSignerLauncher)
    }

    // Keep the system splash (icon on the themed window background) up until
    // engine bootstrap flips the phase, instead of a bare spinner mid-boot.
    // Installed before super.onCreate, held from here once appState exists.
    private fun holdSplashThroughBootstrap(splashScreen: androidx.core.splashscreen.SplashScreen) {
        splashScreen.setKeepOnScreenCondition { appState.phase == AppPhase.Bootstrapping }
    }

    /**
     * Route an inbound intent: a notification tap (our [NotificationNavigation.ACTION_OPEN]
     * action) becomes a navigation target; a White Noise data URI becomes a
     * profile-link payload. A dataless, non-notification intent leaves any
     * already-queued target/link intact (see [routeInboundIntent]).
     */
    private fun consumeIntent(intent: Intent?) {
        if (AppUpdateNavigation.isUpdateTap(intent)) {
            inboundAppUpdateTap += 1
            // One-shot, like the notification tap below: clear the stored intent so
            // activity recreation cannot replay the same update tap.
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val parsedTarget =
            NotificationNavigation.parse(intent) { notificationKey, tapToken ->
                notificationTapTokens.isValid(notificationKey, tapToken)
            }
        val parsedShare = parseShareRequest(intent)
        val routing =
            routeInboundIntent(
                parsedTarget = parsedTarget,
                shareRequest = parsedShare,
                dataString = intent?.dataString,
                current =
                    InboundIntentRouting(
                        notificationTarget = inboundNotificationTarget,
                        profilePayload = inboundProfilePayload,
                        shareRequest = inboundShareRequest,
                        notificationRequestId = inboundNotificationRequestId,
                    ),
            )
        inboundNotificationTarget = routing.notificationTarget
        inboundNotificationRequestId = routing.notificationRequestId
        inboundProfilePayload = routing.profilePayload
        inboundShareRequest = routing.shareRequest
        if (parsedTarget != null || parsedShare != null) {
            // Notification taps and share intents are one-shot navigation requests.
            // Replace the stored intent after parsing so activity recreation cannot
            // replay the same target after the UI has already consumed it.
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        if (::appState.isInitialized) {
            appState.setAppInForeground(true)
            applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)
            if (!appState.appLockScreenVisible) releaseAppLockBackgroundSecureFlag()
        }
    }

    override fun onPause() {
        retainAppLockBackgroundSecureFlagIfNeeded()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::appState.isInitialized) {
            applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)
            if (!appState.appLockScreenVisible) releaseAppLockBackgroundSecureFlag()
        }
    }

    override fun onStop() {
        if (::appState.isInitialized) {
            retainAppLockBackgroundSecureFlagIfNeeded()
            appState.setAppInForeground(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::amberSignerLauncher.isInitialized) AmberActivityCoordinator.detach(amberSignerLauncher)
        releaseAppLockBackgroundSecureFlag()
        if (::appState.isInitialized && appState.onAllowChatScreenshotsChanged === allowChatScreenshotsCallback) {
            appState.onAllowChatScreenshotsChanged = null
        }
        releaseRecentsPreferenceSecureFlag()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun requestAppUnlock() {
        if (!::appState.isInitialized || !appState.appLockScreenVisible || appUnlockPromptActive) return
        appUnlockPromptActive = true
        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setSubtitle(getString(R.string.app_lock_prompt_subtitle))
                .setAllowedAuthenticators(APP_LOCK_ALLOWED_AUTHENTICATORS)
                .build()
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        appUnlockPromptActive = false
                        appState.markAppUnlockSucceeded()
                        releaseAppLockBackgroundSecureFlag()
                    }

                    override fun onAuthenticationFailed() {
                        appState.markAppUnlockFailed(AppText.Resource(R.string.app_lock_auth_failed))
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        appUnlockPromptActive = false
                        appState.markAppUnlockFailed(appLockAuthErrorMessage(errorCode, errString))
                    }
                },
            )
        runCatching {
            prompt.authenticate(promptInfo)
        }.onFailure {
            appUnlockPromptActive = false
            appState.markAppUnlockFailed(AppText.Resource(R.string.app_lock_auth_cancelled))
        }
    }

    private fun appLockAuthErrorMessage(
        errorCode: Int,
        errString: CharSequence,
    ): AppText =
        when (errorCode) {
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            -> AppText.Resource(R.string.app_lock_auth_cancelled)
            else ->
                errString
                    .toString()
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(AppText::Plain)
                    ?: AppText.Resource(R.string.app_lock_auth_cancelled)
        }

    private fun retainAppLockBackgroundSecureFlagIfNeeded() {
        if (!::appState.isInitialized || !appState.shouldSecureAppLockWindowWhileBackgrounded()) return
        // Recents snapshots are captured while the activity is pausing/stopping,
        // before Compose can draw the app-lock surface on the next foreground.
        if (!appLockBackgroundSecureFlagRetained) {
            window.retainSecureFlag()
            appLockBackgroundSecureFlagRetained = true
        }
    }

    private fun releaseAppLockBackgroundSecureFlag() {
        if (appLockBackgroundSecureFlagRetained) {
            window.releaseSecureFlag()
            appLockBackgroundSecureFlagRetained = false
        }
    }

    private fun applyRecentsPreferenceSecureFlag(allowChatScreenshots: Boolean) {
        if (allowChatScreenshots) {
            releaseRecentsPreferenceSecureFlag()
        } else if (!recentsPreferenceSecureFlagRetained) {
            window.retainSecureFlag()
            recentsPreferenceSecureFlagRetained = true
        }
    }

    private fun releaseRecentsPreferenceSecureFlag() {
        if (recentsPreferenceSecureFlagRetained) {
            window.releaseSecureFlag()
            recentsPreferenceSecureFlagRetained = false
        }
    }

    private fun readPersistedThemeMode(): AppThemeMode =
        AppThemeMode.fromPreference(
            getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(THEME_MODE_KEY, null),
        )

    private fun applyPreComposeWindowBackground(
        themeMode: AppThemeMode,
        systemDarkTheme: Boolean,
    ) {
        window.setBackgroundDrawable(ColorDrawable(preComposeWindowBackgroundFor(themeMode, systemDarkTheme)))
    }
}

internal fun preComposeThemeFor(
    themeMode: AppThemeMode,
    systemDarkTheme: Boolean,
): Int =
    when (themeMode) {
        AppThemeMode.System -> if (systemDarkTheme) R.style.Theme_WhiteNoise_Dark else R.style.Theme_WhiteNoise_Light
        AppThemeMode.Light -> R.style.Theme_WhiteNoise_Light
        AppThemeMode.Dark -> R.style.Theme_WhiteNoise_Dark
        AppThemeMode.Amoled -> R.style.Theme_WhiteNoise_Amoled
    }

internal fun preComposeWindowBackgroundFor(
    themeMode: AppThemeMode,
    systemDarkTheme: Boolean,
): Int =
    when (themeMode) {
        AppThemeMode.System -> if (systemDarkTheme) PRE_COMPOSE_BACKGROUND_DARK else PRE_COMPOSE_BACKGROUND_LIGHT
        AppThemeMode.Light -> PRE_COMPOSE_BACKGROUND_LIGHT
        AppThemeMode.Dark -> PRE_COMPOSE_BACKGROUND_DARK
        AppThemeMode.Amoled -> Color.BLACK
    }

private val Configuration.isNightModeActive: Boolean
    get() = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private const val APP_PREFERENCES_NAME = "whitenoise"
private const val THEME_MODE_KEY = "theme_mode"
private const val PRE_COMPOSE_BACKGROUND_LIGHT = 0xFFECEEEE.toInt()
private const val PRE_COMPOSE_BACKGROUND_DARK = 0xFF0F1112.toInt()
