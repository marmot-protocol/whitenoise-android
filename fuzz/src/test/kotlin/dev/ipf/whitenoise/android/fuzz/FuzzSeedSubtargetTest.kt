package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

class FuzzSeedSubtargetTest {
    @TestFactory
    fun checkedInSeedsSelectSubtargetNamedInFilename(): List<DynamicTest> =
        SEED_EXPECTATIONS.map { expectation ->
            DynamicTest.dynamicTest(expectation.seedName) {
                val provider = ByteArrayFuzzedDataProvider(expectation.seedPath.readBytes())
                assertEquals(
                    expectation.expectedSubtargetId,
                    provider.consumeSubtarget(expectation.subtargetCount),
                    "seed ${expectation.seedName} must route to ${expectation.subtargetLabel}",
                )
                assertSeedPayload(expectation.seedName, provider)
            }
        }

    private fun assertSeedPayload(
        seedName: String,
        provider: ByteArrayFuzzedDataProvider,
    ) {
        when {
            seedName.startsWith("nostr_event_") ->
                assertTrue(provider.consumeParserInput().trimStart().startsWith("{"), "$seedName must replay JSON")
            seedName.startsWith("relay_envelope_") -> {
                assertTrue(provider.consumeBoolean(), "$seedName must select direct relay replay")
                assertTrue(provider.consumeParserInput().trimStart().startsWith("["), "$seedName must replay a relay array")
            }
            seedName == "plausible_clipboard.input" -> {
                val field = provider.consumeDirectOrFramedString()
                assertTrue(field.consumedAllRemaining, "$seedName must select direct clipboard replay")
                assertTrue(field.value.startsWith("nostr:"), "$seedName must replay a Nostr reference")
            }
            seedName.startsWith("profile_link_") ||
                seedName.startsWith("recipient_normalize_") ||
                seedName.startsWith("recipient_tokenize") -> {
                assertTrue(provider.consumeBoolean(), "$seedName must select direct identity replay")
                assertFalse(provider.consumeParserInput().first().isISOControl(), "$seedName must not retain an old selector prefix")
            }
            seedName == "parse_content_row.input" || seedName == "parse_activity_result.input" ->
                assertTrue(provider.consumeParserInput().startsWith("get_public_key|"), "$seedName must replay a NIP-55 row")
            seedName == "signed_event_pubkey_helpers.input" -> {
                val event = provider.consumeDirectOrFramedString()
                assertFalse(event.consumedAllRemaining, "$seedName must select structured helper replay")
                assertTrue(event.value.startsWith("{\"pubkey\":"), "$seedName must frame the signed event first")
                assertEquals(64, provider.consumeFramedString().value.length, "$seedName must frame the expected pubkey")
                assertEquals("com.example.signer", provider.consumeFramedString().value)
                assertEquals("com.example.signer", provider.consumeFramedString().value)
                assertEquals("com.evil.signer", provider.consumeFramedString().value)
            }
            else -> error("Unhandled fuzz seed payload: $seedName")
        }
    }

    private data class SeedExpectation(
        val seedPath: Path,
        val seedName: String,
        val subtargetCount: Int,
        val expectedSubtargetId: Int,
        val subtargetLabel: String,
    )

    companion object {
        private val resourcesRoot =
            Path.of("src/test/resources/dev/ipf/whitenoise/android/fuzz")

        private val SEED_EXPECTATIONS: List<SeedExpectation> =
            Files.walk(resourcesRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.name.endsWith(".input") }
                    .map { path -> expectationFor(path) }
                    .toList()
            }

        private fun expectationFor(seedPath: Path): SeedExpectation {
            val name = seedPath.name
            return when {
                name.startsWith("nostr_event_") ->
                    seedExpectation(
                        seedPath,
                        ZapstoreSubtarget.COUNT,
                        ZapstoreSubtarget.NostrEventJson.ordinal,
                        ZapstoreSubtarget.NostrEventJson.name,
                    )
                name.startsWith("relay_envelope_sequence") ->
                    seedExpectation(
                        seedPath,
                        ZapstoreSubtarget.COUNT,
                        ZapstoreSubtarget.RelayEnvelopeSequence.ordinal,
                        ZapstoreSubtarget.RelayEnvelopeSequence.name,
                    )
                name.startsWith("relay_envelope_") ->
                    seedExpectation(
                        seedPath,
                        ZapstoreSubtarget.COUNT,
                        ZapstoreSubtarget.RelayEnvelopeFrames.ordinal,
                        ZapstoreSubtarget.RelayEnvelopeFrames.name,
                    )
                name.startsWith("profile_link_") ->
                    seedExpectation(
                        seedPath,
                        IdentitySubtarget.COUNT,
                        IdentitySubtarget.ProfileLink.ordinal,
                        IdentitySubtarget.ProfileLink.name,
                    )
                name.startsWith("recipient_normalize_") ->
                    seedExpectation(
                        seedPath,
                        IdentitySubtarget.COUNT,
                        IdentitySubtarget.RecipientNormalize.ordinal,
                        IdentitySubtarget.RecipientNormalize.name,
                    )
                name.startsWith("recipient_tokenize") ->
                    seedExpectation(
                        seedPath,
                        IdentitySubtarget.COUNT,
                        IdentitySubtarget.RecipientTokenize.ordinal,
                        IdentitySubtarget.RecipientTokenize.name,
                    )
                name.startsWith("plausible_clipboard") ->
                    seedExpectation(
                        seedPath,
                        IdentitySubtarget.COUNT,
                        IdentitySubtarget.PlausibleClipboard.ordinal,
                        IdentitySubtarget.PlausibleClipboard.name,
                    )
                name == "parse_content_row.input" ->
                    seedExpectation(
                        seedPath,
                        Nip55Subtarget.COUNT,
                        Nip55Subtarget.ParseContentRow.ordinal,
                        Nip55Subtarget.ParseContentRow.name,
                    )
                name == "parse_activity_result.input" ->
                    seedExpectation(
                        seedPath,
                        Nip55Subtarget.COUNT,
                        Nip55Subtarget.ParseActivityResult.ordinal,
                        Nip55Subtarget.ParseActivityResult.name,
                    )
                name == "signed_event_pubkey_helpers.input" ->
                    seedExpectation(
                        seedPath,
                        Nip55Subtarget.COUNT,
                        Nip55Subtarget.SignedEventPubkeyHelpers.ordinal,
                        Nip55Subtarget.SignedEventPubkeyHelpers.name,
                    )
                else -> error("Unhandled fuzz seed: $name")
            }
        }

        private fun seedExpectation(
            seedPath: Path,
            subtargetCount: Int,
            expectedSubtargetId: Int,
            subtargetLabel: String,
        ): SeedExpectation =
            SeedExpectation(
                seedPath = seedPath,
                seedName = seedPath.name,
                subtargetCount = subtargetCount,
                expectedSubtargetId = expectedSubtargetId,
                subtargetLabel = subtargetLabel,
            )
    }
}
