package dev.ipf.whitenoise.android.ui.settings

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.APP_LOCK_ALLOWED_AUTHENTICATORS
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isAppLockCredentialAvailable
import dev.ipf.whitenoise.android.ui.findActivity

/** A fresh, one-shot challenge for the selected raw export destination. */
internal fun interface RawKeyExportAuthentication {
    fun request(onAuthenticated: () -> Unit)

    /** Invalidates the result before dismissing a pending challenge. */
    fun cancel() = Unit
}

@Composable
internal fun rememberRawKeyExportAuthentication(
    owner: ProfileKeysUiState,
    appState: WhiteNoiseAppState,
): RawKeyExportAuthentication {
    val context = LocalContext.current
    val authentication =
        remember(context, owner, appState) {
            RawKeyExportPrompt(context.findActivity()) {
                appState.present(R.string.toast_app_lock_screen_lock_required)
            }
        }
    DisposableEffect(authentication) {
        onDispose { authentication.cancel() }
    }
    return authentication
}

/** Uses the framework prompt so profile-key callbacks are independent of App Lock's AndroidX host. */
private class RawKeyExportPrompt(
    private val activity: Activity?,
    private val onUnavailable: () -> Unit,
) : RawKeyExportAuthentication {
    private var activeRequest: CancellationSignal? = null

    /** Shows a fresh credential challenge owned by one screen action. */
    override fun request(onAuthenticated: () -> Unit) {
        if (activeRequest != null) return
        val host = activity?.takeUnless { it.isFinishing || it.isDestroyed }
        if (host == null || !isAppLockCredentialAvailable(host)) {
            onUnavailable()
            return
        }
        val request = CancellationSignal()
        activeRequest = request
        runCatching {
            BiometricPrompt
                .Builder(host)
                .setTitle(host.getString(R.string.raw_key_export_auth_title))
                .setAllowedAuthenticators(APP_LOCK_ALLOWED_AUTHENTICATORS)
                .build()
                .authenticate(
                    request,
                    host.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (finish(request)) onAuthenticated()
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            finish(request)
                        }
                    },
                )
        }.onFailure {
            if (finish(request)) request.cancel()
            onUnavailable()
        }
    }

    /** Retires the owner synchronously even when the framework callback is posted later. */
    override fun cancel() {
        activeRequest?.let { request ->
            if (finish(request)) request.cancel()
        }
    }

    /** Accepts only the first terminal event for this exact challenge. */
    private fun finish(request: CancellationSignal): Boolean {
        if (activeRequest !== request) return false
        activeRequest = null
        return true
    }
}

/** Gives each presentation its own consent, so reopening cannot accept a retired callback. */
internal fun ProfileKeysUiState.openRawExport() {
    rawKeyConsent = Any()
    rawExportDialog = true
}

/** The callback can start preparation only while the same screen can still deliver account secrets. */
internal fun RawKeyExportAuthentication.gateExport(
    ui: ProfileKeysUiState,
    canDeliverSecret: () -> Boolean,
    beginExport: (Boolean, KeyExportDestination) -> Unit,
): (Boolean, KeyExportDestination) -> Unit =
    { encrypted, destination ->
        if (encrypted) {
            beginExport(true, destination)
        } else {
            authorize(ui, { ui.rawExportDialog && ui.exportIdle && canDeliverSecret() }) {
                beginExport(false, destination)
            }
        }
    }

/** Applies the same one-shot ownership check before every plaintext key disclosure. */
internal fun RawKeyExportAuthentication.authorize(
    ui: ProfileKeysUiState,
    canDeliver: () -> Boolean,
    onAuthenticated: () -> Unit,
) {
    if (canDeliver()) {
        val consent = ui.rawKeyConsent
        request {
            if (ui.rawKeyConsent === consent && canDeliver()) onAuthenticated()
        }
    }
}
