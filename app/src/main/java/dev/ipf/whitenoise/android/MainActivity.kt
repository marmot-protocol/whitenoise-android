package dev.ipf.whitenoise.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import dev.ipf.whitenoise.android.amber.AmberActivityCoordinator
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
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
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.ChatScreenshotPreferences
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.common.releaseSecureFlag
import dev.ipf.whitenoise.android.ui.common.retainSecureFlag
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeLifecycleClass
import dev.ipf.whitenoise.android.ui.navigation.warmResumeActivityLifecycleClass
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateNavigation

class MainActivity : AppCompatActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private var inboundNotificationTarget by mutableStateOf<NotificationTarget?>(null)
    private var inboundNotificationRequestId by mutableLongStateOf(0L)
    private var inboundAppUpdateTap by mutableIntStateOf(0)
    private var appUnlockPromptActive = false
    private var appLockBackgroundSecureFlagRetained = false
    private var recentsPreferenceSecureFlagRetained = false
    private val foregroundConversationDismissal = ForegroundConversationDismissalCoordinator()
    private val allowChatScreenshotsCallback: (Boolean) -> Unit = { enabled ->
        applyRecentsPreferenceSecureFlag(allowChatScreenshots = enabled)
    }
    private lateinit var notificationTapTokens: NotificationTapTokens
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var amberSignerLauncher: ActivityResultLauncher<Intent>
    private var warmResumeTraceToken = 0
    private var warmResumeEpoch by mutableIntStateOf(0)
    private var warmResumeLifecycleClass = WarmResumeLifecycleClass.ColdProcessStart
    private var mainShellHolderCreatedForActivity = false
    private val mainShellStateHolder: MainShellStateHolder by viewModels {
        MainShellStateHolder.Factory(
            appState = appState,
            processState = (application as WhiteNoiseApplication).mainShellProcessState,
            onHolderCreated = { mainShellHolderCreatedForActivity = true },
        )
    }
    private var inboundShareRequest: ShareRequest?
        get() = mainShellStateHolder.inboundShareRequest.value
        set(value) {
            mainShellStateHolder.acceptInboundShareRequest(value)
        }

    /** Exposes the route-owning request to lifecycle instrumentation without exposing its payload to saved state. */
    internal val pendingInboundShareRequestForTest: ShareRequest?
        get() = mainShellStateHolder.visibleShareRequest

    /** Simulates the route acknowledgement used by lifecycle instrumentation. */
    internal fun acknowledgeInboundShareForTest(requestId: String) {
        mainShellStateHolder.clearPendingShareRequest(requestId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val splashInstalledAtMs = SystemClock.elapsedRealtime()
        val initialSystemDarkTheme = resources.configuration.isNightModeActive
        // Apply the pre-Compose theme here, not in attachBaseContext: the window
        // doesn't exist that early, so Activity.setTheme() NPEs on getWindow().
        // onCreate (before super) still runs before the first frame.
        setTheme(preComposeThemeFor(firstFrameTheme(), initialSystemDarkTheme))
        super.onCreate(savedInstanceState)
        appState = (application as WhiteNoiseApplication).appState
        val processProjectionAlreadyOwned =
            (application as WhiteNoiseApplication)
                .mainShellProcessState
                .localProjectionAvailable(
                    activeAccountRef = appState.activeAccountRef,
                    runtimeGeneration = appState.runtimeGeneration,
                )
        // Resolve the ViewModel before classifying this Activity. The factory
        // runs only for a genuinely fresh ViewModelStore; retained recreation
        // returns the existing holder without invoking it.
        mainShellStateHolder
        warmResumeLifecycleClass =
            warmResumeActivityLifecycleClass(
                holderCreatedForActivity = mainShellHolderCreatedForActivity,
                processProjectionAlreadyOwned = processProjectionAlreadyOwned,
                savedStateAvailable = savedInstanceState != null,
            )
        warmResumeTraceToken =
            WarmResumeTrace.activityCreated(
                lifecycleClass = warmResumeLifecycleClass,
                savedStateAvailable = savedInstanceState != null,
                runtimeGeneration = appState.runtimeGeneration,
            )
        holdSplashThroughBootstrap(splashScreen, splashInstalledAtMs)
        appState.onAllowChatScreenshotsChanged = allowChatScreenshotsCallback
        applyRecentsPreferenceSecureFlag(
            allowChatScreenshots = ChatScreenshotPreferences.readAllowChatScreenshots(this),
        )
        notificationTapTokens = NotificationTapTokens.create(this)
        registerAmberSignerLauncher()
        consumeIntent(
            intent = intent,
            retainPendingShareOnRecreation = savedInstanceState != null,
        )
        enableEdgeToEdge()
        applyPreComposeWindowBackground(appState.themeMode, initialSystemDarkTheme)
        installComposeContent()
    }

    private fun installComposeContent() {
        setContent {
            val state = remember { appState }
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = state.themeMode.resolveDarkTheme(systemDarkTheme)
            val bubbleTheme = BubbleTheme.resolve(state.themeMode, systemDarkTheme)
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
                accentColorArgb = state.actionColorArgb(bubbleTheme),
                fontScale = state.fontScale.factor,
                appFont = state.appFont,
            ) {
                WhiteNoiseApp(
                    appState = state,
                    mainShellStateHolder = mainShellStateHolder,
                    warmResumeTraceToken = warmResumeTraceToken,
                    warmResumeEpoch = warmResumeEpoch,
                    inboundProfilePayload = inboundProfilePayload,
                    onProfilePayloadHandled = { handled ->
                        if (inboundProfilePayload == handled) inboundProfilePayload = null
                    },
                    inboundNotificationTarget = inboundNotificationTarget,
                    inboundNotificationRequestId = inboundNotificationRequestId,
                    onNotificationTargetHandled = ::handleNotificationTarget,
                    inboundShareRequest = inboundShareRequest,
                    onShareRequestHandled = ::handleShareRequest,
                    inboundAppUpdateTap = inboundAppUpdateTap,
                    onAppUpdateTapHandled = { handled ->
                        if (inboundAppUpdateTap == handled) inboundAppUpdateTap = 0
                    },
                    onRequestAppUnlock = ::requestAppUnlock,
                )
            }
        }
    }

    private fun firstFrameTheme(): AppThemeMode = RuntimePolicyHooks.allowThreadDiskReads(::readPersistedThemeMode)

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
            foregroundConversationDismissal.onNotificationRouteHandled()
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

    /**
     * Keeps the themed system splash until ordinary bootstrap hands off or an
     * inbound share has a renderable local-first picker frame. Installed before
     * `super.onCreate`; this predicate takes ownership once [appState] exists.
     */
    private fun holdSplashThroughBootstrap(
        splashScreen: androidx.core.splashscreen.SplashScreen,
        installedAtMs: Long,
    ) {
        splashScreen.setKeepOnScreenCondition {
            val firstUsefulFrameReady =
                mainShellStateHolder.prepareFirstUsefulFrame(
                    phase = appState.phase,
                    activeAccountRef = appState.activeAccountRef,
                    runtimeGeneration = appState.runtimeGeneration,
                    appLockScreenVisible = appState.appLockScreenVisible,
                )
            val pendingShareFirstFrameReady =
                if (mainShellStateHolder.hasPendingShareRoute && !appState.appLockScreenVisible) {
                    val directGroupId =
                        if (appState.phase == AppPhase.Ready) {
                            mainShellStateHolder.visibleShareDirectGroupId(
                                activeAccountRef = appState.activeAccountRef,
                                runtimeGeneration = appState.runtimeGeneration,
                            )
                        } else {
                            null
                        }
                    directGroupId == null &&
                        mainShellStateHolder.visibleShareRequest != null &&
                        mainShellStateHolder.prepareSharePickerFirstFrame(
                            phase = appState.phase,
                            activeAccountRef = appState.activeAccountRef,
                            runtimeGeneration = appState.runtimeGeneration,
                            appLockScreenVisible = false,
                        )
                } else {
                    null
                }
            val retain =
                shouldRetainSystemSplash(
                    phase = appState.phase,
                    elapsedMs = SystemClock.elapsedRealtime() - installedAtMs,
                    firstUsefulFrameReady = firstUsefulFrameReady,
                    pendingShareFirstFrameReady = pendingShareFirstFrameReady,
                )
            if (!retain) {
                appState.recordStartupSystemSplashHandoff()
                schedulePeriodicWorkAfterFirstFrame()
            }
            retain
        }
    }

    /**
     * Posting from the splash-release pre-draw callback to the next animation
     * step guarantees that app-owned UI gets its first frame before the first
     * WorkManager use can initialize its database on a background thread.
     */
    private fun schedulePeriodicWorkAfterFirstFrame() {
        window.decorView.postOnAnimation {
            (application as WhiteNoiseApplication).ensurePeriodicWorkScheduled()
        }
    }

    /**
     * Route an inbound intent: a notification tap (our [NotificationNavigation.ACTION_OPEN]
     * action) becomes a navigation target; a White Noise data URI becomes a
     * profile-link payload. A dataless, non-notification intent leaves any
     * already-queued target/link intact (see [routeInboundIntent]).
     */
    private fun consumeIntent(
        intent: Intent?,
        retainPendingShareOnRecreation: Boolean = false,
    ) {
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
        val parsedShare =
            if (retainPendingShareOnRecreation && inboundShareRequest != null) {
                null
            } else {
                parseShareRequest(intent)
            }
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
        if (parsedTarget != null) {
            foregroundConversationDismissal.onNotificationRouteObserved()
            NotificationRouteTrace.startRequest(routing.notificationRequestId)
        }
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

    /** Acknowledges the Activity-delivered intent without clearing a promoted picker route. */
    private fun handleShareRequest(handled: ShareRequest) {
        mainShellStateHolder.acknowledgeInboundShareRequest(handled.requestId)
    }

    override fun onStart() {
        super.onStart()
        warmResumeEpoch += 1
        WarmResumeTrace.foregroundStarted(
            activityToken = warmResumeTraceToken,
            foregroundEpoch = warmResumeEpoch,
            activityClass = warmResumeLifecycleClass,
        )
        if (::appState.isInitialized) {
            // A stopped Activity receives onStart before onNewIntent when a
            // notification brings its existing task forward. Defer the
            // retained-conversation dismissal until onResume has observed
            // whether this foreground entry is actually routing elsewhere.
            foregroundConversationDismissal.onStart(
                hasPendingNotificationRoute = inboundNotificationTarget != null,
            )
            appState.setAppInForeground(
                foreground = true,
                dismissRetainedVisibleConversation = false,
            )
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
        WarmResumeTrace.lifecycle(warmResumeTraceToken, "activity-resumed")
        if (::appState.isInitialized) {
            if (foregroundConversationDismissal.consumeShouldDismissOnResume()) {
                appState.dismissRetainedVisibleConversationNotifications()
            }
            applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)
            if (!appState.appLockScreenVisible) releaseAppLockBackgroundSecureFlag()
        }
    }

    override fun onStop() {
        if (::appState.isInitialized) {
            retainAppLockBackgroundSecureFlagIfNeeded()
            appState.setAppInForeground(false)
        }
        WarmResumeTrace.foregroundStopped(warmResumeTraceToken, warmResumeEpoch)
        super.onStop()
    }

    override fun onDestroy() {
        WarmResumeTrace.lifecycle(warmResumeTraceToken, "activity-destroyed")
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
        if (
            BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS &&
            intent.getBooleanExtra(BENCHMARK_RECREATE_ACTIVITY_EXTRA, false)
        ) {
            setIntent(Intent(this, MainActivity::class.java))
            recreate()
            return
        }
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
                        appState.markAppUnlockSucceeded(
                            dismissRetainedVisibleConversation =
                                foregroundConversationDismissal.shouldDismissAfterUnlock(),
                        )
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
            appState.markAppUnlockFailed(AppText.Resource(R.string.app_lock_auth_unavailable))
        }
    }

    private fun appLockAuthErrorMessage(
        errorCode: Int,
        @Suppress("UNUSED_PARAMETER") errString: CharSequence,
    ): AppText =
        when (errorCode) {
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            -> AppText.Resource(R.string.app_lock_auth_cancelled)
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            -> AppText.Resource(R.string.app_lock_auth_locked)
            BiometricPrompt.ERROR_TIMEOUT -> AppText.Resource(R.string.app_lock_auth_timed_out)
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            -> AppText.Resource(R.string.app_lock_auth_unavailable)
            else -> AppText.Resource(R.string.app_lock_auth_failed)
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

/**
 * Distinguishes a plain foreground resume from a notification-owned route.
 *
 * Android can deliver the notification intent after [MainActivity.onStart], so
 * the decision is consumed only from onResume. Intents received while the
 * Activity is already resumed do not arm a later, unrelated foreground entry.
 */
internal class ForegroundConversationDismissalCoordinator {
    private var resumePending = false
    private var notificationRouteObserved = false
    private var currentForegroundNotificationOwned = false

    fun onStart(hasPendingNotificationRoute: Boolean) {
        resumePending = true
        notificationRouteObserved = hasPendingNotificationRoute
        currentForegroundNotificationOwned = hasPendingNotificationRoute
    }

    fun onNotificationRouteObserved() {
        currentForegroundNotificationOwned = true
        if (resumePending) notificationRouteObserved = true
    }

    /**
     * The route now owns the visible conversation directly. Clear only the
     * unlock guard; the pending resume decision still needs to remember that
     * this foreground entry came from a notification.
     */
    fun onNotificationRouteHandled() {
        currentForegroundNotificationOwned = false
    }

    fun consumeShouldDismissOnResume(): Boolean {
        if (!resumePending) return false
        val shouldDismiss = !notificationRouteObserved
        resumePending = false
        notificationRouteObserved = false
        return shouldDismiss
    }

    fun shouldDismissAfterUnlock(): Boolean = !currentForegroundNotificationOwned
}

internal const val MAX_RETAINED_SYSTEM_SPLASH_MILLIS = 1_500L

/**
 * The platform splash is deliberately brief for ordinary startup. An unresolved
 * inbound share instead remains protected until its picker request and local
 * recipient projection can own the first app-rendered frame. App lock,
 * onboarding, and failure phases still release to their explicit gate surface.
 */
internal fun shouldRetainSystemSplash(
    phase: AppPhase,
    elapsedMs: Long,
    firstUsefulFrameReady: Boolean = true,
    pendingShareFirstFrameReady: Boolean? = null,
): Boolean =
    when {
        pendingShareFirstFrameReady != null && phase == AppPhase.Bootstrapping -> true
        pendingShareFirstFrameReady != null && phase == AppPhase.Ready -> !pendingShareFirstFrameReady
        else ->
            elapsedMs < MAX_RETAINED_SYSTEM_SPLASH_MILLIS &&
                (phase == AppPhase.Bootstrapping || (phase == AppPhase.Ready && !firstUsefulFrameReady))
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
internal const val BENCHMARK_RECREATE_ACTIVITY_EXTRA =
    "dev.ipf.whitenoise.android.extra.BENCHMARK_RECREATE_ACTIVITY"
private const val PRE_COMPOSE_BACKGROUND_LIGHT = 0xFFECEEEE.toInt()
private const val PRE_COMPOSE_BACKGROUND_DARK = 0xFF0F1112.toInt()
