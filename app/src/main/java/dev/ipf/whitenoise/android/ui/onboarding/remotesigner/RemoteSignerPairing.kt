package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.Nip46PermissionsFfi
import dev.ipf.marmotkit.Nip46SessionFfiInterface
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.appStateDebug
import dev.ipf.whitenoise.android.state.readableMessage
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Relays the generated connection code asks the signer to answer on; signers commonly listen on both. */
internal val remoteSignerOfferRelays = listOf("wss://relay.nsec.app", "wss://nos.lol")

/** What the pairing flow needs from the app: an MDK session and the account step after verification. */
internal interface RemoteSignerPairingHost {
    /** Creates an offline MDK session off the main thread; throws for a malformed link or configuration. */
    suspend fun newSession(configJson: String): Nip46SessionFfiInterface

    /** Signs in with, or reattaches, the verified Ready [session] for account [userHex]. */
    suspend fun finish(
        session: Nip46SessionFfiInterface,
        userHex: String,
    )
}

internal enum class RemoteSignerMethod { BunkerLink, ConnectionCode }

/** One screen of the pairing flow. Secrets live only in [WaitingForSigner.offer] and the link field. */
internal sealed interface RemoteSignerStep {
    data object Choose : RemoteSignerStep

    data object EnterLink : RemoteSignerStep

    data class Review(
        val method: RemoteSignerMethod,
        val permissions: Nip46PermissionsFfi,
    ) : RemoteSignerStep

    data object Connecting : RemoteSignerStep

    /** The generated connection code is on screen while White Noise listens for the signer. */
    class WaitingForSigner(
        val offer: String,
    ) : RemoteSignerStep {
        override fun toString(): String = "WaitingForSigner"
    }

    data class Approval(
        val authUrl: String,
        val host: String,
        val requestId: String,
    ) : RemoteSignerStep

    data object SigningIn : RemoteSignerStep

    data object Done : RemoteSignerStep

    data class Failed(
        val failure: RemoteSignerFailure,
    ) : RemoteSignerStep
}

/**
 * Drives one remote-signer pairing: choose a method, review MDK's exact grant set, pair, verify the account and
 * hand the Ready session to [host]. A replaced or abandoned attempt cannot change the visible step.
 * [expectedAccountHex] makes this a re-pair of that account; a signer for another account is refused.
 */
