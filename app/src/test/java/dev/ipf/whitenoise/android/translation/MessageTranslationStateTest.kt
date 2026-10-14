package dev.ipf.whitenoise.android.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTranslationStateTest {
    private val source = TranslationSource("message", "original", "de")

    @Test
    fun duplicateCompositionDoesNotCreateDuplicateRequest() =
        runTest {
            val response = CompletableDeferred<String>()
            var requests = 0
            val session =
                session(backgroundScope) {
                    requests += 1
                    response.await()
                }
            repeat(5) { session.request(source) }
            runCurrent()
            assertEquals(1, requests)
            response.complete("translation")
            runCurrent()
            assertEquals("translation", session.renditions[source]?.text)
            session.request(source)
            runCurrent()
            assertEquals(1, requests)
            session.toggle(source)
            assertFalse(session.renditions.getValue(source).showTranslation)
        }

    @Test
    fun accountOrRuntimeRetirementRejectsAnUncancellableLateCallback() =
        runTest {
            val response = CompletableDeferred<String>()
            var current = true
            val session = session(backgroundScope, { current }) { withContext(NonCancellable) { response.await() } }
            session.request(source)
            runCurrent()
            current = false
            response.complete("late plaintext")
            runCurrent()
            assertTrue(session.renditions.isEmpty())
        }

    @Test
    fun editDeleteTargetOrWindowDisposalInvalidatesOldResult() =
        runTest {
            val old = CompletableDeferred<String>()
            val fresh = CompletableDeferred<String>()
            var request = 0
            val session =
                session(backgroundScope) {
                    request += 1
                    withContext(NonCancellable) { if (request == 1) old.await() else fresh.await() }
                }
            session.request(source)
            runCurrent()
            session.clear()
            val newSource = source.copy(text = "edited", target = "fr")
            session.request(newSource)
            runCurrent()
            old.complete("obsolete")
            fresh.complete("new rendition")
            runCurrent()
            assertEquals(setOf(newSource), session.renditions.keys)
            session.remove(newSource)
            assertTrue(session.renditions.isEmpty())
        }

    @Test
    fun automaticFailureIsScopedAndSuppressesFurtherRequestsUntilRetry() =
        runTest {
            var requests = 0
            val session =
                session(backgroundScope) {
                    requests += 1
                    throw TranslationException(TranslationFailure.SOURCE_MODEL)
                }
            session.request(source, automatic = true)
            runCurrent()
            session.request(source.copy(messageId = "next"), automatic = true)
            runCurrent()
            assertEquals(1, requests)
            assertEquals(TranslationFailure.SOURCE_MODEL, session.failure)
            session.retry()
            session.request(source, automatic = true)
            runCurrent()
            assertEquals(2, requests)
        }

    @Test
    fun loadedWindowWorkIsBoundedToTwoSimultaneousRequests() =
        runTest {
            var requests = 0
            val response = CompletableDeferred<String>()
            val session =
                session(backgroundScope) {
                    requests += 1
                    response.await()
                }
            repeat(100) { session.request(source.copy(messageId = "$it"), automatic = true) }
            runCurrent()
            assertEquals(2, requests)
            session.clear()
            assertFalse(session.isPending(source.copy(messageId = "0")))
        }

    @Test
    fun automaticQueueSaturationKeepsCapacityForAManualDifferentTarget() =
        runTest {
            val response = CompletableDeferred<String>()
            val session = session(backgroundScope) { response.await() }
            repeat(16) { session.request(source.copy(messageId = "automatic-$it"), automatic = true) }
            runCurrent()
            assertEquals(15, (0 until 16).count { session.isPending(source.copy(messageId = "automatic-$it")) })
            val manual = source.copy(messageId = "manual", target = "fr")
            session.request(manual, from = "en")
            assertTrue(session.isPending(manual))
            session.request(source.copy(messageId = "extra-automatic"), automatic = true)
            assertFalse(session.isPending(source.copy(messageId = "extra-automatic")))
            response.complete("translated")
            runCurrent()
            assertEquals("translated", session.renditions[manual]?.text)
        }

    @Test
    fun explicitManualSourceReplacesAutomaticWorkAndRejectsItsLateResult() =
        runTest {
            val automatic = CompletableDeferred<String>()
            val languages = mutableListOf<String>()
            val session =
                ConversationTranslationSession(backgroundScope, {
                    object : TranslationEngine {
                        override val identity = TranslationEngineIdentity("local", "verified", 1)
                        override val trust = TranslationTrust.LOCAL

                        override suspend fun translate(
                            text: String,
                            source: String,
                            target: String,
                        ): String {
                            languages.add(source)
                            return if (source.isEmpty()) {
                                withContext(NonCancellable) { automatic.await() }
                            } else {
                                "explicit source rendition"
                            }
                        }
                    }
                }, { true })
            session.request(source, automatic = true)
            runCurrent()
            // The production manual action clears matching work before submitting the selected language.
            session.retry()
            session.remove(source)
            session.request(source, from = "en")
            runCurrent()
            assertEquals(listOf("", "en"), languages)
            assertEquals("explicit source rendition", session.renditions[source]?.text)
            automatic.complete("obsolete detected-language rendition")
            runCurrent()
            assertEquals("explicit source rendition", session.renditions[source]?.text)
        }

    private fun session(
        scope: CoroutineScope,
        current: () -> Boolean = { true },
        translate: suspend () -> String,
    ): ConversationTranslationSession =
        ConversationTranslationSession(scope, {
            object : TranslationEngine {
                override val identity = TranslationEngineIdentity("local", "verified", 1)
                override val trust = TranslationTrust.LOCAL

                override suspend fun translate(
                    text: String,
                    source: String,
                    target: String,
                ): String = translate()
            }
        }, current)
}
