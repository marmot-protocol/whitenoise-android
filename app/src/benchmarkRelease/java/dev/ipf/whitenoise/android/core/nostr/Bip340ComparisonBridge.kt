package dev.ipf.whitenoise.android.core.nostr

/** Exposes both implementations to the benchmark APK from the same minified app variant. */
object Bip340ComparisonBridge {
    fun legacySignature(): Boolean = LegacyBip340BenchmarkVerifier.verify(PUBLIC_KEY, MESSAGE, SIGNATURE)

    fun replacementSignature(): Boolean = BIP340.verify(PUBLIC_KEY, MESSAGE, SIGNATURE)

    fun legacyFullEvent(): Boolean = legacyVerifies(SIGNED_EVENT)

    fun replacementFullEvent(): Boolean = NostrEventVerifier.verifies(SIGNED_EVENT)

    fun legacyRejectsInvalidSignature(): Boolean =
        !LegacyBip340BenchmarkVerifier.verify(
            PUBLIC_KEY,
            MESSAGE,
            INVALID_SIGNATURE,
        )

    fun replacementRejectsInvalidSignature(): Boolean = !BIP340.verify(PUBLIC_KEY, MESSAGE, INVALID_SIGNATURE)

    fun legacyRejectsMutatedEvent(): Boolean = !legacyVerifies(SIGNED_EVENT.copy(content = "mutated"))

    fun replacementRejectsMutatedEvent(): Boolean = !NostrEventVerifier.verifies(SIGNED_EVENT.copy(content = "mutated"))

    private fun legacyVerifies(event: NostrEvent): Boolean {
        val message = event.computedIdHex()
        if (!message.equals(event.id, ignoreCase = true)) return false
        return LegacyBip340BenchmarkVerifier.verify(event.pubkey, message, event.sig)
    }

    private const val SIGNATURE_HEX_LENGTH = 128
    private const val RELEASE_EVENT_TIMESTAMP = 1_800_000_100L
    private const val RELEASE_EVENT_KIND = 30_063
    private const val PUBLIC_KEY = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9"
    private const val MESSAGE = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val SIGNATURE =
        "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
            "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
    private val INVALID_SIGNATURE = "0".repeat(SIGNATURE_HEX_LENGTH)
    private val SIGNED_EVENT =
        NostrEvent(
            id = "753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087",
            pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            createdAt = RELEASE_EVENT_TIMESTAMP,
            kind = RELEASE_EVENT_KIND,
            tags =
                listOf(
                    listOf("d", "org.parres.darkmatter@2026.6.20"),
                    listOf("summary", "Dark Matter release"),
                ),
            content = "",
            sig =
                "4320d14456f14da853d5213bc677ea8e0bb3253dfaca20b46193236709135c4a" +
                    "6c62e46d318a83829a69a4061b0224eb1708c47684d11d3effa1cefa25aa1167",
        )
}
