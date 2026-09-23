package dev.ipf.whitenoise.android.core.nostr

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
    fun legacySignatureVerification() {
        assertTrue(Bip340ComparisonBridge.legacySignature())
        assertTrue(Bip340ComparisonBridge.legacyRejectsInvalidSignature())
        repeat(EXPLICIT_WARMUP_OPERATIONS) { assertTrue(Bip340ComparisonBridge.legacySignature()) }

        benchmarkRule.measureRepeated {
            Bip340ComparisonBridge.legacySignature()
        }
    }

    @Test
    fun replacementSignatureVerification() {
        assertTrue(Bip340ComparisonBridge.replacementSignature())
        assertTrue(Bip340ComparisonBridge.replacementRejectsInvalidSignature())
        repeat(EXPLICIT_WARMUP_OPERATIONS) { assertTrue(Bip340ComparisonBridge.replacementSignature()) }

        benchmarkRule.measureRepeated {
            Bip340ComparisonBridge.replacementSignature()
        }
    }

    @Test
    fun legacyFullEventVerification() {
        assertTrue(Bip340ComparisonBridge.legacyFullEvent())
        assertTrue(Bip340ComparisonBridge.legacyRejectsMutatedEvent())
        repeat(EXPLICIT_WARMUP_OPERATIONS) { assertTrue(Bip340ComparisonBridge.legacyFullEvent()) }

        benchmarkRule.measureRepeated {
            Bip340ComparisonBridge.legacyFullEvent()
        }
    }

    @Test
    fun replacementFullEventVerification() {
        assertTrue(Bip340ComparisonBridge.replacementFullEvent())
        assertTrue(Bip340ComparisonBridge.replacementRejectsMutatedEvent())
        repeat(EXPLICIT_WARMUP_OPERATIONS) { assertTrue(Bip340ComparisonBridge.replacementFullEvent()) }

        benchmarkRule.measureRepeated {
            Bip340ComparisonBridge.replacementFullEvent()
        }
    }

    private companion object {
        private const val EXPLICIT_WARMUP_OPERATIONS = 20
    }
}
