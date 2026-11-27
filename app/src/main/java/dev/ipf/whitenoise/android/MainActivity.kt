package dev.ipf.whitenoise.android

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.whitenoise.android.amber.AmberActivityCoordinator
import dev.ipf.whitenoise.android.audio.clearMicrophoneRecovery
import dev.ipf.whitenoise.android.core.DateTimeFormatState
import dev.ipf.whitenoise.android.diagnostics.AndroidFramePerformanceReporter
import dev.ipf.whitenoise.android.diagnostics.FramePerformanceCallbackGuard
import dev.ipf.whitenoise.android.notifications.NotificationInboundState
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationProfileTileRoute
import dev.ipf.whitenoise.android.notifications.NotificationTapTokens
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedShortcutTapGate
import dev.ipf.whitenoise.android.notifications.PinnedShortcutTapState
import dev.ipf.whitenoise.android.notifications.SignInAlertNavigation
import dev.ipf.whitenoise.android.notifications.inboundNotificationHandledMatchesCurrent
import dev.ipf.whitenoise.android.notifications.notificationTapLifecycle
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.notifications.startNotificationRouteTrace
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.parseShareRequest
import dev.ipf.whitenoise.android.state.APP_LOCK_ALLOWED_AUTHENTICATORS
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.ChatScreenshotPreferences
import dev.ipf.whitenoise.android.state.HostPerformanceAttemptSlot
import dev.ipf.whitenoise.android.state.HostPerformanceRuntimeOwner
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.actionColorArgb
import dev.ipf.whitenoise.android.state.shouldReattachAppUnlockPrompt
import dev.ipf.whitenoise.android.state.signInAlerts
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.common.ProvideWindowLayout
import dev.ipf.whitenoise.android.ui.common.releaseSecureFlag
import dev.ipf.whitenoise.android.ui.common.retainSecureFlag
import dev.ipf.whitenoise.android.ui.keyboard.appLockShortcutRouter
import dev.ipf.whitenoise.android.ui.keyboard.installHardwareShortcuts
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeLifecycleClass
import dev.ipf.whitenoise.android.ui.navigation.warmResumeActivityLifecycleClass
import dev.ipf.whitenoise.android.ui.onboarding.setup.recordAccountSetupBackground
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateNavigation
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher

class MainActivity : AppCompatActivity() {
    private val activityCreatedAtElapsedMs = SystemClock.elapsedRealtime()
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private val notificationInboundState: NotificationInboundState by viewModels()
    private var inboundNotificationTarget: NotificationTarget?
        get() = notificationInboundState.target
        set(value) {
            notificationInboundState.target = value
        }
    private var inboundNotificationRequestId: Long
        get() = notificationInboundState.requestId
        set(value) {
            notificationInboundState.requestId = value
        }
    private val notificationProfileTileRoute = NotificationProfileTileRoute()
    private var inboundAppUpdateTap by mutableIntStateOf(0)

    /** Pending launcher capabilities belong to this task's retained owner, never saved instance state. */
    private val pendingPinTapState: PinnedShortcutTapState by viewModels()

    /** Holds pin taps while the App Lock decision loads and parses them under the decided state. */
    private val pinTapGate by lazy { PinnedShortcutTapGate(appState, pendingPinTapState) }
    private lateinit var appUnlockPrompt: BiometricPrompt
    private val appUnlockCryptoGate by lazy(::AppUnlockCryptoGate)
    private var attachedAppUnlockSessionId: Long? = null
    private val appUnlockHostId = appUnlockHostIds.incrementAndGet()
    private val appUnlockPromptHostState: AppUnlockPromptHostState by viewModels()
    private var attachedAppUnlockHostId: Long? = null
    private var appLockBackgroundSecureFlagRetained = false
    private var recentsPreferenceSecureFlagRetained = false
    private val foregroundConversationDismissal = ForegroundConversationDismissalCoordinator()
    private val allowChatScreenshotsCallback: (Boolean) -> Unit = { enabled ->
        applyRecentsPreferenceSecureFlag(allowChatScreenshots = enabled)
    }
    private lateinit var notificationTapTokens: NotificationTapTokens
    private lateinit var appState: WhiteNoiseAppState

