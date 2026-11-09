package dev.ipf.whitenoise.android.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTranslationStateTest {
    @Test
    fun typingSettlesBeforeOneRequestAndSendUsesTheTranslation() =
        runTest {
            val requests = mutableListOf<String>()
            val state =
                state(backgroundScope) { text, target ->
                    requests.add(text)
                    "$target:$text"
                }
            state.toggle()
            state.update("h")
            advanceTimeBy(SETTLE / 2)
            state.update("hi")
            assertTrue(state.pending)
            assertNull(state.outgoingFor("hi"))
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals(listOf("hi"), requests)
            assertFalse(state.pending)
            assertEquals("de:hi", state.outgoingFor("hi"))
            // A translation of earlier text is never sent for newer text.
            assertNull(state.outgoingFor("hi there"))
        }

    @Test
    fun blankTextPassesThroughSoAttachmentsCanSendWithoutCaption() =
        runTest {
            val state = state(backgroundScope) { _, _ -> error("blank text must not reach the engine") }
            state.toggle()
            state.update("")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals("", state.outgoingFor(""))
        }

    @Test
    fun lateResultForReplacedTextIsRejected() =
        runTest {
            val first = CompletableDeferred<String>()
            val state =
                state(backgroundScope) { text, _ ->
                    if (text == "first") withContext(NonCancellable) { first.await() } else "second translated"
                }
            state.toggle()
            state.update("first")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            state.update("second")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            first.complete("first translated")
            runCurrent()
            assertEquals("second translated", state.result?.text)
            assertNull(state.outgoingFor("first"))
        }

    @Test
    fun resultIsDroppedWhenTheOwnerIsNoLongerCurrent() =
        runTest {
            var current = true
            val response = CompletableDeferred<String>()
            val state = state(backgroundScope, { current }) { _, _ -> response.await() }
            state.toggle()
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            current = false
            response.complete("hallo")
            runCurrent()
            assertNull(state.result)
            assertNull(state.outgoingFor("hello"))
        }

    @Test
    fun engineFailureBlocksSend() =
        runTest {
            val state =
                state(backgroundScope) { _, _ -> throw TranslationException(TranslationFailure.MISSING_ENGINE) }
            state.toggle()
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals(TranslationFailure.MISSING_ENGINE, state.failure)
            assertNull(state.outgoingFor("hello"))
        }

    @Test
    fun changingTargetSavesItAndInvalidatesTheShownTranslation() =
        runTest {
            val saved = mutableListOf<String>()
            val state =
                ComposerTranslationState(
                    backgroundScope,
                    { engine { text, target -> "$target:$text" } },
                    { true },
                    "de",
                    onTargetSaved = { saved.add(it) },
                    settleMillis = SETTLE,
                )
            state.toggle()
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            val epoch = state.epoch
            state.selectTarget("fr")
            assertEquals(listOf("fr"), saved)
            assertTrue(state.epoch > epoch)
            assertNull(state.outgoingFor("hello"))
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals("fr:hello", state.outgoingFor("hello"))
        }

    @Test
    fun closingStopsSendingTranslations() =
        runTest {
            val state = state(backgroundScope) { text, _ -> "t:$text" }
            state.toggle()
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            state.close()
            assertFalse(state.active)
            assertNull(state.result)
        }

    private fun state(
        scope: CoroutineScope,
        current: () -> Boolean = { true },
        translate: suspend (String, String) -> String,
    ) = ComposerTranslationState(scope, { engine(translate) }, current, "de", settleMillis = SETTLE)

    private fun engine(translate: suspend (String, String) -> String) =
        object : TranslationEngine {
            override val identity = TranslationEngineIdentity("local", "verified", 1)
            override val trust = TranslationTrust.LOCAL

            override suspend fun translate(
                text: String,
                source: String,
                target: String,
            ): String = translate(text, target)
        }

    private companion object {
        const val SETTLE = 400L
    }
}
