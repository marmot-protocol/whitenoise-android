package dev.ipf.whitenoise.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.speech.RecognizerIntent
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.conversationDictationRecognitionActivityIntent
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.WarmResumeRenderedSurface
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AppLockScreen
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.InlineConfirmationNotice
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarContentInset
import dev.ipf.whitenoise.android.ui.common.StartupLoadingScreen
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WarmResumeUsefulSurface
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationFloatingControl
import dev.ipf.whitenoise.android.ui.conversation.media.SHARED_MEDIA_MAX_AGE_MS
import dev.ipf.whitenoise.android.ui.conversation.media.sweepStaleSharedMedia
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardOperationStatusHost
import dev.ipf.whitenoise.android.ui.navigation.MainShell
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.navigation.PrepareMainShellFirstFrame
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeFirstUsefulSurface
import dev.ipf.whitenoise.android.ui.navigation.shouldComposeProtectedMainShell
import dev.ipf.whitenoise.android.ui.navigation.warmResumeFirstUsefulSurface
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.settings.WipeOutcomeSheet
import dev.ipf.whitenoise.android.ui.settings.WipeProgressSheet
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val TRANSIENT_NOTICE_DURATION_MILLIS = 2_000L
internal const val GLOBAL_TRANSIENT_NOTICE_TAG = "global-transient-notice"

/** Keeps exactly one dictation control visible without duplicating the origin composer. */
internal fun shouldShowConversationDictationFloatingControl(
    state: ConversationDictationState,
    originVisible: Boolean,
    appLockScreenVisible: Boolean,
): Boolean =
    !appLockScreenVisible &&
        !originVisible &&
        when (state) {
            is ConversationDictationState.Starting,
            is ConversationDictationState.CheckingProvider,
            is ConversationDictationState.Listening,
            is ConversationDictationState.Processing,
            is ConversationDictationState.ProviderActivityRequired,
            is ConversationDictationState.ProviderActivityActive,
            is ConversationDictationState.Failed,
            is ConversationDictationState.ReviewRequired,
            -> true
            ConversationDictationState.Idle,
            is ConversationDictationState.DisclosureRequired,
            is ConversationDictationState.PermissionRequired,
            -> false
        }

