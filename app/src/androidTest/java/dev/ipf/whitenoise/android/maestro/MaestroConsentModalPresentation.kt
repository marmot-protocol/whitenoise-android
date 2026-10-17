package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingSavedAccountActions
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingSavedAccountUi
import dev.ipf.whitenoise.android.ui.onboarding.SignInContent
import dev.ipf.whitenoise.android.ui.onboarding.SignInKeyField

private const val ORIGINAL_FIXTURE_IDENTITY = "fixture original identity"

/** Consent and decoded-result UI uses the real entry components; recovery and camera remain separate. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroConsentModalPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("modal-signin-") -> SignInConsentPresentation(fixture)
        fixture.scenario.startsWith("modal-retained-") -> RetainedConsentPresentation(fixture)
        else -> ScanResultPresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SignInConsentPresentation(fixture: MaestroPresentationFixture) {
    val key = remember(fixture) { TextFieldState(ORIGINAL_FIXTURE_IDENTITY) }
    var showing by remember { mutableStateOf(true) }
    val busy = fixture.scenario.endsWith("busy")
    Box(Modifier.fillMaxSize()) {
        SignInContent(
            identity = ORIGINAL_FIXTURE_IDENTITY,
            privateKeyState = key,
            busy = busy,
            errorRes = null,
            onIdentityChange = { error("Consent must retain the original identity") },
            onErrorChange = {},
            onBack = { fixture.finish("screen-back") },
            onSignIn = { error("Supplied consent cannot start native sign-in") },
            recoveryConsentVisible = showing,
            onRecoveryConsentDismiss = {
                check(key.text.toString() == ORIGINAL_FIXTURE_IDENTITY)
                fixture.record("recovery-dismiss")
                showing = false
            },
            onRecoveryConsentConfirm = {
                check(!busy && key.text.toString() == ORIGINAL_FIXTURE_IDENTITY)
                fixture.finish("recover-consent")
            },
        )
        if (busy || !showing) {
            Button(
                onClick = {
                    check(key.text.toString() == ORIGINAL_FIXTURE_IDENTITY)
                    if (busy) fixture.finish("done") else showing = true
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            ) { Text(if (busy) "Finish consent check" else "Reopen recovery consent") }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun RetainedConsentPresentation(fixture: MaestroPresentationFixture) {
    val account =
        remember(fixture) {
            OnboardingSavedAccountUi(
                label = "fixture-retained",
                accountIdHex = fixture.peerAccountIdHex,
                displayName = "Fixture retained",
                shortIdentity = "Fixture retained public identity",
                avatarUrl = null,
                recoveryRequired = true,
            )
        }
    val disabled = fixture.scenario.endsWith("disabled")
    val busy = fixture.scenario.endsWith("busy")
    Column {
        OnboardingSavedAccountActions(
            accounts = listOf(account),
            reactivatingAccountLabel = if (busy) account.label else null,
            enabled = !disabled,
            onContinue = { error("Recovery-required account cannot resume without consent") },
            onRecover = {
                check(!busy && !disabled && it == account.label)
                fixture.finish("recover-retained")
            },
        )
        if (busy || disabled) {
            Button(onClick = { fixture.finish("done") }) { Text("Finish retained check") }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ScanResultPresentation(fixture: MaestroPresentationFixture) {
    val key = remember(fixture) { TextFieldState(ORIGINAL_FIXTURE_IDENTITY) }
    val publicKeyScan = fixture.scenario.endsWith("public")
    val result =
        remember(fixture, publicKeyScan) {
            if (publicKeyScan) fixture.appState.npubForDisplay(fixture.peerAccountIdHex) else "invalid fixture scan"
        }
    var errorResource by remember { mutableStateOf<Int?>(null) }
    var previous by remember { mutableStateOf<((String) -> Unit)?>(null) }
    Column {
        SignInKeyField(
            key = key,
            busy = false,
            errorRes = errorResource,
            onIdentityChange = { error("Rejected QR input cannot overwrite the original identity") },
            onErrorChange = {
                val expected =
                    if (publicKeyScan) R.string.sign_in_error_public_key else R.string.identity_entry_error_invalid_key
                check(it == expected)
                errorResource = it
                fixture.record("scan-rejected")
            },
            onSubmit = { error("QR error presentation cannot submit a private key") },
            scannerContent = { dismiss, scan ->
                Column {
                    Text("Fixture scanner session")
                    Button(onClick = {
                        previous = scan
                        scan(result)
                    }) { Text("Deliver decoded result") }
                    previous?.let { stale ->
                        Button(onClick = { stale(result) }) { Text("Deliver previous scanner result") }
                    }
                    Button(onClick = dismiss) { Text("Dismiss fixture scanner") }
                }
            },
        )
        Button(onClick = {
            check(key.text.toString() == ORIGINAL_FIXTURE_IDENTITY)
            fixture.finish("done")
        }) { Text("Finish scanner check") }
    }
}
