package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.state.IdentityImportOutcome
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
import kotlinx.coroutines.launch

// Single in-flight onboarding action so the two buttons can never both read as
// busy: each button's spinner keys off its own action, and the shared value
// disables the other while one runs.
internal enum class OnboardingAction { Idle, Creating, Importing, AmberLogin }

internal enum class OnboardingActionDecision { Start, ShowOffline, IgnoreBusy }

internal fun onboardingActionDecision(
    inFlightAction: OnboardingAction,
    hasValidatedInternet: Boolean,
): OnboardingActionDecision =
    when {
        inFlightAction != OnboardingAction.Idle -> OnboardingActionDecision.IgnoreBusy
        !hasValidatedInternet -> OnboardingActionDecision.ShowOffline
        else -> OnboardingActionDecision.Start
    }

internal fun dispatchOnboardingAction(
    inFlightAction: OnboardingAction,
    hasValidatedInternet: Boolean,
    requestedAction: OnboardingAction,
    onOffline: (OnboardingAction) -> Unit,
    onStart: () -> Unit,
) {
    val identityOnly = requestedAction != OnboardingAction.Creating
    when (onboardingActionDecision(inFlightAction, hasValidatedInternet || identityOnly)) {
        OnboardingActionDecision.IgnoreBusy -> Unit
        OnboardingActionDecision.ShowOffline -> onOffline(requestedAction)
        OnboardingActionDecision.Start -> onStart()
    }
}

internal const val ONBOARDING_OFFLINE_NOTICE_TAG = "onboarding-offline-notice"

// Adaptive cap for the onboarding + sign-in surfaces: on a phone the hero and
// actions fill the width, but on tablets / unfolded foldables / desktop windows
// they stay a readable single column centered in the window rather than
// stretching full-bleed (per the `adaptive` skill's max-content-width guidance).
internal val OnboardingMaxContentWidth = 520.dp

