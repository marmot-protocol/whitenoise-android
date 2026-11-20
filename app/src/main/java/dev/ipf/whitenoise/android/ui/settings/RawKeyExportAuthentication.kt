package dev.ipf.whitenoise.android.ui.settings

import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.APP_LOCK_ALLOWED_AUTHENTICATORS
import dev.ipf.whitenoise.android.state.isAppLockCredentialAvailable
import dev.ipf.whitenoise.android.ui.findActivity

/** A fresh, one-shot challenge for the selected raw export destination. */
internal fun interface RawKeyExportAuthentication {
    fun request(onAuthenticated: () -> Unit)
}

@Composable
internal fun rememberRawKeyExportAuthentication(
    owner: ProfileKeysUiState,
    onUnavailable: () -> Unit,
): RawKeyExportAuthentication {
    val context = LocalContext.current
    val latestUnavailable = rememberUpdatedState(onUnavailable)
    val authentication =
        remember(context, owner) {
            RawKeyExportPrompt(context.findActivity() as? FragmentActivity) { latestUnavailable.value() }
        }
    DisposableEffect(authentication) {
        onDispose { authentication.cancel() }
    }
    return authentication
}

/** A dedicated Fragment keeps this prompt's callback separate from MainActivity's App Lock prompt. */
private class RawKeyExportPrompt(
    private val activity: FragmentActivity?,
    private val onUnavailable: () -> Unit,
) : RawKeyExportAuthentication {
    private var host: Fragment? = null
    private var prompt: BiometricPrompt? = null

    override fun request(onAuthenticated: () -> Unit) {
        if (host != null) return
        if (activity == null || activity.supportFragmentManager.isStateSaved || !isAppLockCredentialAvailable(activity)) {
            onUnavailable()
            return
        }
        val fragment = Fragment()
        runCatching {
            activity.supportFragmentManager.beginTransaction().add(fragment, null).commitNow()
            host = fragment
            val callback =
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (retire(fragment, cancelPrompt = false)) onAuthenticated()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        retire(fragment, cancelPrompt = false)
                    }
                }
            val challenge =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(activity.getString(R.string.raw_key_export_auth_title))
                    .setAllowedAuthenticators(APP_LOCK_ALLOWED_AUTHENTICATORS)
                    .build()
            BiometricPrompt(fragment, ContextCompat.getMainExecutor(activity), callback)
                .also { prompt = it }
                .authenticate(challenge)
        }.onFailure {
            retire(fragment, cancelPrompt = true)
            onUnavailable()
        }
    }

    fun cancel() {
        host?.let { retire(it, cancelPrompt = true) }
    }

    /** Invalidates the attempt before cancelling or accepting a terminal callback. */
    private fun retire(
        fragment: Fragment,
        cancelPrompt: Boolean,
    ): Boolean {
        if (host !== fragment) return false
        host = null
        val activePrompt = prompt
        prompt = null
        if (cancelPrompt) activePrompt?.cancelAuthentication()
        activity?.supportFragmentManager?.beginTransaction()?.remove(fragment)?.commitAllowingStateLoss()
        return true
    }
}

/** Gives each presentation its own consent, so reopening cannot accept a retired callback. */
internal fun ProfileKeysUiState.openRawExport() {
    rawExportConsent = Any()
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
        } else if (ui.rawExportDialog && ui.exportIdle && canDeliverSecret()) {
            val consent = ui.rawExportConsent
            request {
                if (ui.rawExportConsent === consent && ui.rawExportDialog && ui.exportIdle && canDeliverSecret()) {
                    beginExport(false, destination)
                }
            }
        }
    }
