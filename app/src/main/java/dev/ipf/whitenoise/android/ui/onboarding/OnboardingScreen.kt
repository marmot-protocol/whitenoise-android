package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.state.IdentityImportOutcome
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLogoLockup
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Single in-flight onboarding action so the two buttons can never both read as
// busy: each button's spinner keys off its own action, and the shared value
// disables the other while one runs.
internal enum class OnboardingAction { Idle, Creating, Importing, AmberLogin }

internal enum class OnboardingActionDecision { Start, ShowOffline, IgnoreBusy }

internal fun onboardingActionDecision(
    inFlightAction: OnboardingAction,
    hasActiveNetwork: Boolean,
): OnboardingActionDecision =
    when {
        inFlightAction != OnboardingAction.Idle -> OnboardingActionDecision.IgnoreBusy
        !hasActiveNetwork -> OnboardingActionDecision.ShowOffline
        else -> OnboardingActionDecision.Start
    }

internal fun dispatchOnboardingAction(
    inFlightAction: OnboardingAction,
    hasActiveNetwork: Boolean,
    requestedAction: OnboardingAction,
    onOffline: (OnboardingAction) -> Unit,
    onStart: () -> Unit,
) {
    when (onboardingActionDecision(inFlightAction, hasActiveNetwork)) {
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
internal val OnboardingMaxContentWidth = 440.dp

@Composable
internal fun OnboardingScreen(
    appState: WhiteNoiseAppState,
    hasActiveNetwork: () -> Boolean = appState::hasActiveNetwork,
) {
    var identity by remember { mutableStateOf("") }
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

    fun applyStep(step: SignInStep) {
        when (step) {
            SignInStep.SignedIn -> clearSensitiveClipboard(context)
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
        scope.launch {
            try {
                applyStep(step())
            } finally {
                inFlightAction = OnboardingAction.Idle
            }
        }
    }

    fun startNetworkSetupAction(action: OnboardingAction) {
        dispatchOnboardingAction(
            inFlightAction = inFlightAction,
            hasActiveNetwork = hasActiveNetwork(),
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
                        val value = identity.trim()
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

    OnboardingContent(
        identity = identity,
        creatingIdentity = inFlightAction == OnboardingAction.Creating,
        signingInBusy = inFlightAction == OnboardingAction.Importing,
        importErrorRes = importErrorRes,
        offlineErrorVisible = offlineRetryAction != null,
        onOfflineRetry = {
            offlineRetryAction?.let(::startNetworkSetupAction)
        },
        onOfflineErrorDismiss = { offlineRetryAction = null },
        onIdentityChange = {
            identity = it
            importErrorRes = null
            offlineRetryAction = null
            // Editing the field ends the attempt the acknowledgement belonged to,
            // so the key it held must not outlive it.
            recoveryConsentedFor = null
        },
        onImportErrorChange = { importErrorRes = it },
        onCreateIdentity = { startNetworkSetupAction(OnboardingAction.Creating) },
        onImportIdentity = { _ -> startNetworkSetupAction(OnboardingAction.Importing) },
        recoveryConsentVisible = recoveryConsentVisible,
        // The engine needs the same nsec again, so the already-entered value is
        // reused from the field rather than stashed anywhere new — it leaves
        // memory with the rest of the onboarding state.
        onRecoveryConsentConfirm = {
            recoveryConsentVisible = false
            val value = identity.trim()
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

@Composable
fun OnboardingContent(
    identity: String,
    creatingIdentity: Boolean,
    signingInBusy: Boolean,
    onIdentityChange: (String) -> Unit,
    onCreateIdentity: () -> Unit,
    onImportIdentity: (String) -> Unit,
    importErrorRes: Int? = null,
    onImportErrorChange: (Int?) -> Unit = {},
    offlineErrorVisible: Boolean = false,
    onOfflineRetry: () -> Unit = {},
    onOfflineErrorDismiss: () -> Unit = {},
    loggingInWithAmber: Boolean = false,
    amberSignInStage: Int? = null,
    amberSignerAvailable: Boolean = false,
    onLoginWithAmber: () -> Unit = {},
    recoveryConsentVisible: Boolean = false,
    onRecoveryConsentConfirm: () -> Unit = {},
    onRecoveryConsentDismiss: () -> Unit = {},
) {
    var signingIn by remember { mutableStateOf(signingInBusy) }
    val busy = creatingIdentity || signingInBusy || loggingInWithAmber
    val creatingIdentityDescription = stringResource(R.string.creating_identity)
    val amberLoginDescription = stringResource(R.string.onboarding_login_with_amber)

    if (signingIn) {
        SignInContent(
            identity = identity,
            busy = signingInBusy,
            errorRes = importErrorRes,
            offlineErrorVisible = offlineErrorVisible,
            onOfflineRetry = onOfflineRetry,
            onIdentityChange = onIdentityChange,
            onErrorChange = onImportErrorChange,
            onBack = {
                onOfflineErrorDismiss()
                signingIn = false
            },
            onSignIn = { onImportIdentity(identity.trim()) },
            recoveryConsentVisible = recoveryConsentVisible,
            onRecoveryConsentConfirm = onRecoveryConsentConfirm,
            onRecoveryConsentDismiss = onRecoveryConsentDismiss,
        )
        return
    }

    // Edge-to-edge: the landing is hosted in a Scaffold with zero content insets
    // (see WhiteNoiseApp), so it owns its own system-bar / display-cutout padding
    // via safeDrawing. Adaptive: the hero + actions are capped at a readable
    // width and centered so the column doesn't stretch full-bleed on tablets /
    // unfolded foldables / desktop windows. Layout mirrors the old app: plain
    // logo + rotating slogan centered above a bottom "slate" holding the two
    // auth actions.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = OnboardingMaxContentWidth)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero: plain mark + wordmark + rotating slogan, centered in the
            // flexible space above the slate.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                WhiteNoiseLogoLockup(size = 96.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    // Brand wordmark: always "White Noise" (see the white_noise
                    // string), never app_name, which debug builds relabel to
                    // "White Noise Dev".
                    stringResource(R.string.white_noise),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                OnboardingRotatingSlogan()
            }

            // Bottom slate: a lifted surface panel holding the auth actions,
            // echoing the old app's WnSlate. Login (tonal) sits above Sign up
            // (filled primary), matching the old auth-buttons order.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    if (offlineErrorVisible) {
                        OnboardingOfflineNotice(onRetry = onOfflineRetry)
                    }
                    FilledTonalButton(
                        onClick = {
                            onOfflineErrorDismiss()
                            signingIn = true
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_login),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(
                        onClick = onCreateIdentity,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        if (creatingIdentity) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .semantics { contentDescription = creatingIdentityDescription },
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            stringResource(if (creatingIdentity) R.string.creating_identity_title else R.string.onboarding_sign_up),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // Alternative login via an installed NIP-55 external signer
                    // (Amber). Only offered when such a signer is present.
                    if (amberSignerAvailable) {
                        OutlinedButton(
                            onClick = onLoginWithAmber,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        ) {
                            if (loggingInWithAmber) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier
                                            .size(20.dp)
                                            .semantics { contentDescription = amberLoginDescription },
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                stringResource(R.string.onboarding_login_with_amber),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        // Two sequential signer prompts are unavoidable (the
                        // proof needs the key the first prompt reveals), so
                        // say which one the wait is for instead of reading
                        // as a hang.
                        if (loggingInWithAmber && amberSignInStage != null) {
                            Text(
                                text =
                                    stringResource(
                                        if (amberSignInStage == 1) {
                                            R.string.amber_signin_waiting_request
                                        } else {
                                            R.string.amber_signin_waiting_proof
                                        },
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OnboardingOfflineNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ONBOARDING_OFFLINE_NOTICE_TAG),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_offline_setup_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .padding(end = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/**
 * The rotating brand slogan on the onboarding landing, mirroring the old app:
 * fades through "Decentralized" → "Uncensorable" → "Secure messaging" every 3s.
 * The index advances on a paused-clock-friendly [delay] loop, so a screenshot
 * test that doesn't advance the clock deterministically captures the first
 * slogan.
 */
@Composable
private fun OnboardingRotatingSlogan() {
    val slogans =
        listOf(
            R.string.onboarding_slogan_decentralized,
            R.string.onboarding_slogan_uncensorable,
            R.string.onboarding_slogan_secure_messaging,
        )
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            index = (index + 1) % slogans.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
        label = "onboarding-slogan",
    ) { current ->
        Text(
            stringResource(slogans[current]),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}