internal class RemoteSignerPairing(
    private val host: RemoteSignerPairingHost,
    private val scope: CoroutineScope,
    private val expectedAccountHex: String? = null,
    private val offerRelays: List<String> = remoteSignerOfferRelays,
    /** Where MDK's local session reads run, keeping them off the main thread. */
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var step by mutableStateOf<RemoteSignerStep>(RemoteSignerStep.Choose)
        private set
    var method by mutableStateOf<RemoteSignerMethod?>(null)
        private set

    /** The inline error under the link field; editing the field clears it. */
    var linkErrorRes by mutableStateOf<Int?>(null)

    /** The pasted or scanned bunker link; a masked field, never saved state. */
    val link = TextFieldState()

    private var session: Nip46SessionFfiInterface? = null
    private var job: Job? = null
    private var attempt = 0L
    private var resumeStep: RemoteSignerStep = RemoteSignerStep.Connecting
    private var acknowledgedRequest: String? = null
    private var lastOpen: Pair<RemoteSignerMethod, String>? = null

    /** The bunker link the finished pairing used, so the host can clear it from the clipboard; null otherwise. */
    var usedLink: String? = null
        private set

    /** Whether leaving now would abandon an account handoff that must finish. */
    val busy: Boolean
        get() = step == RemoteSignerStep.SigningIn

    /**
     * Opens [chosen]. A connection code starts its session right away; a bunker link waits for input unless [prefill]
     * already holds one, which goes straight to the permission review.
     */
    fun choose(
        chosen: RemoteSignerMethod,
        prefill: String? = null,
    ) {
        if (busy) return
        method = chosen
        linkErrorRes = null
        if (chosen == RemoteSignerMethod.ConnectionCode) {
            val config = JSONObject().put("relays", JSONArray(offerRelays)).put("name", REMOTE_SIGNER_CLIENT_NAME)
            open(chosen, config.toString())
        } else {
            prefill?.let { link.setTextAndPlaceCursorAtEnd(it.trim()) }
            step = RemoteSignerStep.EnterLink
            if (prefill != null && isBunkerLink(prefill)) submitLink()
        }
    }

    /** Starts a session for the entered link, or shows why it is not a bunker link. */
    fun submitLink() {
        if (step != RemoteSignerStep.EnterLink) return
        val value = link.text.toString().trim()
        if (isBunkerLink(value)) {
            open(RemoteSignerMethod.BunkerLink, JSONObject().put("uri", value).toString())
        } else {
            linkErrorRes = R.string.remote_signer_error_not_bunker_link
        }
    }

    /** Approves exactly the reviewed snapshot, then pairs. */
    fun approve() {
        val review = step as? RemoteSignerStep.Review ?: return
        launchAttempt { active ->
            active.approvePermissions(review.permissions.version, review.permissions.grants)
            pair(active)
        }
    }

    /** Tries a failed attempt again on its session, or starts over where reusing it cannot work. */
    fun retry() {
        val current = step as? RemoteSignerStep.Failed ?: return
        val reopen = lastOpen
        when {
            current.failure.startOver -> back()
            // A bunker link's secret is spent once its session connected; the signer needs a fresh link.
            session == null && reopen?.first == RemoteSignerMethod.BunkerLink -> {
                link.clearText()
                step = RemoteSignerStep.EnterLink
            }
            // No session to retry on: creating it failed, or it was already handed to the account step.
            session == null && reopen != null -> open(reopen.first, reopen.second)
            session == null -> back()
            else -> launchAttempt(::pair)
        }
    }

    /** Acknowledges the signer's browser check; the pending request still waits for the signer's reply. */
    fun approvalFinished() {
        val approval = step as? RemoteSignerStep.Approval ?: return
        acknowledgedRequest = approval.requestId
        runCatchingCancellable { session?.completeAuth(approval.requestId) }
        step = resumeStep
    }

    /** Steps back one screen, ending any pairing in progress. Returns false when the flow should close. */
    fun back(): Boolean {
        val current = step
        val handled = busy || (current != RemoteSignerStep.Choose && current != RemoteSignerStep.Done)
        if (!busy && handled) {
            if (current == RemoteSignerStep.EnterLink) link.clearText() else discard()
            val linkPath = method == RemoteSignerMethod.BunkerLink && current != RemoteSignerStep.EnterLink
            step = if (linkPath) RemoteSignerStep.EnterLink else RemoteSignerStep.Choose
        }
        return handled
    }

    /** Ends the flow; an account handoff already in progress finishes on its own. */
    fun dispose() {
        link.clearText()
        usedLink = null
        lastOpen = null
        if (!busy) discard()
    }

    private fun open(
        chosen: RemoteSignerMethod,
        configJson: String,
    ) {
        discard()
        lastOpen = chosen to configJson
        val token = attempt
        step = RemoteSignerStep.Connecting
        job =
            scope.launch {
                val created = runCatchingCancellable { host.newSession(configJson) }.getOrNull()
                val review =
                    created?.let { new ->
                        try {
                            runCatchingCancellable { withContext(sessionDispatcher) { new.reviewPermissions() } }
                        } catch (closed: CancellationException) {
                            // The flow closed before it owned the session; nothing else will end it.
                            new.cancel()
                            throw closed
                        }
                    }
                when {
                    token != attempt -> created?.cancel()
                    created != null && review?.isSuccess == true -> {
                        session = created
                        step = RemoteSignerStep.Review(chosen, review.getOrThrow())
                    }
                    chosen == RemoteSignerMethod.BunkerLink -> {
                        created?.cancel()
                        linkErrorRes = R.string.remote_signer_error_invalid_link
                        step = RemoteSignerStep.EnterLink
                    }
                    else -> {
                        created?.cancel()
                        step = RemoteSignerStep.Failed(RemoteSignerFailure.Unreachable)
                    }
                }
            }
    }

    private fun launchAttempt(block: suspend (Nip46SessionFfiInterface) -> Unit) {
        val active = session ?: return
        val token = ++attempt
        job?.cancel()
        job =
            scope.launch {
                runCatchingCancellable { block(active) }.onFailure { error ->
                    if (token == attempt) step = failedStep(method, active, remoteSignerFailureFor(error))
                }
            }
    }

    /** Pairs, verifies the account, then hands the session over; a replaced attempt stops at its next check. */
    private suspend fun pair(active: Nip46SessionFfiInterface) {
        val token = attempt
        if (method == RemoteSignerMethod.ConnectionCode) {
            step = RemoteSignerStep.Connecting
            resumeStep = RemoteSignerStep.WaitingForSigner(active.pairingOffer())
        } else {
            resumeStep = RemoteSignerStep.Connecting
        }
        if (token == attempt) step = resumeStep
        val onSignal: (ApprovalSignal) -> Unit = { signal ->
            if (signal == ApprovalSignal.Refused) {
                step = RemoteSignerStep.Failed(RemoteSignerFailure.Unsupported)
                discard()
            } else {
                approvalStepFor(signal, step, resumeStep, acknowledgedRequest)?.let { step = it }
            }
        }
        val userHex =
            coroutineScope {
                val isCurrent = { token == attempt && step !is RemoteSignerStep.Failed }
                val readStatus = suspend { withContext(sessionDispatcher) { active.status() } }
                val watcher = launch { awaitSignerApproval(readStatus, isCurrent, onSignal) }
                try {
                    active.connect()
                } finally {
                    watcher.cancel()
                }
            }
        val wrongAccount = expectedAccountHex != null && !userHex.equals(expectedAccountHex, ignoreCase = true)
        when {
            token != attempt -> Unit
            wrongAccount -> step = RemoteSignerStep.Failed(RemoteSignerFailure.WrongAccount)
            else -> {
                // From here the account may own the session: drop the flow's reference so it is never cancelled.
                step = RemoteSignerStep.SigningIn
                session = null
                val handedOff = handOffToAccount(host, active, userHex)
                if (handedOff) {
                    val submitted = link.text.toString()
                    usedLink = submitted.trim().takeIf { method == RemoteSignerMethod.BunkerLink }
                    link.clearText()
                    lastOpen = null
                }
                step =
                    if (handedOff) RemoteSignerStep.Done else RemoteSignerStep.Failed(RemoteSignerFailure.SignInFailed)
            }
        }
    }

    private fun discard() {
        attempt++
        job?.cancel()
        job = null
        session?.cancel()
        session = null
    }
}

