package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationFollowUpTest {
    @Test
    fun followUpRecordsWithoutARecognizerAndUsesTheDraftAfterFirstDelivery() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.continueDictation())
        assertEquals(1, fixture.platform.captures)
        assertEquals(0, fixture.platform.recognizers)

        controller.paste()
        fixture.draft = TextFieldValue("First part", TextRange(0))
        controller.complete(fixture.target)

        assertEquals(1, fixture.platform.recognizers)
        val capturedDraft = requireNotNull(controller.state.target).capturedDraft
        assertEquals(TextRange(0), capturedDraft.selection)
        assertEquals("First part", capturedDraft.text)
        assertNull(controller.followUp.pending)
    }

    @Test
    fun cancelWaitsForNativeClosureBeforeAReplacementRecorderOpens() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.continueDictation())
        controller.cancel()

        assertFalse(controller.continueDictation())
        assertEquals(1, fixture.platform.captures)

        fixture.platform.discardedCaptureClosed()
        assertTrue(controller.continueDictation())
        assertEquals(2, fixture.platform.captures)
        assertTrue(controller.completionActionsEnabled)
    }

    @Test
    fun cancelDiscardsFollowUpWhilePreservingFailedPredecessor() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.continueDictation())
        val failed = ConversationDictationState.Failed(1L, fixture.target, ConversationDictationFailure.TimedOut)
        controller.state = failed

        controller.cancel()

        assertSame(failed, controller.state)
        assertNull(controller.followUp.pending)
        assertEquals(0, fixture.platform.recognizers)
    }

    private class Fixture {
        val platform = FakePlatform()
        var draft = TextFieldValue()
        val target = ConversationDictationTarget("account", "group", draft, 0L, ConversationDictationMode.InApp)
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
                writeDraft = { _, _, _, value ->
                    draft = value
                    0L
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            ).apply {
                nextSessionId = 1L
                state = ConversationDictationState.Processing(1L, target)
                finishRequested = true
                foregroundMicrophoneRequired = false
            }
    }

    private class FakePlatform : ConversationDictationPlatform {
        var captures = 0
        var recognizers = 0
        lateinit var discardedCaptureClosed: () -> Unit

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun followUpCaptureSupported() = true

        override fun beginFollowUpCapture(): Boolean {
            captures++
            return true
        }

        override fun finishFollowUpCapture(onClosed: () -> Unit): Boolean {
            onClosed()
            return true
        }

        override fun discardFollowUpCapture(onClosed: () -> Unit): Boolean {
            discardedCaptureClosed = onClosed
            return true
        }

        override fun adoptFollowUpCapture() = true

        override fun callerAudioHasPending() = true

        override fun createSession(listener: ConversationDictationRecognitionListener): RecognitionSession {
            recognizers++
            return object : RecognitionSession {
                override fun usesCallerAudioCapture() = true

                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }
}
