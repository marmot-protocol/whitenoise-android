package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.SignInContent
import dev.ipf.whitenoise.android.ui.onboarding.SignInFieldState
import dev.ipf.whitenoise.android.ui.onboarding.WelcomeScreen
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerEntry

/**
 * Hosts the Add Profile entry and secure Sign In. The caller retains [signingInState] while showing pairing.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun AddAccountSheetContent(
    amberSignerAvailable: Boolean,
    inFlightAction: OnboardingAction,
    identity: String,
    importErrorRes: Int?,
    onCreate: () -> Unit,
    onLoginWithAmber: () -> Unit,
    onIdentityChange: (String) -> Unit,
    onErrorChange: (Int?) -> Unit,
    onImport: () -> Unit,
    signInField: SignInFieldState? = null,
    amberSignInStage: Int? = null,
    onBack: () -> Unit = {},
    onUseRemoteSigner: ((RemoteSignerEntry) -> Unit)? = null,
    signingInState: MutableState<Boolean> =
        remember {
            val action = inFlightAction
            val signingIn = action == OnboardingAction.Importing || action == OnboardingAction.AmberLogin
            mutableStateOf(signingIn)
        },
) {
    val busy = inFlightAction != OnboardingAction.Idle
    val field = signInField ?: remember { SignInFieldState(identity) }
    val key = field.key
    if (signInField == null) {
        LaunchedEffect(identity) {
            if (key.text.toString() != identity) field.replaceText(identity)
        }
    }
    var signingIn by signingInState
    if (signingIn) {
        SignInContent(
            identity = key.text.toString(),
            busy = busy,
            errorRes = importErrorRes,
            onIdentityChange = onIdentityChange,
            onErrorChange = onErrorChange,
            onBack = {
                if (!busy) {
                    field.replaceText("")
                    onIdentityChange("")
                    onErrorChange(null)
                    signingIn = false
                }
            },
            onSignIn = onImport,
            signInField = field,
            amberSignerAvailable = amberSignerAvailable,
            loggingInWithAmber = inFlightAction == OnboardingAction.AmberLogin,
            amberSignInStage = amberSignInStage,
            onLoginWithAmber = onLoginWithAmber,
            onSignInValue = { onImport() },
            onSystemBackWhileBusy = onBack,
            onUseRemoteSigner = onUseRemoteSigner,
        )
    } else {
        WelcomeScreen(
            busy = busy,
            creatingIdentity = inFlightAction == OnboardingAction.Creating,
            onSignIn = { if (!busy) signingIn = true },
            onSignUp = onCreate,
            savedAccounts = emptyList(),
            reactivatingAccountLabel = null,
            onContinueWithSavedAccount = {},
            onRecoverSavedAccount = {},
            offlineErrorVisible = false,
            onOfflineRetry = {},
            onBack = onBack,
        )
    }
}