@Composable
@Suppress("FunctionNaming")
internal fun TransientNoticeTimeoutEffect(
    notice: TransientNotice?,
    onClear: (TransientNotice) -> Unit,
) {
    LaunchedEffect(notice) {
        notice ?: return@LaunchedEffect
        delay(TRANSIENT_NOTICE_DURATION_MILLIS)
        onClear(notice)
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun GlobalTransientNotice(
    notice: TransientNotice?,
    modifier: Modifier = Modifier,
) {
    notice?.takeIf { it.conversation == null }?.let {
        InlineConfirmationNotice(
            notice = it,
            modifier = modifier.navigationBarsPadding().testTag(GLOBAL_TRANSIENT_NOTICE_TAG),
        )
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun ShellTransientNoticeLayout(
    notice: TransientNotice?,
    modifier: Modifier = Modifier,
    persistentTopContent: @Composable () -> Unit = {},
    persistentTopContentConsumesStatusBars: Boolean = false,
    persistentBottomContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        persistentTopContent()
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (persistentTopContentConsumesStatusBars) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
        persistentBottomContent()
        GlobalTransientNotice(notice)
    }
}

/** Owns top-level privacy gates and records the first app-rendered useful surface. */
@Composable
@Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
) // App-level Compose orchestration keeps lifecycle effects and privacy routing in one owner.
internal fun WhiteNoiseApp(
    appState: WhiteNoiseAppState,
    mainShellStateHolder: MainShellStateHolder,
    warmResumeTraceToken: Int,
    warmResumeEpoch: Int,
    inboundProfilePayload: String? = null,
    onProfilePayloadHandled: (String) -> Unit = {},
    inboundNotificationTarget: NotificationTarget? = null,
    inboundNotificationRequestId: Long = 0L,
    onNotificationTargetHandled: (NotificationTarget, Long) -> Unit = { _, _ -> },
    inboundShareRequest: ShareRequest? = null,
    onShareRequestHandled: (ShareRequest) -> Unit = {},
    inboundAppUpdateTap: Int = 0,
    onAppUpdateTapHandled: (Int) -> Unit = {},
    onRequestAppUnlock: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Mutable bottom-chrome inset so screens further down the tree
    // (e.g. ConversationScreen) can push the snackbar above their
    // composer. Owned here so the host — which lives at this level —
    // can read it; child screens mutate via [LocalSnackbarBottomInset].
    val snackbarBottomInset = remember { mutableStateOf(0.dp) }
    val snackbarContentInset = remember { mutableStateOf(0.dp) }
    val forwardOperationVisible by
        remember(appState) {
            appState.activeForwardOperation.map { it != null }.distinctUntilChanged()
        }.collectAsState(initial = false)
    val toast = appState.toast
    val transientNotice = appState.transientNotice
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val dictation = appState.conversationDictation
    val dictationState = dictation.state
    val dictationPermissionRequestId = dictation.permissionRequestId
    val dictationProviderActivityRequestId = dictation.providerActivityRequestId
    val dictationOriginVisible =
        dictationState.target?.let { target ->
            appState.isConversationDictationOriginVisible(target.accountRef, target.groupIdHex)
        } == true
    val density = LocalDensity.current
    var firstUsefulFrameRecorded by remember(warmResumeTraceToken, warmResumeEpoch) { mutableStateOf(false) }
    val visibleShareRequest = mainShellStateHolder.visibleShareRequest
    val visibleShareDirectGroupId =
        if (appState.phase == AppPhase.Ready && visibleShareRequest != null) {
            mainShellStateHolder.visibleShareDirectGroupId(
                activeAccountRef = appState.activeAccountRef,
                runtimeGeneration = appState.runtimeGeneration,
            )
        } else {
            null
        }
    val visiblePickerRequest = visibleShareRequest.takeUnless { visibleShareDirectGroupId != null }
    val inboundRoutePending =
        inboundNotificationTarget != null ||
            inboundProfilePayload != null ||
            mainShellStateHolder.hasPendingShareRoute ||
            inboundAppUpdateTap != 0
    val dictationImeVisible =
        WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val firstUsefulFrameReady =
        mainShellStateHolder.prepareFirstUsefulFrame(
            phase = appState.phase,
            activeAccountRef = appState.activeAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
            appLockScreenVisible = appState.appLockScreenVisible,
        )
    val localProjectionAvailable =
        mainShellStateHolder.localProjectionAvailable(
            activeAccountRef = appState.activeAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
        )
    val lockDecision =
        when {
            appState.appUnlockEvaluationPending -> "pending"
            appState.appLockScreenVisible -> "locked"
            else -> "unlocked"
        }
    val dictationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val permanentlyDenied =
                !granted &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
                    } == true
            dictation.onPermissionResult(granted, permanentlyDenied)
        }
    val dictationProviderActivityLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                dictation.onProviderActivityResult(
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull(),
                )
            } else {
                dictation.onProviderActivityCancelled()
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            if (granted) {
                appState.launchMutation { appState.enableDefaultNotificationsIfReady() }
            } else {
                appState.markDefaultNotificationsEnableAttempted()
            }
        }

    LaunchedEffect(Unit) {
        appState.bootstrap()
        // Stale share-temp janitor. Runs once per process start, off the
        // main thread because directory walks on cold cache can take a
        // moment. Files in `shared_media` from earlier sessions that
        // any external reader has long since finished with are deleted.
        withContext(Dispatchers.IO) {
            sweepStaleSharedMedia(context, SHARED_MEDIA_MAX_AGE_MS)
        }
    }
    LaunchedEffect(
        appState.phase,
        appState.runtimeGeneration,
        lockDecision,
        mainShellStateHolder.hasSavedConversationRoute,
        localProjectionAvailable,
    ) {
        WarmResumeTrace.restorationState(
            activityToken = warmResumeTraceToken,
            runtimeGeneration = appState.runtimeGeneration,
            bootstrapLocalReady = appState.phase == AppPhase.Ready,
            lockDecision = lockDecision,
            savedRouteAvailable = mainShellStateHolder.hasSavedConversationRoute,
            localProjectionAvailable = localProjectionAvailable,
        )
    }
    LaunchedEffect(
        appState.phase,
        appState.activeAccountRef,
        appState.localNotificationPermissionGranted,
        appState.backgroundConnectionEnabled,
        appState.localNotificationSettings?.localNotificationsEnabled,
        appState.runtimeGeneration,
        appState.appLockScreenVisible,
    ) {
        if (appState.phase != AppPhase.Ready || appState.appLockScreenVisible) return@LaunchedEffect
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
        if (appState.shouldRequestDefaultNotificationPermission()) {
            appState.markDefaultNotificationPermissionPromptLaunched()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            appState.enableDefaultNotificationsIfReady()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(
                ToastSnackbarVisuals(
                    message = listOfNotNull(toast.title.resolve(context), toast.detail?.resolve(context)).joinToString("\n"),
                    copyable = toast.copyable,
                    tier = toast.tier,
                    copyText = toast.diagnosticReport,
                ),
            )
            appState.clearToast()
        }
    }
    TransientNoticeTimeoutEffect(transientNotice, appState::clearTransientNotice)
    LaunchedEffect(inboundProfilePayload, appState.phase) {
        val payload = inboundProfilePayload ?: return@LaunchedEffect
        if (appState.phase == AppPhase.Ready && appState.presentProfilePayload(payload)) {
            onProfilePayloadHandled(payload)
        }
    }
    LaunchedEffect(
        appState.appLockScreenVisible,
        appState.appUnlockPromptRequestId,
        appState.appUnlockEvaluationPending,
    ) {
        // While the unlock-timestamp evaluation is pending, the scrim secures
        // the UI but the biometric sheet waits for the real decision.
        if (appState.appLockScreenVisible && !appState.appUnlockEvaluationPending) onRequestAppUnlock()
    }
    LaunchedEffect(dictationPermissionRequestId) {
        if (dictationPermissionRequestId > 0L && dictation.state is ConversationDictationState.PermissionRequired) {
            dictationPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(dictationProviderActivityRequestId, dictationImeVisible) {
        if (dictationProviderActivityRequestId > 0L && !dictationImeVisible) {
            // Let the bounded readiness state draw before provider UI backgrounds White Noise.
            withFrameNanos { }
            if (dictation.beginProviderActivityLaunch(dictationProviderActivityRequestId)) {
                runCatching {
                    dictationProviderActivityLauncher.launch(conversationDictationRecognitionActivityIntent())
                }.onFailure {
                    dictation.onProviderActivityLaunchFailed()
                }
            }
        }
    }
    LaunchedEffect(appState.appLockScreenVisible) {
        if (appState.appLockScreenVisible) {
            dictation.cancel()
        }
    }

    // Privacy hardening (#405): when "Force incognito keyboard" is on, wrap the
    // whole app UI so every descendant text field requests incognito mode from
    // the IME (no learning / suggestion history / cloud sync of typed content).
    IncognitoKeyboardScope(enabled = appState.forceIncognitoKeyboard) {
        CompositionLocalProvider(
            LocalSnackbarBottomInset provides snackbarBottomInset,
            LocalSnackbarContentInset provides snackbarContentInset,
        ) {
            Scaffold(
                modifier =
                    Modifier
                        .performanceTestTag("${PerformanceTestTags.ACTIVITY_INSTANCE_PREFIX}$warmResumeTraceToken")
                        .exposePerformanceTestTags(),
                contentWindowInsets = WindowInsets(0.dp),
                snackbarHost = { WhiteNoiseSnackbarHost(snackbarHostState) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (appState.appLockScreenVisible) {
                        // Privacy boundary: the protected shell is not composed
                        // underneath the lock. Its Activity-scoped controllers
                        // remain alive and may refresh the local projection only.
                        if (appState.phase == AppPhase.Ready) {
                            PrepareMainShellFirstFrame(appState, mainShellStateHolder)
                        }
                        LaunchedEffect(warmResumeTraceToken, warmResumeEpoch) {
                            if (!firstUsefulFrameRecorded && warmResumeEpoch > 0) {
                                withFrameNanos { }
                                WarmResumeTrace.firstUsefulFrame(
                                    activityToken = warmResumeTraceToken,
                                    foregroundEpoch = warmResumeEpoch,
                                    surface = WarmResumeFirstUsefulSurface.AppLock,
                                    localSnapshotReady = false,
                                )
                                firstUsefulFrameRecorded = true
                            }
                        }
                        WarmResumeFrameSurface(
                            activityToken = warmResumeTraceToken,
                            foregroundEpoch = warmResumeEpoch,
                            surface = WarmResumeRenderedSurface.AppLock,
                        ) {
                            WarmResumeUsefulSurface {
                                AppLockScreen(
                                    error = appState.appUnlockError,
                                    onRetry = { appState.requestAppUnlock() },
                                )
                            }
                        }
                    } else {
                        AppSelfUpdateDialog(appState = appState)
                        when (val phase = appState.phase) {
                            AppPhase.Bootstrapping ->
                                WarmResumeFrameSurface(
                                    activityToken = warmResumeTraceToken,
                                    foregroundEpoch = warmResumeEpoch,
                                    surface = WarmResumeRenderedSurface.StartupLoading,
                                ) {
                                    StartupLoadingScreen()
                                }
                            AppPhase.Onboarding ->
                                WarmResumeFrameSurface(
                                    activityToken = warmResumeTraceToken,
                                    foregroundEpoch = warmResumeEpoch,
                                    surface = WarmResumeRenderedSurface.Onboarding,
                                ) {
                                    WarmResumeUsefulSurface { OnboardingScreen(appState) }
                                }
                            AppPhase.Ready -> {
                                val firstUsefulSurface =
                                    warmResumeFirstUsefulSurface(
                                        appLockScreenVisible = false,
                                        inboundRoutePending = inboundRoutePending,
                                        shellReady = firstUsefulFrameReady,
                                    )
                                LaunchedEffect(firstUsefulSurface, warmResumeTraceToken, warmResumeEpoch) {
                                    val foregroundCanRecord =
                                        !firstUsefulFrameRecorded && warmResumeEpoch > 0
                                    val inboundFrameCanRecord =
                                        !inboundRoutePending || visibleShareRequest != null
                                    if (
                                        foregroundCanRecord &&
                                        inboundFrameCanRecord &&
                                        firstUsefulSurface != WarmResumeFirstUsefulSurface.Startup
                                    ) {
                                        withFrameNanos { }
                                        WarmResumeTrace.firstUsefulFrame(
                                            activityToken = warmResumeTraceToken,
                                            foregroundEpoch = warmResumeEpoch,
                                            surface = firstUsefulSurface,
                                            localSnapshotReady = firstUsefulFrameReady,
                                        )
                                        firstUsefulFrameRecorded = true
                                    }
                                }
                                if (inboundProfilePayload != null) {
                                    PrepareMainShellFirstFrame(appState, mainShellStateHolder)
                                    WarmResumeFrameSurface(
                                        activityToken = warmResumeTraceToken,
                                        foregroundEpoch = warmResumeEpoch,
                                        surface = WarmResumeRenderedSurface.FullScreenLoading,
                                    ) {
                                        LoadingScreen()
                                    }
                                } else if (!shouldComposeProtectedMainShell(firstUsefulSurface)) {
                                    PrepareMainShellFirstFrame(appState, mainShellStateHolder)
                                    WarmResumeFrameSurface(
                                        activityToken = warmResumeTraceToken,
                                        foregroundEpoch = warmResumeEpoch,
                                        surface = WarmResumeRenderedSurface.StartupLoading,
                                    ) {
                                        StartupLoadingScreen()
                                    }
                                } else {
                                    val renderedSurface =
                                        when {
                                            visiblePickerRequest != null -> WarmResumeRenderedSurface.SharePicker
                                            inboundRoutePending -> WarmResumeRenderedSurface.InboundRoute
                                            mainShellStateHolder.selectedChat.value != null ->
                                                WarmResumeRenderedSurface.Conversation
                                            else -> WarmResumeRenderedSurface.ChatList
                                        }
                                    WarmResumeFrameSurface(
                                        activityToken = warmResumeTraceToken,
                                        foregroundEpoch = warmResumeEpoch,
                                        surface = renderedSurface,
                                    ) {
                                        WarmResumeUsefulSurface {
                                            ShellTransientNoticeLayout(
                                                notice = transientNotice,
                                                persistentTopContent = { ForwardOperationStatusHost(appState) },
                                                persistentTopContentConsumesStatusBars = forwardOperationVisible,
                                            ) {
                                                MainShell(
                                                    appState = appState,
                                                    stateHolder = mainShellStateHolder,
                                                    inboundNotificationTarget = inboundNotificationTarget,
                                                    inboundNotificationRequestId = inboundNotificationRequestId,
                                                    onNotificationTargetHandled = onNotificationTargetHandled,
                                                    inboundShareRequest = inboundShareRequest,
                                                    onShareRequestHandled = onShareRequestHandled,
                                                    inboundAppUpdateTap = inboundAppUpdateTap,
                                                    onAppUpdateTapHandled = onAppUpdateTapHandled,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is AppPhase.Failed ->
                                WarmResumeFrameSurface(
                                    activityToken = warmResumeTraceToken,
                                    foregroundEpoch = warmResumeEpoch,
                                    surface = WarmResumeRenderedSurface.Error,
                                ) {
                                    WarmResumeUsefulSurface {
                                        ErrorContent(
                                            title = stringResource(R.string.white_noise_couldnt_start),
                                            error = phase.error,
                                            onRetry = { scope.launch { appState.retryBootstrap() } },
                                        )
                                    }
                                }
                        }
                        // Sign Out & Wipe chrome (#350) lives above the phase
                        // router but below the lock privacy boundary.
                        if (appState.wipeInProgress) {
                            WipeProgressSheet()
                        }
                        appState.pendingWipeReport?.let { report ->
                            WipeOutcomeSheet(
                                report = report,
                                onDismiss = { appState.pendingWipeReport = null },
                            )
                        }
                        if (
                            shouldShowConversationDictationFloatingControl(
                                state = dictationState,
                                originVisible = dictationOriginVisible,
                                appLockScreenVisible = appState.appLockScreenVisible,
                            )
                        ) {
                            ConversationDictationFloatingControl(
                                state = dictationState,
                                controller = dictation,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .navigationBarsPadding()
                                        .padding(end = 16.dp, bottom = 88.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (!appState.appLockScreenVisible && dictationState is ConversationDictationState.DisclosureRequired) {
        ConfirmDialog(
            title = stringResource(R.string.dictation_disclosure_title),
            message = stringResource(R.string.dictation_disclosure_message),
            confirmLabel = stringResource(R.string.dictation_continue),
            onConfirm = dictation::acceptDisclosure,
            onDismiss = dictation::cancel,
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun WarmResumeFrameSurface(
    activityToken: Int,
    foregroundEpoch: Int,
    surface: WarmResumeRenderedSurface,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view, activityToken, foregroundEpoch, surface) {
        if (foregroundEpoch <= 0) return@DisposableEffect onDispose { }

        val observer = view.viewTreeObserver
        var recorded = false
        var attached = true
        lateinit var listener: ViewTreeObserver.OnDrawListener

        fun detachListener() {
            if (!attached) return
            attached = false
            if (observer.isAlive) observer.removeOnDrawListener(listener)
        }
        listener =
            ViewTreeObserver.OnDrawListener {
                if (!recorded) {
                    recorded = true
                    WarmResumeTrace.renderedSurfaceFrame(
                        activityToken = activityToken,
                        foregroundEpoch = foregroundEpoch,
                        surface = surface,
                    )
                    view.post(::detachListener)
                }
            }
        observer.addOnDrawListener(listener)
        onDispose(::detachListener)
    }
    content()
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