    // Hardware keyboard shortcuts (#4005), held while App Lock is shown or still deciding.
    internal val hardwareShortcuts = appLockShortcutRouter { appState.appLock }
    private lateinit var amberSignerLauncher: ActivityResultLauncher<Intent>
    private var warmResumeTraceToken = 0
    private var warmResumeEpoch by mutableIntStateOf(0)
    private var warmResumeLifecycleClass = WarmResumeLifecycleClass.ColdProcessStart
    private var mainShellHolderCreatedForActivity = false
    private var splashHostReadyRecorded = false
    private var fontsHostReadyRecorded = false
    private val foregroundHostReady = HostPerformanceAttemptSlot()
    private var framePerformanceReporter: AndroidFramePerformanceReporter? = null
    private var framePerformanceCallbackGuard: FramePerformanceCallbackGuard<HostPerformanceRuntimeOwner>? = null
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
        installHardwareShortcuts(window, hardwareShortcuts)
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
        pinTapGate.replayWhenDecided(lifecycleScope, ::consumeIntent)
        installAppUnlockPrompt()
        enableEdgeToEdge()
        window.applyPreComposeWindowBackground(appState.themeMode, initialSystemDarkTheme)
        installComposeContent()
        appState.recordHostPerformanceSince(
            HostPerformanceOperationFfi.WINDOW_INIT,
            activityCreatedAtElapsedMs,
        )
        startFramePerformanceReporter()
    }

    /** Starts frame sampling with one Activity lifetime and one lazily captured runtime owner. */
    private fun startFramePerformanceReporter() {
        val frameCallbackGuard =
            FramePerformanceCallbackGuard(
                captureOwner = appState::captureHostPerformanceRuntimeOwner,
                isCurrent = appState::ownsHostPerformanceRuntimeOwner,
            ).also { framePerformanceCallbackGuard = it }
        framePerformanceReporter =
            AndroidFramePerformanceReporter(window = window, emit = { operation, durationMs ->
                window.decorView.post {
                    frameCallbackGuard.runIfCurrent { owner ->
                        appState.recordHostPerformance(
                            owner = owner,
                            operation = operation,
                            durationMs = durationMs,
                            outcome = HostPerformanceOutcomeFfi.SUCCESS,
                        )
                    }
                }
            }).also(AndroidFramePerformanceReporter::start)
    }

    private fun syncSystemBarsAndHostReadiness(darkTheme: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        if (!fontsHostReadyRecorded) {
            fontsHostReadyRecorded = true
            appState.recordHostPerformanceSince(HostPerformanceOperationFfi.FONTS_INIT, activityCreatedAtElapsedMs)
        }
        if (appState.phase == AppPhase.Ready) {
            foregroundHostReady.success()
        }
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
                window.applyPreComposeWindowBackground(state.themeMode, systemDarkTheme)
                onDispose { }
            }
            SideEffect { syncSystemBarsAndHostReadiness(darkTheme) }
            WhiteNoiseTheme(
                darkTheme = darkTheme,
                amoled = state.themeMode.isAmoled,
                accentColorArgb = state.actionColorArgb(bubbleTheme),
                fontScale = state.fontScale.factor,
                appFont = state.appFont,
            ) {
                ProvideWindowLayout {
                    notificationProfileTileRoute.Content(state)
                    WhiteNoiseApp(
                        appState = state,
                        mainShellStateHolder = mainShellStateHolder,
                        warmResumeTraceToken = warmResumeTraceToken,
                        warmResumeEpoch = warmResumeEpoch,
                        inboundProfilePayload = inboundProfilePayload,
                        onProfilePayloadHandled = { if (inboundProfilePayload == it) inboundProfilePayload = null },
                        inboundNotificationTarget = inboundNotificationTarget,
                        inboundNotificationRequestId = inboundNotificationRequestId,
                        onNotificationTargetHandled = ::handleNotificationTarget,
                        inboundShareRequest = inboundShareRequest,
                        onShareRequestHandled = ::handleShareRequest,
                        onChatLinkResolved = { handled, target ->
                            if (handled != null && intent?.dataString == handled) {
                                setIntent(Intent(this@MainActivity, MainActivity::class.java))
                            }
                            if (target != null) applyInboundRouting(target, null, null, warmResumeLifecycleClass)
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
    }

    private fun firstFrameTheme(): AppThemeMode = RuntimePolicyHooks.allowThreadDiskReads(this::readPersistedThemeMode)

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
                    appLockScreenVisible = appState.appLock.screenVisible,
                )
            val pendingShareFirstFrameReady =
                if (mainShellStateHolder.hasPendingShareRoute && !appState.appLock.screenVisible) {
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
                if (!splashHostReadyRecorded) {
                    splashHostReadyRecorded = true
                    appState.recordHostPerformanceSince(
                        HostPerformanceOperationFfi.SPLASH_READY,
                        installedAtMs,
                    )
                }
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
        lifecycleClass: WarmResumeLifecycleClass = warmResumeLifecycleClass,
    ) {
        val signInAlertAccount = SignInAlertNavigation.accountRef(intent, notificationTapTokens)
        val opensProfileChooser = signInAlertAccount == null && notificationProfileTileRoute.accept(intent)
        if (signInAlertAccount != null) {
            pinTapGate.supersede()
            appState.signInAlerts.requestDevices(signInAlertAccount)
        }
        if (signInAlertAccount != null || opensProfileChooser || AppUpdateNavigation.isUpdateTap(intent)) {
            if (signInAlertAccount == null && !opensProfileChooser) {
                pinTapGate.supersede()
                inboundAppUpdateTap += 1
            }
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        // Retained state preserves an unresolved tap across recreation; clear the launch intent to avoid replay
        // after the gate has consumed or superseded it.
        if (pinTapGate.hold(intent)) {
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val pinTarget = pinTapGate.target(this, intent)
        val retainNotification = retainPendingShareOnRecreation && inboundNotificationTarget != null
        val parsedTarget =
            if (!retainNotification) {
                pinTarget ?: parseNotificationTarget(intent, retainPendingShareOnRecreation, notificationTapTokens)
            } else {
                null
            }
        val parsedShare =
            if (retainPendingShareOnRecreation && inboundShareRequest != null) {
                null
            } else {
                parseShareRequest(intent)
            }
        val profileData = intent?.dataString.takeUnless { retainNotification }
        pinTapGate.supersedeForRoute(parsedTarget != null, parsedShare != null, profileData)
        applyInboundRouting(parsedTarget, parsedShare, profileData, lifecycleClass)
        if (parsedTarget != null || parsedShare != null) {
            // Notification taps and share intents are one-shot navigation requests.
            // Replace the stored intent after parsing so activity recreation cannot
            // replay the same target after the UI has already consumed it.
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    // Merges a parsed inbound route into the pending routes; a notification target also starts its route trace. A chat
    // link (#3658) opens through here as a notification target, sharing account switching, app lock and cold/warm
    // handling; its stored intent is replaced first so activity recreation cannot resolve the link again.
    private fun applyInboundRouting(
        target: NotificationTarget?,
        share: ShareRequest?,
        data: String?,
        lifecycleClass: WarmResumeLifecycleClass,
    ) {
        // Revoke before ON_RESUME: Compose can still show the old chat when this route arrives.
        if (target != null || share != null || data != null) appState.conversationDictation.clearMicrophoneRecovery()
        val routing = notificationInboundState.route(target, share, data, inboundProfilePayload, inboundShareRequest)
        if (target != null) {
            foregroundConversationDismissal.onNotificationRouteObserved()
            appState.startNotificationRouteTrace(routing.notificationRequestId, lifecycleClass, target)
        }
        inboundProfilePayload = routing.profilePayload
        inboundShareRequest = routing.shareRequest
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
            foregroundHostReady.replace(
                appState.beginHostPerformance(HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY),
            )
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
            // A warm process already has usable local content and may not recompose merely because
            // the Activity restarted, so settle directly at the lifecycle boundary in that case.
            foregroundHostReady.successIf(appState.phase == AppPhase.Ready)
            applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)
            if (!appState.appLock.screenVisible) releaseAppLockBackgroundSecureFlag()
        }
    }

    override fun onPause() {
        retainAppLockBackgroundSecureFlagIfNeeded()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        WarmResumeTrace.lifecycle(warmResumeTraceToken, "activity-resumed")
        // The 12/24-hour switch and phone region can change while the app is in the background.
        DateTimeFormatState.refreshSystem(this)
        if (::appState.isInitialized) {
            appState.appLock.sessions.clearForegroundReturn()
            if (foregroundConversationDismissal.consumeShouldDismissOnResume()) {
                appState.dismissRetainedVisibleConversationNotifications()
            }
            applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)
            if (!appState.appLock.screenVisible) releaseAppLockBackgroundSecureFlag()
        }
    }

    override fun onStop() {
        if (::appState.isInitialized) {
            foregroundHostReady.cancel()
            retainAppLockBackgroundSecureFlagIfNeeded()
            if (!isChangingConfigurations) appState.recordAccountSetupBackground()
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
        framePerformanceCallbackGuard?.invalidate()
        framePerformanceCallbackGuard = null
        framePerformanceReporter?.close()
        framePerformanceReporter = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (
            BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS &&
            intent.action != NotificationNavigation.ACTION_OPEN &&
            intent.getBooleanExtra(BENCHMARK_RECREATE_ACTIVITY_EXTRA, false)
        ) {
            setIntent(Intent(this, MainActivity::class.java))
            recreate()
            return
        }
        setIntent(intent)
        consumeIntent(
            intent,
            lifecycleClass = notificationTapLifecycle(warmResumeLifecycleClass, deliveredToRunningActivity = true),
        )
    }

    private fun requestAppUnlock() {
        if (!::appState.isInitialized || !appState.appLock.screenVisible) return
        val sessionId = appState.appLock.sessions.activeSessionId
        // A replacement Activity constructs BiometricPrompt early so AndroidX
        // reconnects this callback to its retained prompt. Never call
        // authenticate() a second time for that same logical session.
        val alreadyAttached =
            sessionId != null &&
                attachedAppUnlockSessionId == sessionId &&
                attachedAppUnlockHostId == appUnlockHostId &&
                appState.appLock.sessions.owns(sessionId, appUnlockHostId)
        if (sessionId == null || alreadyAttached) return
        if (
            appState.appLock.sessions.attachHost(
                sessionId = sessionId,
                hostId = appUnlockHostId,
                replaceExistingHost = false,
            )
        ) {
            launchAppUnlockPrompt(sessionId)
        }
    }

    private fun launchAppUnlockPrompt(sessionId: Long) {
        attachedAppUnlockSessionId = sessionId
        attachedAppUnlockHostId = appUnlockHostId
        appUnlockPromptHostState.sessionId = sessionId
        appUnlockPromptHostState.expectedCipher = null
        // Bind this callback instance to this exact logical session and host.
        // AndroidX may rebind its retained prompt to a replacement Activity,
        // so AppState remains the authority for accepting terminal callbacks.
        appUnlockPrompt = createAppUnlockPrompt(sessionId = sessionId, hostId = appUnlockHostId)
        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setSubtitle(getString(R.string.app_lock_prompt_subtitle))
                .setAllowedAuthenticators(APP_LOCK_ALLOWED_AUTHENTICATORS)
                .build()
        traceAppUnlock(sessionId, "prompt-launched")
        runCatching {
            val cryptoObject = appUnlockCryptoGate.createCryptoObject()
            appUnlockPromptHostState.expectedCipher = cryptoObject.cipher
            appUnlockPrompt.authenticate(promptInfo, cryptoObject)
        }.onFailure {
            if (
                appState.appLock.sessions.terminate(
                    sessionId = sessionId,
                    hostId = appUnlockHostId,
                )
            ) {
                appUnlockPromptHostState.clear()
                appState.appLock.markUnlockFailed(AppText.Resource(R.string.app_lock_auth_unavailable))
                attachedAppUnlockSessionId = null
                attachedAppUnlockHostId = null
                traceAppUnlock(sessionId, "prompt-launch-error")
            }
        }
    }

    private fun installAppUnlockPrompt() {
        // AndroidX reconnects through an Activity-scoped ViewModel. Our paired
        // ViewModel survives the same configuration-change boundary; a fresh
        // Activity receives no matching token, so it abandons only the dead
        // logical session and launches one new prompt under the opaque cover.
        var abandonedSession = false
        val reattachedSessionId =
            appState.appLock.sessions.activeSessionId?.takeIf { sessionId ->
                if (
                    shouldReattachAppUnlockPrompt(
                        hasActiveSession = true,
                        hasAttachedHost = appState.appLock.sessions.hasAttachedHost,
                        retainedHostSessionMatches = appUnlockPromptHostState.sessionId == sessionId,
                    ) &&
                    appState.appLock.sessions.attachHost(
                        sessionId = sessionId,
                        hostId = appUnlockHostId,
                        replaceExistingHost = true,
                    )
                ) {
                    attachedAppUnlockSessionId = sessionId
                    attachedAppUnlockHostId = appUnlockHostId
                    true
                } else {
                    appState.appLock.sessions.clear()
                    appUnlockPromptHostState.clear()
                    abandonedSession = true
                    traceAppUnlock(sessionId, "prompt-not-retained")
                    false
                }
            }
        appUnlockPrompt =
            createAppUnlockPrompt(
                sessionId = reattachedSessionId,
                hostId = appUnlockHostId.takeIf { reattachedSessionId != null },
            )
        reattachedSessionId?.let { sessionId -> traceAppUnlock(sessionId, "prompt-reattached") }
        if (abandonedSession) {
            appState.appLock.requestUnlock()
            requestAppUnlock()
        }
    }

    private fun createAppUnlockPrompt(
        sessionId: Long?,
        hostId: Long?,
    ): BiometricPrompt =
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (sessionId == null || hostId == null) return
                    if (!appState.appLock.sessions.owns(sessionId, hostId)) {
                        traceAppUnlock(sessionId, "stale-success-ignored")
                    } else {
                        val expectedCipher = appUnlockPromptHostState.expectedCipher
                        appUnlockPromptHostState.expectedCipher = null
                        if (!appUnlockCryptoGate.verify(result, expectedCipher)) {
                            if (
                                appState.appLock.sessions.terminate(
                                    sessionId = sessionId,
                                    hostId = hostId,
                                )
                            ) {
                                appUnlockPromptHostState.clear()
                                appState.appLock.markUnlockFailed(AppText.Resource(R.string.app_lock_auth_failed))
                                attachedAppUnlockSessionId = null
                                attachedAppUnlockHostId = null
                                traceAppUnlock(sessionId, "prompt-crypto-failed")
                            }
                        } else {
                            val accepted =
                                appState.appLock.sessions.complete(
                                    sessionId = sessionId,
                                    hostId = hostId,
                                    foregroundReturnExpiresAtElapsedRealtime =
                                        foregroundReturnExpiryElapsedRealtime(
                                            nowElapsedRealtime = SystemClock.elapsedRealtime(),
                                            hostResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                                        ),
                                )
                            if (accepted) {
                                appUnlockPromptHostState.clear()
                                appState.appLock.markUnlockSucceeded(
                                    dismissRetainedVisibleConversation =
                                        foregroundConversationDismissal.shouldDismissAfterUnlock(),
                                )
                                attachedAppUnlockSessionId = null
                                attachedAppUnlockHostId = null
                                traceAppUnlock(sessionId, "prompt-succeeded")
                                releaseAppLockBackgroundSecureFlag()
                            } else {
                                traceAppUnlock(sessionId, "stale-success-ignored")
                            }
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    sessionId ?: return
                    hostId ?: return
                    if (
                        appState.appLock.sessions.owns(sessionId, hostId)
                    ) {
                        appState.appLock.markUnlockFailed(AppText.Resource(R.string.app_lock_auth_failed))
                        traceAppUnlock(sessionId, "prompt-attempt-failed")
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    sessionId ?: return
                    hostId ?: return
                    if (
                        appState.appLock.sessions.terminate(
                            sessionId = sessionId,
                            hostId = hostId,
                        )
                    ) {
                        appUnlockPromptHostState.clear()
                        appState.appLock.markUnlockFailed(appLockAuthErrorMessage(errorCode, errString))
                        attachedAppUnlockSessionId = null
                        attachedAppUnlockHostId = null
                        traceAppUnlock(sessionId, "prompt-terminated")
                    } else {
                        traceAppUnlock(sessionId, "stale-error-ignored")
                    }
                }
            },
        )

    private fun traceAppUnlock(
        sessionId: Long,
        event: String,
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return
        Log.i(
            "WNAppUnlock",
            "activity=$warmResumeTraceToken session=$sessionId event=$event " +
                "lifecycle_class=${warmResumeLifecycleClass.name.lowercase()}",
        )
    }

    private fun foregroundReturnExpiryElapsedRealtime(
        nowElapsedRealtime: Long,
        hostResumed: Boolean,
    ): Long? {
        if (hostResumed) return null
        return nowElapsedRealtime.coerceAtMost(Long.MAX_VALUE - APP_UNLOCK_FOREGROUND_HANDOFF_MILLIS) +
            APP_UNLOCK_FOREGROUND_HANDOFF_MILLIS
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
        if (!::appState.isInitialized || !appState.appLock.shouldSecureWindowWhileBackgrounded()) return
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
}

internal const val MAX_RETAINED_SYSTEM_SPLASH_MILLIS = 300L

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

private val Configuration.isNightModeActive: Boolean
    get() = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

internal const val BENCHMARK_RECREATE_ACTIVITY_EXTRA =
    "dev.ipf.whitenoise.android.extra.BENCHMARK_RECREATE_ACTIVITY"
private val appUnlockHostIds = AtomicLong()
private const val APP_UNLOCK_FOREGROUND_HANDOFF_MILLIS = 5_000L

internal class AppUnlockPromptHostState : ViewModel() {
    var sessionId: Long? = null
    var expectedCipher: Cipher? = null

    fun clear() {
        sessionId = null
        expectedCipher = null
    }
}

private fun parseNotificationTarget(
    intent: Intent?,
    retainPendingShareOnRecreation: Boolean,
    tokens: NotificationTapTokens,
): NotificationTarget? =
    NotificationNavigation.parse(
        intent,
        importReplyDraft = !retainPendingShareOnRecreation,
        isTrustedTargetSignature = tokens::isValidTarget,
    ) { notificationKey, tapToken ->
        tokens.isValid(notificationKey, tapToken)
    }
