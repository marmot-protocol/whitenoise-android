package dev.ipf.whitenoise.android.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AppLockScreen
import dev.ipf.whitenoise.android.ui.common.FailureScreen
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.conversation.media.SHARED_MEDIA_MAX_AGE_MS
import dev.ipf.whitenoise.android.ui.conversation.media.sweepStaleSharedMedia
import dev.ipf.whitenoise.android.ui.navigation.MainShell
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.settings.WipeOutcomeSheet
import dev.ipf.whitenoise.android.ui.settings.WipeProgressSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WhiteNoiseApp(
    appState: WhiteNoiseAppState,
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
    val toast = appState.toast
    val context = LocalContext.current
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
                ),
            )
            appState.clearToast()
        }
    }
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

    // Privacy hardening (#405): when "Force incognito keyboard" is on, wrap the
    // whole app UI so every descendant text field requests incognito mode from
    // the IME (no learning / suggestion history / cloud sync of typed content).
    IncognitoKeyboardScope(enabled = appState.forceIncognitoKeyboard) {
        CompositionLocalProvider(LocalSnackbarBottomInset provides snackbarBottomInset) {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                snackbarHost = { WhiteNoiseSnackbarHost(snackbarHostState) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    // Not while the app-lock screen is up — as a Dialog it would
                    // float above the lock and expose update actions before unlock.
                    if (!appState.appLockScreenVisible) {
                        AppSelfUpdateDialog(appState = appState)
                    }
                    when (val phase = appState.phase) {
                        AppPhase.Bootstrapping -> LoadingScreen()
                        AppPhase.Onboarding -> OnboardingScreen(appState)
                        AppPhase.Ready ->
                            MainShell(
                                appState = appState,
                                inboundNotificationTarget = inboundNotificationTarget,
                                inboundNotificationRequestId = inboundNotificationRequestId,
                                onNotificationTargetHandled = onNotificationTargetHandled,
                                inboundShareRequest = inboundShareRequest,
                                onShareRequestHandled = onShareRequestHandled,
                                inboundAppUpdateTap = inboundAppUpdateTap,
                                onAppUpdateTapHandled = onAppUpdateTapHandled,
                            )
                        is AppPhase.Failed ->
                            FailureScreen(
                                message = phase.message,
                                onRetry = { appState.present(R.string.toast_restarting) },
                                onRetryAction = { appState.bootstrap() },
                            )
                    }
                    if (appState.appLockScreenVisible) {
                        AppLockScreen(
                            error = appState.appUnlockError,
                            onRetry = { appState.requestAppUnlock() },
                        )
                    }
                    // Sign Out & Wipe chrome (#350) is hosted here, above the
                    // phase router: the wipe flips the active account (or drops
                    // to Onboarding) mid-flight, popping whatever screen
                    // started it, so neither the progress sheet nor the
                    // partial-failure outcome sheet can live in that screen.
                    if (appState.wipeInProgress) {
                        WipeProgressSheet()
                    }
                    appState.pendingWipeReport?.let { report ->
                        WipeOutcomeSheet(
                            report = report,
                            onDismiss = { appState.pendingWipeReport = null },
                        )
                    }
                }
            }
        }
    }
}
