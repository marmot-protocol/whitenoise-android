package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi

/**
 * What White Noise shows for one remote-signer (NIP-46) account. MDK owns the session, transport, credentials and
 * grant policy; this is only a display projection of its local status snapshot.
 */
internal enum class RemoteSignerHealth {
    /** Paired and either connected or idle until the next signing request. */
    Paired,
    Connecting,
    Reconnecting,

    /** The signer asked for an extra approval step in the browser. */
    ApprovalNeeded,

    /** The required permission set changed; the user must review it again before signing resumes. */
    PermissionReview,

    /** The signer or its relays did not answer. */
    Unreachable,

    /** The signer declined the last request. */
    Declined,

    /** The connection was interrupted; reconnecting or pairing again restores signing. */
    Disconnected,

    /** This device's pairing was revoked or ended; pairing again restores signing. */
    PairingEnded,
    ;

    /** Whether the account needs the user's help before it can sign again. */
    val needsAttention: Boolean
        get() = this !in setOf(Paired, Connecting, Reconnecting)

    /** Whether a pairing exists to disconnect; an ended pairing can only be paired again. */
    val canDisconnect: Boolean
        get() = this != PairingEnded
}

/** One account's signer status; [authUrl] and [requestId] belong to [generation] only. */
internal data class RemoteSignerAccountStatus(
    val health: RemoteSignerHealth,
    val authUrl: String? = null,
    val requestId: String? = null,
    val generation: ULong = 0u,
)

/** Maps MDK's typed local status, without keys or URIs, to the account's display health. */
internal fun Nip46StatusFfi.toAccountStatus(): RemoteSignerAccountStatus {
    val health =
        when (state) {
            Nip46StateFfi.READY -> RemoteSignerHealth.Paired
            Nip46StateFfi.CONNECTING, Nip46StateFfi.LISTENING -> RemoteSignerHealth.Connecting
            Nip46StateFfi.RECONNECTING -> RemoteSignerHealth.Reconnecting
            Nip46StateFfi.APPROVAL ->
                if (authUrl != null && requestId != null) {
                    RemoteSignerHealth.ApprovalNeeded
                } else {
                    RemoteSignerHealth.Connecting
                }
            Nip46StateFfi.PERMISSION_APPROVAL -> RemoteSignerHealth.PermissionReview
            Nip46StateFfi.DISCONNECTED -> RemoteSignerHealth.Disconnected
            Nip46StateFfi.CANCELLED, Nip46StateFfi.LOGGED_OUT -> RemoteSignerHealth.PairingEnded
            Nip46StateFfi.UNAVAILABLE -> lastError.toHealth()
        }
    return if (health == RemoteSignerHealth.ApprovalNeeded) {
        RemoteSignerAccountStatus(health, authUrl, requestId, generation)
    } else {
        RemoteSignerAccountStatus(health, generation = generation)
    }
}

/** An idle restored session has no error; it connects on the next signing request. */
private fun Nip46ErrorFfi?.toHealth(): RemoteSignerHealth =
    when (this) {
        null -> RemoteSignerHealth.Paired
        is Nip46ErrorFfi.Rejected -> RemoteSignerHealth.Declined
        is Nip46ErrorFfi.PermissionApprovalRequired -> RemoteSignerHealth.PermissionReview
        is Nip46ErrorFfi.LoggedOut, is Nip46ErrorFfi.Cancelled -> RemoteSignerHealth.PairingEnded
        is Nip46ErrorFfi.Disconnected, is Nip46ErrorFfi.Mismatch -> RemoteSignerHealth.Disconnected
        else -> RemoteSignerHealth.Unreachable
    }
