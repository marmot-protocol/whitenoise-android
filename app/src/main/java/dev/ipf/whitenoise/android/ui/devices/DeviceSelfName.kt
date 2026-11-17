@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceSelfNaming
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.deviceModelLabel
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private const val DEVICE_SELF_NAME_RETRY_MS = 5_000L

// Every language the app ships, since the approving device sends the placeholder in its own language.
private val PLACEHOLDER_LANGUAGES = listOf("en", "de", "es", "fr", "it", "pt", "ru", "tr", "zh", "zh-Hant")

/** The placeholder device name in every language the app ships, so one sent by any approving device is known. */
internal fun placeholderDeviceLabels(context: Context): Set<String> =
    PLACEHOLDER_LANGUAGES.mapTo(mutableSetOf()) { tag ->
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        context.createConfigurationContext(configuration).getString(R.string.link_request_default_label)
    }

/**
 * While the app is in the foreground, names this device with its model for the active account when MDK's roster
 * lists it without a name. It follows MDK's roster events, so a device that has just been linked is named without
 * the user opening Linked devices. Accounts that sign through an external signer app are left alone.
 */
@Composable
internal fun DeviceSelfNameEffect(appState: WhiteNoiseAppState) {
    val account = appState.activeAccount?.takeIf { it.localSigning && !it.signedOut }?.label
    val runtimeGeneration = appState.runtimeGeneration
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(account, runtimeGeneration, lifecycle) {
        val accountRef = account ?: return@LaunchedEffect
        val naming =
            DeviceSelfNaming(
                accountRef = accountRef,
                modelLabel = deviceModelLabel(Build.MODEL),
                readDevices = { ref -> appState.marmotIo { linkedDevices(ref) } },
                rename = { ref, slot, label -> appState.marmotIo { renameLinkedDevice(ref, slot, label) } },
                placeholders = placeholderDeviceLabels(appState.appContext),
            )
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val subscription = runCatchingCancellable { appState.marmotIo { subscribeEvents() } }.getOrNull()
                try {
                    if (subscription == null) {
                        naming.nameIfUnnamed()
                    } else {
                        naming.observe { withContext(Dispatchers.IO) { subscription.next() } }
                    }
                } finally {
                    subscription?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { it.destroy() } } }
                }
                delay(DEVICE_SELF_NAME_RETRY_MS)
            }
        }
    }
}