/** Owns sign-in actions and observes entry only when sharing permission already exists. */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod") // Owns the short-lived sign-in state machine.
internal fun OnboardingScreen(
    appState: WhiteNoiseAppState,
    hasValidatedInternet: () -> Boolean = appState::hasValidatedInternet,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        appState.recordProductObservation(dev.ipf.whitenoise.android.state.ProductObservation.ONBOARDING)
    }
    val privateKeyState = remember { TextFieldState() }
    val identity = privateKeyState.text.toString()
    var inFlightAction by remember { mutableStateOf(OnboardingAction.Idle) }
    var importErrorRes by remember { mutableStateOf<Int?>(null) }
    var offlineRetryAction by remember { mutableStateOf<OnboardingAction?>(null) }
    var recoveryConsentVisible by remember { mutableStateOf(false) }
    // The key an acknowledged recovery already ran for, cleared as soon as the
    // field changes, so it never outlives the attempt it belongs to. It stops a
    // failed recovery from steering the user through the prompt on every retry.
    var recoveryConsentedFor by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Signer availability can't change while onboarding is on screen, so read it
    // once (the query hits the PackageManager).
    val amberSignerAvailable = remember { appState.isAmberSignerInstalled() }
    val savedAccounts = onboardingSavedAccounts(appState)

    fun applyStep(step: SignInStep) {
        when (step) {
            SignInStep.SignedIn, SignInStep.SetupStarted -> {
                clearSensitiveClipboard(context)
                privateKeyState.setTextAndPlaceCursorAtEnd("")
                recoveryConsentedFor = null
            }
            SignInStep.AskRecoveryConsent -> recoveryConsentVisible = true
            is SignInStep.InlineError -> importErrorRes = step.messageRes
        }
    }

    // Shared by the sign-in attempt and the acknowledged recovery: the busy
    // state flips before the coroutine starts, so the button reads as busy from
    // the tap onward. The idle guard is what makes a second tap in the frame
    // before that flip renders — the consent dialog is still attached — reach
    // no second engine call.
    fun runStep(step: suspend () -> SignInStep) {
        if (inFlightAction != OnboardingAction.Idle) return
        inFlightAction = OnboardingAction.Importing
        importErrorRes = null
        appState.launchMutation {
            try {
                applyStep(step())
            } finally {
                inFlightAction = OnboardingAction.Idle
            }
        }
    }

    fun startNetworkSetupAction(action: OnboardingAction) {
        if (appState.retainedAccountReactivationRef != null) return
        dispatchOnboardingAction(
            inFlightAction = inFlightAction,
            hasValidatedInternet = hasValidatedInternet(),
            requestedAction = action,
            onOffline = { offlineRetryAction = it },
            onStart = {
                offlineRetryAction = null
                when (action) {
                    OnboardingAction.Creating -> {
                        inFlightAction = OnboardingAction.Creating
                        scope.launch {
                            try {
                                appState.createIdentity()
                            } finally {
                                inFlightAction = OnboardingAction.Idle
                            }
                        }
                    }
                    OnboardingAction.Importing -> {
                        val value = privateKeyState.text.toString().trim()
                        runStep { signInStepFor(appState.importIdentity(value), value, recoveryConsentedFor) }
                    }
                    OnboardingAction.AmberLogin -> {
                        inFlightAction = OnboardingAction.AmberLogin
                        scope.launch {
                            try {
                                appState.loginWithAmber()
                            } finally {
                                inFlightAction = OnboardingAction.Idle
                            }
                        }
                    }
                    OnboardingAction.Idle -> Unit
                }
            },
        )
    }

    val signUp = appState.profileSignUpForPresentation
    if (signUp != null) {
        SignUpScreen(signUp, hasValidatedInternet, onBack = { appState.dismissProfileSignUp() })
        return
    }

    OnboardingContent(
        identity = identity,
        privateKeyState = privateKeyState,
        creatingIdentity = inFlightAction == OnboardingAction.Creating,
        signingInBusy = inFlightAction == OnboardingAction.Importing,
        importErrorRes = importErrorRes,
        offlineErrorVisible = offlineRetryAction != null,
        onOfflineRetry = {
            offlineRetryAction?.let(::startNetworkSetupAction)
        },
        onOfflineErrorDismiss = { offlineRetryAction = null },
        onIdentityChange = {
            if (privateKeyState.text.toString() != it) privateKeyState.setTextAndPlaceCursorAtEnd(it)
            importErrorRes = null
            offlineRetryAction = null
            // Editing the field ends the attempt the acknowledgement belonged to,
            // so the key it held must not outlive it.
            recoveryConsentedFor = null
        },
        onImportErrorChange = { importErrorRes = it },
        onCreateIdentity = {
            if (inFlightAction == OnboardingAction.Idle && appState.retainedAccountReactivationRef == null) {
                appState.beginProfileSignUp()
            }
        },
        onImportIdentity = { _ -> startNetworkSetupAction(OnboardingAction.Importing) },
        recoveryConsentVisible = recoveryConsentVisible,
        // The engine needs the same nsec again, so the already-entered value is
        // reused from the field rather than stashed anywhere new — it leaves
        // memory with the rest of the onboarding state.
        onRecoveryConsentConfirm = {
            recoveryConsentVisible = false
            val value = privateKeyState.text.toString().trim()
            recoveryConsentedFor = value
            runStep { recoveryStepFor(appState.recoverIncompleteIdentitySetup(value)) }
        },
        // Declining reaches no engine call at all: only the prompt closes, and
        // the entered key and sign-in button stay exactly as they were.
        onRecoveryConsentDismiss = {
            recoveryConsentVisible = false
            importErrorRes = R.string.sign_in_error_setup_recovery_declined
        },
        loggingInWithAmber = inFlightAction == OnboardingAction.AmberLogin,
        amberSignInStage = appState.amberSignInStage,
        amberSignerAvailable = amberSignerAvailable,
        onLoginWithAmber = { startNetworkSetupAction(OnboardingAction.AmberLogin) },
        savedAccounts = savedAccounts,
        reactivatingAccountLabel = appState.retainedAccountReactivationRef,
        onContinueWithSavedAccount = appState::reactivateRetainedAccount,
        onRecoverSavedAccount = { accountRef ->
            if (inFlightAction == OnboardingAction.Idle) {
                inFlightAction = OnboardingAction.Importing
                appState.launchMutation {
                    try {
                        val recovered = appState.recoverSetup(accountRef)
                        appState.presentTransient(
                            if (recovered) {
                                R.string.onboarding_recover_setup_success
                            } else {
                                R.string.onboarding_recover_setup_failed
                            },
                        )
                    } finally {
                        inFlightAction = OnboardingAction.Idle
                    }
                }
            }
        },
    )
}

