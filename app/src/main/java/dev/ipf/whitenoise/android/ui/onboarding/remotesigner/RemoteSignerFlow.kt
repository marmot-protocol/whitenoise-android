package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import dev.ipf.whitenoise.android.ui.common.plainText
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

    /** Clears a spent link using the app context while the account still owns its original UI. */
    override fun onBunkerLinkSpent(link: String) = clearClipboardIfHolding(appState.appContext, link)

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
        if (pairing.step == RemoteSignerStep.Done) finished()
    }
    RemoteSignerScreen(
        pairing = pairing,
        onClose = onClose,
        onOpenApproval = { url ->
            // Only an https page the user just saw by host name is opened, in their own browser.
            if (remoteSignerApprovalHost(url) != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // No browser: the prompt stays and the signer's request times out on its own.
                }
            }
        },
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
    try {
        val text = clipboard.primaryClip?.takeIf { it.itemCount == 1 }?.plainText(context)
        if (text?.trim() == used) clearSensitiveClipboard(context)
    } catch (_: SecurityException) {
        // Android may revoke access between the handshake and the clipboard comparison.
    }
}
