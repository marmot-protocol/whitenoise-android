package dev.ipf.whitenoise.android.ui.profile

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.removeUnlinkedAccount
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.devices.UnlinkedProfileRemovalDialog
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.SignInStep
import dev.ipf.whitenoise.android.ui.onboarding.importIdentityErrorRes
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerEntry
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerFlow
import dev.ipf.whitenoise.android.ui.onboarding.signInAfterRemovalConsent
import dev.ipf.whitenoise.android.ui.onboarding.signInStepFor
import dev.ipf.whitenoise.android.ui.settings.SettingsContour

/** Full-screen Add Profile entry; shared Sign Up and the existing import/Amber owners perform native work. */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun AddIdentitySheet(
    appState: WhiteNoiseAppState,
    origin: Rect? = null,
    sourceContour: SettingsContour? = null,
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
    val identity =
        session.signInField.key.text
            .toString()
    var removalRef by remember(session, identity) { mutableStateOf<String?>(null) }
    val amberSignerAvailable = remember(appState) { appState.isAmberSignerInstalled() }
    var remoteSigner by remember(session) { mutableStateOf<RemoteSignerEntry?>(null) }
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

    /** Runs an onboarding action as the sheet's single in-flight work item. */
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

    /** Imports the entered key. */
    fun startImport(confirmedRemoval: String? = null) {
        val submitted =
            session.signInField.key.text
                .toString()
        if (submitted.isBlank() || submitted != identity) return

        fun reportError(message: Int) {
            if (confirmedRemoval != null && !session.ownsEntry()) appState.present(message) else session.error(message)
        }
        start(OnboardingAction.Importing) {
            val step =
                if (confirmedRemoval == null) {
                    signInStepFor(appState.importIdentity(submitted), submitted)
                } else {
                    signInAfterRemovalConsent(
                        confirmedRemoval,
                        submitted,
                        appState::removeUnlinkedAccount,
                        appState::importIdentity,
                    )
                }
            when (step) {
                SignInStep.SignedIn, SignInStep.SetupStarted -> {
                    clearSubmittedIdentityClipboard(context, submitted)
                    session.dismiss()
                }
                // Recovery requires its existing dedicated consent route; adding a profile never implies that grant.
                SignInStep.AskRecoveryConsent -> reportError(importIdentityErrorRes(submitted))
                is SignInStep.AskRemovalConsent ->
                    if (session.ownsEntry()) {
                        removalRef = step.accountRef
                    } else {
                        reportError(R.string.unlinked_account_remove_failed)
                    }
                is SignInStep.InlineError -> reportError(step.messageRes)
            }
        }
    }
    MeasuredPlaybackDialog(
        origin = origin,
        sourceContour = sourceContour,
        ownerKey = session,
        onDismissRequest = session::dismiss,
        onSourceDismiss = session::dismiss,
        properties =
            DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        remoteSigner?.let { entry ->
            RemoteSignerFlow(
                appState,
                onClose = { remoteSigner = null },
                onFinished = session::dismiss,
                entry = entry,
            )
            return@MeasuredPlaybackDialog
        }
        AddAccountSheetContent(
            amberSignerAvailable = amberSignerAvailable,
            inFlightAction = session.action,
            identity =
                session.signInField.key.text
                    .toString(),
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
            onImport = { startImport() },
            signInField = session.signInField,
            amberSignInStage = appState.amberSignInStage,
            onBack = this.dismiss,
            onUseRemoteSigner = { entry ->
                if (session.action == OnboardingAction.Idle) remoteSigner = entry
            },
        )
        removalRef?.let { label ->
            UnlinkedProfileRemovalDialog(
                signingIn = true,
                onCancel = { removalRef = null },
                onRemove = {
                    if (removalRef == label) {
                        removalRef = null
                        startImport(label)
                    }
                },
            )
        }
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
