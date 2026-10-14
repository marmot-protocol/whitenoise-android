package dev.ipf.whitenoise.android.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TranslationPrivacyGateTest {
    @Test
    fun networkProviderGetsNoTextUntilExactIdentityIsAcknowledged() =
        runTest {
            val engine = CountingEngine(TranslationTrust.NETWORK_CAPABLE)
            for (consent in listOf(null, engine.identity.copy(version = 2))) {
                try {
                    translateWithPrivacyGate(engine, "private text", "", "de", consent)
                    fail("unacknowledged provider ran")
                } catch (failure: TranslationException) {
                    assertEquals(TranslationFailure.CONSENT_REQUIRED, failure.failure)
                }
            }
            assertEquals(0, engine.requests)
            assertEquals("translated", translateWithPrivacyGate(engine, "private text", "", "de", engine.identity))
            assertEquals(1, engine.requests)
        }

    @Test
    fun verifiedLocalAdapterDoesNotShowRemoteWarning() =
        runTest {
            val engine = CountingEngine(TranslationTrust.LOCAL)
            assertEquals("translated", translateWithPrivacyGate(engine, "text", "", "fr"))
            assertEquals(1, engine.requests)
        }

    @Test
    fun malformedLanguageAndOversizedRequestNeverReachProvider() =
        runTest {
            val engine = CountingEngine(TranslationTrust.LOCAL)
            for ((text, target) in listOf("text" to "", "text" to "../../secret", "x".repeat(16_385) to "en")) {
                try {
                    translateWithPrivacyGate(engine, text, "", target)
                    fail("invalid request ran")
                } catch (failure: TranslationException) {
                    assertEquals(TranslationFailure.INVALID_REQUEST, failure.failure)
                }
            }
            assertEquals(0, engine.requests)
        }

    private class CountingEngine(
        override val trust: TranslationTrust,
    ) : TranslationEngine {
        override val identity = TranslationEngineIdentity("provider", "verified", 1)
        var requests = 0

        override suspend fun translate(
            text: String,
            source: String,
            target: String,
        ): String {
            requests += 1
            return "translated"
        }
    }
}
