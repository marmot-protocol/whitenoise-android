package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.marmotkit.Nip46PermissionsFfi
import dev.ipf.marmotkit.Nip46SessionFfiInterface
import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi
import kotlinx.coroutines.CompletableDeferred

/** The grant set MDK 0.17.0 requests, in its exact order. */
internal val mdkRemoteSignerGrants =
    listOf(
        "sign_event:450",
        "sign_event:30443",
        "sign_event:13",
        "sign_event:22242",
        "sign_event:10002",
        "sign_event:10050",
        "sign_event:5",
        "sign_event:0",
        "sign_event:3",
        "sign_event:10000",
        "sign_event:24242",
        "sign_event:451",
        "sign_event:30078",
        "nip44_encrypt",
        "nip44_decrypt",
    )

/** A scripted MDK session: connect and pairing offers wait for the test to answer them. */
internal class FakeRemoteSignerSession(
    private val grants: List<String> = mdkRemoteSignerGrants,
) : Nip46SessionFfiInterface {
    var status = Nip46StatusFfi(Nip46StateFfi.PERMISSION_APPROVAL, null, null, null, 0u)
    var approved: Nip46PermissionsFfi? = null
    var reviewed = 0
    var cancelled = false
    val completedAuth = mutableListOf<String>()
    var connectReply = CompletableDeferred<String>()
    var offerReply = CompletableDeferred<String>()
    var connects = 0
    var offers = 0

    override suspend fun approvePermissions(
        version: UInt,
        grants: List<String>,
    ) {
        approved = Nip46PermissionsFfi(version, grants)
    }

    override fun cancel() {
        cancelled = true
        connectReply.completeExceptionally(Nip46ErrorFfi.Cancelled())
    }

    override fun cancelRequest(requestId: String) = Unit

    override fun completeAuth(requestId: String) {
        completedAuth += requestId
    }

    override suspend fun connect(): String {
        connects++
        return connectReply.await()
    }

    override fun disconnect() = Unit

    override fun export(): String = error("not exported in tests")

    override suspend fun logout() = Unit

    override suspend fun pairingOffer(): String {
        offers++
        return offerReply.await()
    }

    override fun permissions(): Nip46PermissionsFfi = Nip46PermissionsFfi(1u, grants)

    override suspend fun ping() = Unit

    override fun reviewPermissions(): Nip46PermissionsFfi {
        reviewed++
        return permissions()
    }

    override fun stateJson(): String = "{}"

    override fun status(): Nip46StatusFfi = status

    override fun uri(): String = error("the offer comes from pairingOffer")
}

/** Hands out [sessions] in order and records what the flow finished with. */
internal class FakeRemoteSignerHost(
    private vararg val sessions: FakeRemoteSignerSession,
) : RemoteSignerPairingHost {
    val configs = mutableListOf<String>()
    val finished = mutableListOf<Pair<Nip46SessionFfiInterface, String>>()
    val spentLinks = mutableListOf<String>()
    var onFinish: () -> Unit = {}
    var finishError: Throwable? = null
    var failNextSession = false
    private var handedOut = 0

    override suspend fun newSession(configJson: String): Nip46SessionFfiInterface {
        configs += configJson
        if (configJson.contains("not-a-link")) throw Nip46ErrorFfi.Invalid()
        if (failNextSession) {
            failNextSession = false
            throw Nip46ErrorFfi.Unavailable()
        }
        return sessions[minOf(handedOut++, sessions.lastIndex)]
    }

    /** Records exactly which links the native handshake spent. */
    override fun onBunkerLinkSpent(
        link: String,
        paste: BunkerClipboardPaste?,
    ) {
        spentLinks += link
    }

    /** Lets the test model sign-in disposing the composition during the account handoff. */
    override suspend fun finish(
        session: Nip46SessionFfiInterface,
        userHex: String,
    ) {
        onFinish()
        finishError?.let { throw it }
        finished += session to userHex
    }
}
