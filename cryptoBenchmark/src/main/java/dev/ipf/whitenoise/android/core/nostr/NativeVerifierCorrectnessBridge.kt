package dev.ipf.whitenoise.android.core.nostr

/** Boolean entry points keep the separate test APK independent of obfuscated DTOs. */
object NativeVerifierCorrectnessBridge {
    fun acceptsSignedTextEvent(): Boolean = NostrEventVerifier.verifies(TEXT_EVENT)

    fun rejectsOutOfRangeSignatureScalars(): Boolean =
        listOf(
            TEXT_EVENT.copy(sig = FIELD_PRIME_HEX + TEXT_EVENT.sig.takeLast(64)),
            TEXT_EVENT.copy(sig = TEXT_EVENT.sig.take(64) + CURVE_ORDER_HEX),
        ).all { !NostrEventVerifier.verifies(it) }

    fun rejectsOffCurvePublicKey(): Boolean {
        val changed = TEXT_EVENT.copy(pubkey = FIELD_PRIME_HEX)
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

    // Public fixture from the pinned Rust Nostr event tests.
    private val TEXT_EVENT =
        NostrEvent(
            id = "cb8feca582979d91fe90455867b34dbf4d65e4b86e86b3c68c368ca9f9eef6f2",
            pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            createdAt = 1_707_409_439L,
            kind = 1,
            tags = listOf(listOf("-")),
            content = "hello members of the secret group",
            sig =
                "fa163f5cfb75d77d9b6269011872ee22b34fb48d23251e9879bb1e4ccbdd8aaaf" +
                    "4b6dc5f5084a65ef42c52fbcde8f3178bac3ba207de827ec513a6aa39fa684c",
        )
    private const val FIELD_PRIME_HEX = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f"
    private const val CURVE_ORDER_HEX = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
}
