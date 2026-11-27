package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.SignInContent
import dev.ipf.whitenoise.android.ui.onboarding.WelcomeScreen

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
    privateKeyState: TextFieldState? = null,
    amberSignInStage: Int? = null,
    onBack: () -> Unit = {},
    onUseRemoteSigner: ((String?) -> Unit)? = null,
    signingInState: MutableState<Boolean> =
        remember {
            val action = inFlightAction
            val signingIn = action == OnboardingAction.Importing || action == OnboardingAction.AmberLogin
            mutableStateOf(signingIn)
        },
) {
    val busy = inFlightAction != OnboardingAction.Idle
    val key = privateKeyState ?: remember { TextFieldState(identity) }
    if (privateKeyState == null) {
        LaunchedEffect(identity) {
            if (key.text.toString() != identity) key.setTextAndPlaceCursorAtEnd(identity)
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
                    key.setTextAndPlaceCursorAtEnd("")
                    onIdentityChange("")
                    onErrorChange(null)
                    signingIn = false
                }
            },
            onSignIn = onImport,
            privateKeyState = key,
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
