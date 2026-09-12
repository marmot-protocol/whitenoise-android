package dev.ipf.whitenoise.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.reserveSnackbarSpace
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
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
    privateKeyState: TextFieldState? = null,
    amberSignerAvailable: Boolean = false,
    loggingInWithAmber: Boolean = false,
    amberSignInStage: Int? = null,
    onLoginWithAmber: () -> Unit = {},
    onSignInValue: ((String) -> Unit)? = null,
    onSystemBackWhileBusy: (() -> Unit)? = null,
) {
    WindowSecureFlag()
    // The real onboarding owner supplies the native state; the fallback retains existing standalone callers/tests.
    val key = privateKeyState ?: remember { TextFieldState(identity) }
    val changed by rememberUpdatedState(onIdentityChange)
    var lastReportedKey by remember(key) { mutableStateOf(key.text.toString()) }

    fun reportKeyEdit(value: String) {
        if (value != lastReportedKey) {
            lastReportedKey = value
            changed(value)
        }
    }
    if (privateKeyState == null) {
        LaunchedEffect(identity) {
            if (key.text.toString() != identity) key.setTextAndPlaceCursorAtEnd(identity)
        }
    }
    LaunchedEffect(key) {
        snapshotFlow { key.text.toString() }.drop(1).collect { reportKeyEdit(it) }
    }
    val canSignIn = key.text.isNotBlank() && !busy

    fun submit() {
        if (busy) return
        val value = key.text.toString()
        // Flush a just-typed edit before native work; its queued observer cannot erase the resulting error or consent.
        reportKeyEdit(value)
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
    WhiteNoiseScaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { WhiteNoiseTopBar(stringResource(R.string.sign_in), onBack = { if (!busy) onBack() }) },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .reserveSnackbarSpace()
                    .padding(WhiteNoiseSpacing.PinnedActionInset),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = OnboardingMaxContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
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
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = OnboardingMaxContentWidth)
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(rememberScrollState())
                    .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                SignInKeyField(key, busy, errorRes, ::reportKeyEdit, onErrorChange, ::submit)
                if (offlineErrorVisible) OnboardingOfflineNotice(onOfflineRetry)
            }
        }
    }
    if (recoveryConsentVisible && !busy) {
        WhiteNoiseAlertDialog(
            onDismissRequest = onRecoveryConsentDismiss,
            title = { Text(stringResource(R.string.sign_in_recovery_title)) },
            text = { Text(stringResource(R.string.sign_in_recovery_message)) },
            confirmButton = {
                TextButton(onClick = onRecoveryConsentConfirm) {
                    Text(stringResource(R.string.sign_in_recovery_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onRecoveryConsentDismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
