package dev.ipf.whitenoise.android.amber

import android.content.Context
import android.content.Intent
import dev.ipf.marmotkit.ExternalAccountSignerFfi
import dev.ipf.marmotkit.MarmotKitException
import java.util.UUID

/**
 * NIP-55 (Amber) implementation of the engine's [ExternalAccountSignerFfi].
 *
 * Generated contract implemented here:
 * ```
 * fun publicKey(): String
 * fun signEvent(unsignedEventJson: String): String            // returns SIGNED event JSON
 * fun nip04Encrypt(publicKey: String, content: String): String
 * fun nip04Decrypt(publicKey: String, encryptedContent: String): String
 * fun nip44Encrypt(publicKey: String, content: String): String
 * fun nip44Decrypt(publicKey: String, payload: String): String
 * ```
 * The engine calls each method SYNCHRONOUSLY on a background worker thread and
 * expects a value or a thrown [MarmotKitException] (the UniFFI callback shim
 * lowers `MarmotKitException` to a typed error; any other throwable becomes an
 * untyped "unexpected error"). Each method therefore tries Amber's
 * ContentResolver interface first — answerable in the background once the user
 * granted "remember" — and falls back to a foreground Intent prompt via
 * [AmberActivityCoordinator], which blocks only this worker thread.
 *
 * [accountPubkey] is the signer account's OWN key (hex). For the crypto methods
 * the engine passes the COUNTERPARTY as `publicKey`; the account's own key is
 * always sent as `current_user`.
 *
 * User cancellation / rejection and prompt timeouts map to
 * [MarmotKitException.ExternalSignerRejected]; the absence of a foreground
 * Activity or a saved signer package maps to
 * [MarmotKitException.ExternalSignerUnavailable]; a malformed signer response
 * maps to [MarmotKitException.Runtime].
 */
class AmberExternalSigner(
    private val appContext: Context,
    private val accountPubkey: String,
    private val coordinator: AmberActivityCoordinator = AmberActivityCoordinator,
    private val approvalTimeoutMs: Long = APPROVAL_TIMEOUT_MS,
) : ExternalAccountSignerFfi {
    override fun publicKey(): String = accountPubkey

    override fun signEvent(unsignedEventJson: String): String = request(SignerOp.SignEvent, content = unsignedEventJson, counterparty = null)

    override fun nip04Encrypt(
        publicKey: String,
        content: String,
    ): String = request(SignerOp.Nip04Encrypt, content = content, counterparty = publicKey)

    override fun nip04Decrypt(
        publicKey: String,
        encryptedContent: String,
    ): String = request(SignerOp.Nip04Decrypt, content = encryptedContent, counterparty = publicKey)

    override fun nip44Encrypt(
        publicKey: String,
        content: String,
    ): String = request(SignerOp.Nip44Encrypt, content = content, counterparty = publicKey)

    override fun nip44Decrypt(
        publicKey: String,
        payload: String,
    ): String = request(SignerOp.Nip44Decrypt, content = payload, counterparty = publicKey)

    private fun request(
        op: SignerOp,
        content: String,
        counterparty: String?,
    ): String {
        val packageName =
            Nip55.savedSignerPackage(appContext)
                ?: throw MarmotKitException.ExternalSignerUnavailable(accountPubkey)

        // 1) ContentResolver: background, no prompt once "remember" was granted.
        val args =
            when (op) {
                SignerOp.SignEvent -> arrayOf(content, "", accountPubkey)
                else -> arrayOf(content, counterparty.orEmpty(), accountPubkey)
            }
        when (val row = Nip55.queryViaContentResolver(appContext, op, packageName, args)) {
            is ContentRowOutcome.Value -> return validateSignerValue(op, row.value)
            ContentRowOutcome.Unavailable -> Unit // fall through to the Intent prompt
        }

        if (!Nip55.contentFitsIntentFallbackBudget(content)) {
            throw MarmotKitException.ExternalSignerUnavailable(accountPubkey)
        }

        // 2) Intent prompt on the foreground Activity; blocks THIS worker thread.
        val requestId = newRequestId()
        val intent =
            when (op) {
                SignerOp.SignEvent -> Nip55.buildSignEventIntent(packageName, content, requestId, accountPubkey)
                else -> Nip55.buildCryptoIntent(op, packageName, content, counterparty.orEmpty(), accountPubkey, requestId)
            }
        return when (val outcome = coordinator.awaitApproval(intent, approvalTimeoutMs, requestId)) {
            is AmberActivityCoordinator.Outcome.Completed -> parseCompleted(op, outcome.data, outcome.resultOk, packageName)
            AmberActivityCoordinator.Outcome.NoForegroundActivity ->
                throw MarmotKitException.ExternalSignerUnavailable(accountPubkey)
            AmberActivityCoordinator.Outcome.TimedOut ->
                throw MarmotKitException.ExternalSignerRejected()
        }
    }

    private fun parseCompleted(
        op: SignerOp,
        data: Intent?,
        resultOk: Boolean,
        expectedPackageName: String,
    ): String =
        when (
            val parsed =
                parseActivityResult(
                    op,
                    resultOk,
                    rejected = readRejectedIntentExtra(data),
                    resultExtra = data?.getStringExtra(Nip55.EXTRA_RESULT),
                    eventExtra = data?.getStringExtra(Nip55.EXTRA_EVENT),
                    packageExtra = data?.getStringExtra(Nip55.EXTRA_PACKAGE),
                )
        ) {
            is ActivityResultOutcome.Value -> {
                validateSignerPackageEcho(parsed.packageName, expectedPackageName)
                validateSignerValue(op, parsed.value)
            }
            // Not reachable for sign/crypto ops, but keep the branch total.
            is ActivityResultOutcome.PublicKey -> parsed.pubkey
            ActivityResultOutcome.Rejected -> throw MarmotKitException.ExternalSignerRejected()
            is ActivityResultOutcome.Malformed -> throw MarmotKitException.Runtime(parsed.reason)
        }

    private fun validateSignerValue(
        op: SignerOp,
        value: String,
    ): String {
        if (op != SignerOp.SignEvent) return value
        signedEventPubkeyMismatchReason(value, accountPubkey)?.let { reason ->
            throw MarmotKitException.Runtime(reason)
        }
        return value
    }

    private fun validateSignerPackageEcho(
        packageName: String?,
        expectedPackageName: String,
    ) {
        signerPackageEchoMismatchReason(packageName, expectedPackageName)?.let { reason ->
            throw MarmotKitException.Runtime(reason)
        }
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    companion object {
        const val APPROVAL_TIMEOUT_MS = 120_000L
    }
}