private const val REMOTE_SIGNER_CLIENT_NAME = "White Noise"

/**
 * Gives the verified session to the account step. An interruption that is not this flow's own cancellation (for
 * example a replaced runtime) returns false so the flow ends in a retryable failure instead of staying busy.
 */
private suspend fun handOffToAccount(
    host: RemoteSignerPairingHost,
    active: Nip46SessionFfiInterface,
    userHex: String,
): Boolean =
    try {
        host.finish(active, userHex)
        true
    } catch (interrupted: CancellationException) {
        currentCoroutineContext().ensureActive()
        appStateDebug { "remote signer handoff interrupted: ${interrupted.readableMessage()}" }
        false
    }

/**
 * The step a status signal moves the prompt to, or null to stay. A cleared check returns to [resumeStep] so an old
 * prompt never stays actionable; an acknowledged request is not shown again.
 */
private fun approvalStepFor(
    signal: ApprovalSignal,
    current: RemoteSignerStep,
    resumeStep: RemoteSignerStep,
    acknowledged: String?,
): RemoteSignerStep? =
    when (signal) {
        ApprovalSignal.Cleared -> resumeStep.takeIf { current is RemoteSignerStep.Approval }
        is ApprovalSignal.Requested ->
            signal.approval.takeIf { it.requestId != acknowledged && it != current }
        ApprovalSignal.Refused -> null
    }

/** The step after a failed attempt: cancellation steps back, changed grants return to review. */
private fun failedStep(
    method: RemoteSignerMethod?,
    active: Nip46SessionFfiInterface,
    failure: RemoteSignerFailure?,
): RemoteSignerStep =
    when (failure) {
        null -> RemoteSignerStep.Choose
        RemoteSignerFailure.PermissionsChanged ->
            RemoteSignerStep.Review(method ?: RemoteSignerMethod.BunkerLink, active.reviewPermissions())
        else -> RemoteSignerStep.Failed(failure)
    }

/** Whether [value] is a NIP-46 bunker link rather than a key; the key field routes these to this flow. */
internal fun isBunkerLink(value: String): Boolean = value.trim().startsWith("bunker://", ignoreCase = true)

/** The host of an https approval page, or null for any other scheme, which is never opened. */
internal fun remoteSignerApprovalHost(url: String): String? =
    runCatching { java.net.URI(url) }
        .getOrNull()
        ?.takeIf { it.scheme.equals("https", ignoreCase = true) }
        ?.host
        ?.takeIf { it.isNotBlank() }
