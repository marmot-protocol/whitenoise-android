package dev.ipf.whitenoise.android.ui.profile

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.SignInStep
import dev.ipf.whitenoise.android.ui.onboarding.importIdentityErrorRes
import dev.ipf.whitenoise.android.ui.onboarding.signInStepFor

/** Full-screen Add Profile entry; shared Sign Up and the existing import/Amber owners perform native work. */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun AddIdentitySheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
) {
    WindowSecureFlag()
    val context = LocalContext.current
    val dismiss by rememberUpdatedState(onDismiss)
    val activeAtOpen = remember(appState) { appState.activeAccountRef }
    val runtimeAtOpen = remember(appState) { appState.runtimeGeneration }
    val session =
        remember(appState) {
            AddProfileSession(
                ownerAvailable = {
                    appState.activeAccountRef == activeAtOpen &&
                        appState.runtimeGeneration == runtimeAtOpen &&
                        !appState.signOutInProgress &&
                        !appState.wipeInProgress &&
                        appState.retainedAccountReactivationRef == null &&
                        appState.accountSetup.controller == null
                },
                onDismiss = { dismiss() },
            )
        }
    val amberSignerAvailable = remember(appState) { appState.isAmberSignerInstalled() }
    DisposableEffect(session) { onDispose { session.dispose() } }
    LaunchedEffect(
        appState.activeAccountRef,
        appState.runtimeGeneration,
        appState.accountSetup.controller,
        appState.signOutInProgress,
        appState.wipeInProgress,
        appState.retainedAccountReactivationRef,
    ) {
        if (!session.ownsEntry()) session.dismiss()
    }

    fun start(
        action: OnboardingAction,
        work: suspend () -> Unit,
    ) {
        if (!session.begin(action)) return
        appState.launchMutation {
            val previousToast = appState.toast
            val previousNotice = appState.transientNotice
            try {
                if (session.ownsEntry()) {
                    work()
                    session.recordNativeFeedback(
                        action,
                        previousToast,
                        appState.toast,
                        previousNotice,
                        appState.transientNotice,
                    )
                }
            } finally {
                session.finish(action)
            }
        }
    }

    fun startImport() {
        val submitted = session.key.text.toString()
        if (submitted.isBlank()) return
        start(OnboardingAction.Importing) {
            when (val step = signInStepFor(appState.importIdentity(submitted), submitted)) {
                SignInStep.SignedIn, SignInStep.SetupStarted -> {
                    clearSubmittedIdentityClipboard(context, submitted)
                    session.dismiss()
                }
                // Recovery requires its existing dedicated consent route; adding a profile never implies that grant.
                SignInStep.AskRecoveryConsent -> session.error(importIdentityErrorRes(submitted))
                is SignInStep.InlineError -> session.error(step.messageRes)
            }
        }
    }
    Dialog(
        onDismissRequest = session::dismiss,
        properties =
            DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        AddAccountSheetContent(
            amberSignerAvailable = amberSignerAvailable,
            inFlightAction = session.action,
            identity = session.key.text.toString(),
            importErrorRes = session.errorRes,
            onCreate = {
                if (session.begin(OnboardingAction.Creating)) {
                    appState.beginProfileSignUp()
                    if (appState.profileSignUpForPresentation != null) {
                        session.dismiss()
                    } else {
                        // A still-running stale attempt may retain the native owner; keep this entry usable.
                        session.finish(OnboardingAction.Creating)
                    }
                }
            },
            onLoginWithAmber = { start(OnboardingAction.AmberLogin) { appState.loginWithAmber() } },
            onIdentityChange = session::edit,
            onErrorChange = session::error,
            onImport = ::startImport,
            privateKeyState = session.key,
            amberSignInStage = appState.amberSignInStage,
            onBack = session::dismiss,
        )
        session.feedback?.let { AddProfileFeedbackDialog(it, session::dismissFeedback) }
    }
}

/** Clears only the submitted plaintext key; a late accepted import cannot erase a replacement clipboard. */
private fun clearSubmittedIdentityClipboard(
    context: Context,
    submitted: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val current =
        clipboard.primaryClip
            ?.takeIf { it.itemCount == 1 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    if (current?.trim() == submitted.trim()) clearSensitiveClipboard(context)
}