/**
 * Inline message for a failed identity import (#795 lud16 pattern): input
 * that isn't even shaped like a key reads as "not a valid key"; a
 * well-formed key that the engine still rejected reads as a retryable
 * sign-in failure.
 */
internal fun importIdentityErrorRes(identity: String): Int =
    when (IdentityEntryInput.classify(identity)) {
        IdentityEntryInput.Kind.Invalid -> R.string.identity_entry_error_invalid_key
        IdentityEntryInput.Kind.PublicKey -> R.string.sign_in_error_public_key
        IdentityEntryInput.Kind.SecretKey -> R.string.identity_entry_error_import_failed
        // Recognized but not yet importable: the engine's login accepts
        // plaintext keys only, so an encrypted backup reads as import-failed.
        IdentityEntryInput.Kind.EncryptedSecretKey -> R.string.identity_entry_error_import_failed
    }

/** What the sign-in surface should do once the engine has answered. */
internal sealed interface SignInStep {
    data object SignedIn : SignInStep

    data object SetupStarted : SignInStep

    data class InlineError(
        val messageRes: Int,
    ) : SignInStep

    data object AskRecoveryConsent : SignInStep
}

/**
 * Each account-setup state gets its own message. The two resumable states point
 * at the sign-in button the user already has rather than retrying on their
 * behalf — an automatic retry on this path can loop.
 *
 * [recoveryConsentedFor] is the key an acknowledged recovery already ran for.
 * Asking again for the same key would only walk the user back through the
 * orphaned-KeyPackage acknowledgement the last round already spent, so the
 * repeat reads as a recovery that didn't complete.
 */
internal fun signInStepFor(
    outcome: IdentityImportOutcome,
    identity: String,
    recoveryConsentedFor: String? = null,
): SignInStep =
    when (outcome) {
        IdentityImportOutcome.Success -> SignInStep.SignedIn
        IdentityImportOutcome.SetupStarted -> SignInStep.SetupStarted
        IdentityImportOutcome.SetupRecoveryRequired ->
            if (identity == recoveryConsentedFor) {
                SignInStep.InlineError(R.string.sign_in_error_setup_recovery_failed)
            } else {
                SignInStep.AskRecoveryConsent
            }
        IdentityImportOutcome.SetupRetryRequired -> SignInStep.InlineError(R.string.sign_in_error_setup_retry)
        IdentityImportOutcome.SetupKeyPackageRecoveryAvailable ->
            SignInStep.InlineError(R.string.sign_in_error_setup_key_package_retry)
        IdentityImportOutcome.SetupResetNotApplicable ->
            SignInStep.InlineError(R.string.sign_in_error_setup_unexpected_state)
        IdentityImportOutcome.Failed -> SignInStep.InlineError(importIdentityErrorRes(identity))
    }

/**
 * Outcomes of the acknowledged recovery attempt. The consent was already
 * carried, so nothing here can ask for it again, and no message may claim the
 * account is untouched: the engine's recovery may have applied part of its work
 * before reporting any of these states.
 */
internal fun recoveryStepFor(outcome: IdentityImportOutcome): SignInStep =
    when (outcome) {
        IdentityImportOutcome.Success -> SignInStep.SignedIn
        IdentityImportOutcome.SetupStarted -> SignInStep.SetupStarted
        IdentityImportOutcome.SetupRetryRequired -> SignInStep.InlineError(R.string.sign_in_error_setup_retry)
        IdentityImportOutcome.SetupKeyPackageRecoveryAvailable ->
            SignInStep.InlineError(R.string.sign_in_error_setup_key_package_retry)
        IdentityImportOutcome.SetupResetNotApplicable ->
            SignInStep.InlineError(R.string.sign_in_error_setup_recovery_unexpected_state)
        // The key was well-formed enough for the engine to report a setup state a
        // moment ago, so a bare failure here is the recovery failing, not a bad key.
        IdentityImportOutcome.SetupRecoveryRequired,
        IdentityImportOutcome.Failed,
        -> SignInStep.InlineError(R.string.sign_in_error_setup_recovery_failed)
    }
