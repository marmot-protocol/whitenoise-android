package dev.ipf.whitenoise.android.core.nostr

import org.json.JSONObject

/** Boolean entry points keep the separate test APK independent of obfuscated DTOs. */
object NativeVerifierCorrectnessBridge {
    fun verifiesFixtureJson(json: String): Boolean =
        NostrEvent.fromJson(JSONObject(json))?.let(NostrEventVerifier::verifies) ?: false

    fun verifiesFixtureWithRecomputedId(json: String): Boolean {
        val event = NostrEvent.fromJson(JSONObject(json)) ?: return false
        return NostrEventVerifier.verifies(event.copy(id = event.computedIdHex()))
    }

    fun acceptsSignedTextEvent(): Boolean = NostrEventVerifier.verifies(TEXT_EVENT)

    fun rejectsOutOfRangeSignatureScalars(): Boolean =
        listOf(
            TEXT_EVENT.copy(sig = FIELD_PRIME_HEX + TEXT_EVENT.sig.takeLast(64)),
            TEXT_EVENT.copy(sig = TEXT_EVENT.sig.take(64) + CURVE_ORDER_HEX),
        ).all { !NostrEventVerifier.verifies(it) }

    fun rejectsOutOfRangePublicKey(): Boolean {
        val changed = TEXT_EVENT.copy(pubkey = FIELD_PRIME_HEX)
        return !NostrEventVerifier.verifies(changed.copy(id = changed.computedIdHex()))
    }

    fun rejectsInRangeOffCurvePublicKey(): Boolean {
        // x=0 is below p, but x^3+7 has no square root in the secp256k1 field.
        val changed = TEXT_EVENT.copy(pubkey = "0".repeat(64))
        return !NostrEventVerifier.verifies(changed.copy(id = changed.computedIdHex()))
    }

    fun rejectsMalformedFields(): Boolean =
        listOf(
            TEXT_EVENT.copy(kind = Int.MAX_VALUE),
            TEXT_EVENT.copy(kind = -1),
            TEXT_EVENT.copy(createdAt = -1L),
            TEXT_EVENT.copy(pubkey = "invalid"),
            TEXT_EVENT.copy(sig = "invalid"),
            TEXT_EVENT.copy(id = "invalid"),
        ).all { !NostrEventVerifier.verifies(it) }

    // Canonical event signed with public test scalar 1 and zero auxiliary randomness.
    private val TEXT_EVENT =
        NostrEvent(
            id = "f017727ad7c6b4c872639506b75ad7c8f85e0896f71610fb491625e1c0b8b2e6",
            pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            createdAt = 1_707_409_439L,
            kind = 1,
            tags = listOf(listOf("-")),
            content = "hello members of the secret group",
            sig =
                "05a6cc87a4e03cd93efd843d45ec927b13dedcaf653518c0dc7e929f2cd3588587" +
                    "ea4402009afaa9d4dfff5c7e74a8e15aee9cd71c2aaadf04ff3e7e690a3e9d",
        )
    private const val FIELD_PRIME_HEX = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f"
    private const val CURVE_ORDER_HEX = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
}
