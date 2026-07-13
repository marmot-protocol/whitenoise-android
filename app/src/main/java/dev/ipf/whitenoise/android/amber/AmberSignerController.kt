package dev.ipf.whitenoise.android.amber

import android.content.Context
import dev.ipf.marmotkit.ExternalAccountSignerFfi
import dev.ipf.marmotkit.MarmotKitException
import java.util.UUID

/**
 * Entry points for the Amber (NIP-55) external-signer flow, wrapping the
 * protocol layer and the [AmberActivityCoordinator] for callers (AppState) that
 * hold an application [Context].
 */
class AmberSignerController(
    context: Context,
    private val coordinator: AmberActivityCoordinator = AmberActivityCoordinator,
    private val approvalTimeoutMs: Long = AmberExternalSigner.APPROVAL_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext

    fun isSignerInstalled(): Boolean = Nip55.isExternalSignerInstalled(appContext)

    /**
     * Login-time `get_public_key` round trip: shows Amber's foreground prompt via
     * the coordinator, persists the chosen signer package, and returns the key
     * Amber reported (npub or hex — the engine normalizes it).
     *
     * BLOCKING: run this off the main thread (it blocks on the coordinator while
     * the launcher runs on the main thread). Throws a typed [MarmotKitException]
     * on rejection / unavailability so the caller keeps the user in the flow.
     */
    fun requestPublicKey(): String {
        if (!isSignerInstalled()) throw MarmotKitException.ExternalSignerUnavailable("")
        val requestId = UUID.randomUUID().toString()
        val intent = Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), requestId)
        return when (val outcome = coordinator.awaitApproval(intent, approvalTimeoutMs, requestId)) {
            is AmberActivityCoordinator.Outcome.Completed -> parsePublicKey(outcome)
            AmberActivityCoordinator.Outcome.NoForegroundActivity ->
                throw MarmotKitException.ExternalSignerUnavailable("")
            AmberActivityCoordinator.Outcome.TimedOut ->
                throw MarmotKitException.ExternalSignerRejected()
        }
    }

    private fun parsePublicKey(outcome: AmberActivityCoordinator.Outcome.Completed): String =
        when (
            val parsed =
                parseActivityResult(
                    SignerOp.GetPublicKey,
                    outcome.resultOk,
                    rejected = readRejectedIntentExtra(outcome.data),
                    resultExtra = outcome.data?.getStringExtra(Nip55.EXTRA_RESULT),
                    eventExtra = outcome.data?.getStringExtra(Nip55.EXTRA_EVENT),
                    packageExtra = outcome.data?.getStringExtra(Nip55.EXTRA_PACKAGE),
                )
        ) {
            is ActivityResultOutcome.PublicKey -> {
                parsed.packageName?.let { Nip55.saveSignerPackage(appContext, it) }
                parsed.pubkey
            }
            ActivityResultOutcome.Rejected -> throw MarmotKitException.ExternalSignerRejected()
            is ActivityResultOutcome.Malformed -> throw MarmotKitException.Runtime(parsed.reason)
            is ActivityResultOutcome.Value -> throw MarmotKitException.Runtime("unexpected signer response")
        }

    /** A signer bound to [accountPubkeyHex] (the account's own key, hex). */
    fun buildSigner(accountPubkeyHex: String): ExternalAccountSignerFfi = AmberExternalSigner(appContext, accountPubkeyHex, coordinator, approvalTimeoutMs)

    companion object {
        fun isSignerInstalled(context: Context): Boolean = Nip55.isExternalSignerInstalled(context)
    }
}
