package dev.ipf.whitenoise.android.core.nostr

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reproducible optimized-device benchmark for the production BIP-340 verification paths. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class Bip340VerificationBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun signatureOnlyVerification() {
        assertTrue(BIP340.verify(PUBLIC_KEY, MESSAGE, SIGNATURE))
        assertFalse(BIP340.verify(PUBLIC_KEY, MESSAGE, "0".repeat(SIGNATURE_HEX_LENGTH)))
        repeat(EXPLICIT_WARMUP_OPERATIONS) { assertTrue(BIP340.verify(PUBLIC_KEY, MESSAGE, SIGNATURE)) }

        benchmarkRule.measureRepeated {
            BIP340.verify(PUBLIC_KEY, MESSAGE, SIGNATURE)
        }
    }

    @Test
    fun fullEventVerification() {
        assertTrue(NostrEventVerifier.verifies(SIGNED_EVENT))
        assertFalse(NostrEventVerifier.verifies(SIGNED_EVENT.copy(content = "mutated")))
        repeat(EXPLICIT_WARMUP_OPERATIONS) { assertTrue(NostrEventVerifier.verifies(SIGNED_EVENT)) }

        benchmarkRule.measureRepeated {
            NostrEventVerifier.verifies(SIGNED_EVENT)
        }
    }

    private companion object {
        private const val EXPLICIT_WARMUP_OPERATIONS = 20
        private const val SIGNATURE_HEX_LENGTH = 128
        private const val PUBLIC_KEY = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9"
        private const val MESSAGE = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val SIGNATURE =
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
        private val SIGNED_EVENT =
            NostrEvent(
                id = "753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087",
                pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                createdAt = 1_800_000_100L,
                kind = 30_063,
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
}
