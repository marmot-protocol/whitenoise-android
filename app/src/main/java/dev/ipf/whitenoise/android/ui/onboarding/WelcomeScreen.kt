package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Selects Welcome or Sign In while the existing owner retains creation, import, signer and recovery state. */
@Suppress("FunctionNaming", "LongParameterList")
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
    savedAccounts: List<OnboardingSavedAccountUi> = emptyList(),
    reactivatingAccountLabel: String? = null,
    onContinueWithSavedAccount: (String) -> Unit = {},
    onRecoverSavedAccount: (String) -> Unit = {},
    privateKeyState: TextFieldState? = null,
) {
    var signingIn by remember { mutableStateOf(signingInBusy || loggingInWithAmber) }
    val busy = creatingIdentity || signingInBusy || loggingInWithAmber || reactivatingAccountLabel != null
    if (signingIn) {
        SignInContent(
            identity = identity,
            busy = busy,
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
            onSignInValue = { onImportIdentity(it.trim()) },
            recoveryConsentVisible = recoveryConsentVisible,
            onRecoveryConsentConfirm = onRecoveryConsentConfirm,
            onRecoveryConsentDismiss = onRecoveryConsentDismiss,
            privateKeyState = privateKeyState,
            amberSignerAvailable = amberSignerAvailable,
            loggingInWithAmber = loggingInWithAmber,
            amberSignInStage = amberSignInStage,
            onLoginWithAmber = onLoginWithAmber,
        )
    } else {
        WelcomeScreen(
            busy = busy,
            creatingIdentity = creatingIdentity,
            onSignIn = {
                onOfflineErrorDismiss()
                signingIn = true
            },
            onSignUp = onCreateIdentity,
            savedAccounts = savedAccounts,
            reactivatingAccountLabel = reactivatingAccountLabel,
            onContinueWithSavedAccount = onContinueWithSavedAccount,
            onRecoverSavedAccount = onRecoverSavedAccount,
            offlineErrorVisible = offlineErrorVisible,
            onOfflineRetry = onOfflineRetry,
        )
    }
}

/** Flexible monochrome mark above a 520 dp action column; no editor or outgoing IME owns this screen. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun WelcomeScreen(
    busy: Boolean,
    creatingIdentity: Boolean,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    savedAccounts: List<OnboardingSavedAccountUi>,
    reactivatingAccountLabel: String?,
    onContinueWithSavedAccount: (String) -> Unit,
    onRecoverSavedAccount: (String) -> Unit,
    offlineErrorVisible: Boolean,
    onOfflineRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        topBar = {
            if (onBack != null) WhiteNoiseTopBar(title = stringResource(R.string.settings_add_profile), onBack = onBack)
        },
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val availableHeight = (maxHeight - WhiteNoiseSpacing.CompactScreenMargin * 2).coerceAtLeast(0.dp)
                val markWidth =
                    minOf(
                        maxWidth.coerceAtMost(OnboardingMaxContentWidth) * 0.5f,
                        availableHeight * WELCOME_MARK_ASPECT_RATIO,
                    )
                Icon(
                    painterResource(R.drawable.ic_white_noise_mark),
                    stringResource(R.string.app_name),
                    modifier =
                        Modifier
                            .size(markWidth, markWidth / WELCOME_MARK_ASPECT_RATIO)
                            .testTag("onboarding.welcome.mark"),
                )
            }
            Column(
                modifier =
                    Modifier
                        .then(
                            if (savedAccounts.isNotEmpty() || offlineErrorVisible) {
                                Modifier.weight(1f, fill = false)
                            } else {
                                Modifier
                            },
                        ).whiteNoiseVerticalScroll(rememberScrollState())
                        .widthIn(max = OnboardingMaxContentWidth)
                        .fillMaxWidth()
                        .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                        .padding(bottom = WhiteNoiseSpacing.PinnedActionInset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                OnboardingSavedAccountActions(
                    savedAccounts,
                    reactivatingAccountLabel,
                    !busy,
                    onContinueWithSavedAccount,
                    onRecoverSavedAccount,
                )
                if (offlineErrorVisible) OnboardingOfflineNotice(onOfflineRetry)
                WhiteNoiseOutlinedButton(
                    onClick = onSignIn,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding.welcome.sign_in"),
                ) {
                    Text(stringResource(R.string.onboarding_login))
                }
                WhiteNoiseButton(
                    onClick = onSignUp,
                    enabled = !busy,
                    loading = creatingIdentity,
                    loadingLabel = stringResource(R.string.creating_identity_title),
                    modifier = Modifier.fillMaxWidth().testTag("onboarding.welcome.sign_up"),
                ) { Text(stringResource(R.string.onboarding_sign_up)) }
            }
        }
    }
}

/** Existing offline retry action in the shared callout; it remains reachable in the scrolling action column. */
@Suppress("FunctionNaming")
@Composable
internal fun OnboardingOfflineNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(ONBOARDING_OFFLINE_NOTICE_TAG)) {
        WhiteNoiseCallout(
            text = stringResource(R.string.onboarding_offline_setup_message),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.retry)) }
    }
}

private const val WELCOME_MARK_ASPECT_RATIO = 598f / 460f
