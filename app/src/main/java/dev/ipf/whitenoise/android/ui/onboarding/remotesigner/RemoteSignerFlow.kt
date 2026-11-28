package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

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
    override fun onBunkerLinkSpent(
        link: String,
        paste: BunkerClipboardPaste?,
    ) = clearClipboardIfHolding(appState.appContext, link, paste)

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
 * Hosts one remote-signer pairing. An [entry] with a link starts on the bunker-link step with that link filled in;
 * [repairAccountHex] re-pairs that existing account instead of signing in. [onFinished] runs once the account owns
 * the session, [onClose] when the user leaves first.
 */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerFlow(
    appState: WhiteNoiseAppState,
    onClose: () -> Unit,
    onFinished: () -> Unit = onClose,
    entry: RemoteSignerEntry? = null,
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
    LaunchedEffect(pairing) {
        entry?.link?.let { pairing.choose(RemoteSignerMethod.BunkerLink, it, entry.clipboardPaste) }
    }
    LaunchedEffect(pairing.step) {
        if (pairing.step == RemoteSignerStep.Done) finished()
    }
    RemoteSignerScreen(
        pairing = pairing,
        onClose = onClose,
        onOpenApproval = context::openRemoteSignerApproval,
    )
}

/** Opens pairing with an optional link and the evidence from pasting that link in the key field. */
internal class RemoteSignerEntry(
    val link: String? = null,
    val clipboardPaste: BunkerClipboardPaste? = null,
)
