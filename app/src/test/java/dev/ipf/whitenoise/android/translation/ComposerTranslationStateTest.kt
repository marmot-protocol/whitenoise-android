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
    @Test fun consentCannotApproveAReplacementOrRetiredOwner() =
        runTest {
            var current = true
            var accepted = 0
            val state =
                ComposerTranslationState(
                    scope = backgroundScope,
                    engine = { error("Consent must not contact the engine") },
                    ownerCurrent = { current },
                    initialTarget = "de",
                    noticeAccepted = { false },
                    onNoticeAccepted = { accepted += 1 },
                )
            state.toggle()
            val old = state.consentRequestId
            state.declineNotice(old)
            state.toggle()
            state.acceptNotice(old)
            assertTrue(state.consenting)
            assertEquals(0, accepted)
            current = false
            state.acceptNotice(state.consentRequestId)
            assertFalse(state.consenting)
            assertFalse(state.active)
            assertEquals(0, accepted)
            current = true
            state.toggle()
            state.acceptNotice(state.consentRequestId)
            state.acceptNotice(state.consentRequestId)
            assertEquals(1, accepted)
            assertTrue(state.active)
        }

    /** Typing settles into one request, and Send uses only the translation of the exact current text. */
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

    /** Blank text never reaches the engine and passes through, so attachments can go without a caption. */
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

    /** A result that arrives after the text changed is discarded. */
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

    /** A result is dropped when its chat no longer owns the composer. */
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

    /** An engine failure leaves nothing to send. */
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

    /** Choosing another language saves it and retranslates instead of sending the old translation. */
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

    /** Leaving translate mode makes Send use the typed text again. */
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

    /** Typing back to already translated text cancels the newer request and reuses the shown translation. */
    @Test
    fun typingBackToTheTranslatedTextCancelsTheNewerRequest() =
        runTest {
            val requests = mutableListOf<String>()
            val state =
                state(backgroundScope) { text, target ->
                    requests.add(text)
                    "$target:$text"
                }
            state.toggle()
            state.update("hi")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            state.update("hi!")
            assertTrue(state.pending)
            state.update("hi")
            assertFalse(state.pending)
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals(listOf("hi"), requests)
            assertEquals("de:hi", state.outgoingFor("hi"))
        }

    /** Typing back after a failed edit clears the failure and restores the earlier translation. */
    @Test
    fun typingBackAfterAFailureRestoresTheEarlierTranslation() =
        runTest {
            val state =
                state(backgroundScope) { text, target ->
                    if (text == "hello") "$target:$text" else throw TranslationException(TranslationFailure.CONNECTION)
                }
            state.toggle()
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            state.update("hellox")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals(TranslationFailure.CONNECTION, state.failure)
            assertNull(state.outgoingFor("hellox"))
            state.update("hello")
            assertNull(state.failure)
            assertEquals("de:hello", state.outgoingFor("hello"))
        }

    /** The first toggle asks for the notice; accepting records it and opens translate mode. */
    @Test
    fun firstToggleAsksForTheNoticeAndAcceptingOpensTranslateMode() =
        runTest {
            var accepted = false
            val state = noticeState(backgroundScope, { accepted }) { accepted = true }
            state.toggle()
            assertTrue(state.consenting)
            assertFalse(state.active)
            state.acceptNotice()
            assertTrue(accepted)
            assertFalse(state.consenting)
            assertTrue(state.active)
            state.toggle()
            state.toggle()
            assertFalse(state.consenting)
            assertTrue(state.active)
        }

    /** Declining the notice keeps translate mode off and asks again next time. */
    @Test
    fun decliningTheNoticeLeavesTranslateModeOffAndAsksAgainNextTime() =
        runTest {
            var saved = 0
            val state = noticeState(backgroundScope, { false }) { saved += 1 }
            state.toggle()
            state.declineNotice()
            assertFalse(state.consenting)
            assertFalse(state.active)
            state.acceptNotice()
            assertFalse(state.active)
            assertEquals(0, saved)
            state.toggle()
            assertTrue(state.consenting)
            state.close()
            assertFalse(state.consenting)
        }

    /** Closing for dictation makes Send use the typed text instead of the translation. */
    @Test
    fun closingForDictationSendsTheTypedTextInsteadOfTheTranslation() =
        runTest {
            val state = state(backgroundScope) { text, _ -> "t:$text" }
            state.toggle()
            state.update("hello")
            advanceTimeBy(SETTLE + 1)
            runCurrent()
            assertEquals("t:hello", state.outgoingFor("hello"))
            state.close()
            assertFalse(state.active)
            assertEquals("hello", state.outgoingFor("hello"))
            assertEquals("", state.outgoingFor(""))
        }

    /** A state whose notice acceptance is supplied by the test. */
    private fun noticeState(
        scope: CoroutineScope,
        accepted: () -> Boolean,
        onAccepted: () -> Unit,
    ) = ComposerTranslationState(
        scope,
        { engine { text, _ -> text } },
        { true },
        "de",
        settleMillis = SETTLE,
        noticeAccepted = accepted,
        onNoticeAccepted = onAccepted,
    )

    /** A state translating into German with the test's [translate] and owner check. */
    private fun state(
        scope: CoroutineScope,
        current: () -> Boolean = { true },
        translate: suspend (String, String) -> String,
    ) = ComposerTranslationState(scope, { engine(translate) }, current, "de", settleMillis = SETTLE)

    /** A local engine that delegates to [translate]. */
    private fun engine(translate: suspend (String, String) -> String) =
        object : TranslationEngine {
            override val identity = TranslationEngineIdentity("local", "verified", 1)
            override val trust = TranslationTrust.LOCAL

            /** Delegates to the test's translate function. */
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
