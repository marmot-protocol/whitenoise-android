package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.Nip46SessionFfi
import dev.ipf.marmotkit.Nip46SessionFfiInterface
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.loginWithRemoteSigner
import dev.ipf.whitenoise.android.state.repairRemoteSigner
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connects the pairing flow to this app's MDK runtime: new sessions, then login or re-pair of one account. The
 * runtime that created the session is the only one it is handed to.
 */
private class AppRemoteSignerHost(
    private val appState: WhiteNoiseAppState,
    private val repairAccountHex: String?,
) : RemoteSignerPairingHost {
    private var runtime: MarmotInterface? = null

    override suspend fun newSession(configJson: String): Nip46SessionFfiInterface {
        val current = appState.marmot()
        runtime = current
        return withContext(Dispatchers.IO) { current.nip46New(configJson) }
    }

    override suspend fun finish(
        session: Nip46SessionFfiInterface,
        userHex: String,
    ) {
        val owner = checkNotNull(runtime) { "Remote signer session has no runtime" }
        val native = session as Nip46SessionFfi
        if (repairAccountHex != null) {
            appState.repairRemoteSigner(repairAccountHex, native, owner)
        } else {
            appState.loginWithRemoteSigner(native, owner)
        }
    }
}

/**
 * Hosts one remote-signer pairing. [initialLink] starts on the bunker-link step with that link filled in;
 * [repairAccountHex] re-pairs that existing account instead of signing in. [onFinished] runs once the account owns
 * the session, [onClose] when the user leaves first.
 */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerFlow(
    appState: WhiteNoiseAppState,
    onClose: () -> Unit,
    onFinished: () -> Unit = onClose,
    initialLink: String? = null,
    repairAccountHex: String? = null,
) {
    val context = LocalContext.current
    val pairing =
        remember(appState, repairAccountHex) {
            val host = AppRemoteSignerHost(appState, repairAccountHex)
            RemoteSignerPairing(host, appState.mutationsScope, repairAccountHex)
        }
    val finished by rememberUpdatedState(onFinished)
    DisposableEffect(pairing) { onDispose { pairing.dispose() } }
    LaunchedEffect(pairing) { if (initialLink != null) pairing.choose(RemoteSignerMethod.BunkerLink, initialLink) }
    LaunchedEffect(pairing.step) {
        if (pairing.step == RemoteSignerStep.Done) {
            // The link carries the pairing secret; once used it leaves the clipboard, as a submitted key does.
            pairing.usedLink?.let { clearClipboardIfHolding(context, it) }
            finished()
        }
    }
    RemoteSignerScreen(
        pairing = pairing,
        onClose = onClose,
        onOpenApproval = context::openRemoteSignerApproval,
    )
}

/** A request to open the pairing flow; [link] is a bunker link already entered in the key field. */
internal class RemoteSignerEntry(
    val link: String?,
)

/** Clears the clipboard only while it still holds [used]. */
private fun clearClipboardIfHolding(
    context: Context,
    used: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val text =
        clipboard.primaryClip
            ?.takeIf { it.itemCount == 1 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    if (text?.trim() == used) clearSensitiveClipboard(context)
}
