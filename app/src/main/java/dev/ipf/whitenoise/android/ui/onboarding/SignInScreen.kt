package dev.ipf.whitenoise.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerEntry
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.isBunkerLink
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

/**
 * Secure native key entry and explicit import/Amber actions; the existing owner retains recovery and account
 * lifecycle.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun SignInContent(
    identity: String,
    busy: Boolean,
    errorRes: Int?,
    offlineErrorVisible: Boolean = false,
    onOfflineRetry: () -> Unit = {},
    onIdentityChange: (String) -> Unit,
    onErrorChange: (Int?) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    recoveryConsentVisible: Boolean = false,
    onRecoveryConsentConfirm: () -> Unit = {},
    onRecoveryConsentDismiss: () -> Unit = {},
    signInField: SignInFieldState? = null,
    amberSignerAvailable: Boolean = false,
    loggingInWithAmber: Boolean = false,
    amberSignInStage: Int? = null,
    onLoginWithAmber: () -> Unit = {},
    onSignInValue: ((String) -> Unit)? = null,
    onSystemBackWhileBusy: (() -> Unit)? = null,
    onUseRemoteSigner: ((RemoteSignerEntry) -> Unit)? = null,
) {
    WindowSecureFlag()
    // The real onboarding owner supplies the native state; the fallback retains existing standalone callers/tests.
    val field = signInField ?: remember { SignInFieldState(identity) }
    val key = field.key
    val changed by rememberUpdatedState(onIdentityChange)
    var lastReportedKey by remember(key) { mutableStateOf(key.text.toString()) }

    /** Reports a key edit once per distinct value. */
    fun reportKeyEdit(value: String) {
        field.discardPasteForDifferentValue(value)
        if (value != lastReportedKey) {
            lastReportedKey = value
            changed(value)
        }
    }
    if (signInField == null) {
        LaunchedEffect(identity) {
            if (key.text.toString() != identity) field.replaceText(identity)
        }
    }
    LaunchedEffect(key) {
        snapshotFlow { key.text.toString() }.drop(1).collect { reportKeyEdit(it) }
    }
    val canSignIn = key.text.isNotBlank() && !busy

    /** Submits the key after flushing the last edit. */
    fun submit() {
        if (busy) return
        val value = key.text.toString()
        // Flush a just-typed edit before native work; its queued observer cannot erase the resulting error or consent.
        reportKeyEdit(value)
        if (onUseRemoteSigner != null && isBunkerLink(value)) {
            onUseRemoteSigner(RemoteSignerEntry(value.trim(), field.clipboardPaste))
            return
        }
        when (IdentityEntryInput.classify(value)) {
            IdentityEntryInput.Kind.Invalid -> onErrorChange(R.string.identity_entry_error_invalid_key)
            IdentityEntryInput.Kind.PublicKey -> onErrorChange(R.string.sign_in_error_public_key)
            IdentityEntryInput.Kind.SecretKey,
            IdentityEntryInput.Kind.EncryptedSecretKey,
            -> {
                onSignInValue?.invoke(value) ?: onSignIn()
            }
        }
    }
    BackHandler {
        if (busy) onSystemBackWhileBusy?.invoke() else onBack()
    }
    OnboardingPinnedActionPage(
        title = stringResource(R.string.sign_in),
        onBack = { if (!busy) onBack() },
        actions = {
            if (amberSignerAvailable) {
                WhiteNoiseOutlinedButton(
                    onClick = onLoginWithAmber,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding.sign_in.amber"),
                ) {
                    Text(
                        stringResource(
                            when {
                                loggingInWithAmber && amberSignInStage == 1 ->
                                    R.string.amber_signin_waiting_request
                                loggingInWithAmber && amberSignInStage != null ->
                                    R.string.amber_signin_waiting_proof
                                else -> R.string.onboarding_login_with_amber
                            },
                        ),
                    )
                }
            }
            WhiteNoiseButton(
                onClick = ::submit,
                enabled = canSignIn,
                loading = busy && !loggingInWithAmber,
                loadingLabel = stringResource(R.string.onboarding_signing_in),
                modifier = Modifier.fillMaxWidth().testTag("onboarding.sign_in.action"),
            ) { Text(stringResource(R.string.sign_in)) }
        },
    ) {
        SignInKeyField(
            key,
            busy,
            errorRes,
            ::reportKeyEdit,
            onErrorChange,
            ::submit,
            onBunkerClipboardPaste = { field.clipboardPaste = it },
        )
        if (onUseRemoteSigner != null) {
            WhiteNoiseOutlinedButton(
                onClick = { onUseRemoteSigner(RemoteSignerEntry()) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("onboarding.sign_in.remote_signer"),
            ) { Text(stringResource(R.string.remote_signer_use)) }
        }
        if (offlineErrorVisible) OnboardingOfflineNotice(onOfflineRetry)
    }
    if (recoveryConsentVisible && !busy) {
        WhiteNoiseAlertDialog(
            ownerKey = identity,
            onDismissRequest = onRecoveryConsentDismiss,
            title = { Text(stringResource(R.string.sign_in_recovery_title)) },
            text = { Text(stringResource(R.string.sign_in_recovery_message)) },
            confirmButton = {
                TextButton(onClick = { close(onRecoveryConsentConfirm) }) {
                    Text(stringResource(R.string.sign_in_recovery_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
