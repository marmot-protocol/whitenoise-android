package dev.ipf.whitenoise.android.core.nostr

import fr.acinq.secp256k1.Secp256k1

/** BIP-340 verification backed by Bitcoin Core's libsecp256k1 implementation. */
internal object BIP340 {
    fun verify(
        publicKeyHex: String,
        messageHex: String,
        signatureHex: String,
    ): Boolean {
        val publicKey = publicKeyHex.hexToBytes()?.takeIf { it.size == PUBLIC_KEY_BYTES } ?: return false
        val message = messageHex.hexToBytes()?.takeIf { it.size == MESSAGE_BYTES } ?: return false
        val signature = signatureHex.hexToBytes()?.takeIf { it.size == SIGNATURE_BYTES } ?: return false

        return try {
            Secp256k1.verifySchnorr(
                signature = signature,
                data = message,
                pub = publicKey,
            )
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: Secp256k1Exception) {
            false
        }
    }

    private const val PUBLIC_KEY_BYTES = 32
    private const val MESSAGE_BYTES = 32
    private const val SIGNATURE_BYTES = 64
}
