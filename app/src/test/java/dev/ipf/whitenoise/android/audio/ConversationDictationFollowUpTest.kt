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

    @Test
    fun anotherChatRecordsWhileTheFirstIsTranscribedAndLandsInItsOwnDraft() {
        val fixture = Fixture()
        val controller = fixture.controller
        fixture.drafts["bob"] = TextFieldValue("Hi Bob ", TextRange(7))

        assertTrue(controller.canQueueDictation("account", "bob"))
        assertTrue(controller.queueDictation("account", "bob", "reply-to-bob"))
        assertTrue(controller.followUpRecordingFor("account", "bob"))
        assertFalse(controller.followUpRecordingFor("account", "group"))
        assertEquals(0, fixture.platform.recognizers)

        controller.send()
        controller.complete(fixture.target)

        val promoted = requireNotNull(controller.state.target)
        assertEquals("bob", promoted.groupIdHex)
        assertEquals("Hi Bob ", promoted.capturedDraft.text)
        assertEquals("reply-to-bob", promoted.replyToMessageIdHex)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, controller.requestedDeliveryMode)
        assertEquals(listOf(2L), fixture.platform.adopted)
        assertEquals(1, fixture.platform.recognizers)
    }

    @Test
    fun recordingsFromSeveralChatsArePromotedInTheOrderTheyWereMade() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.queueDictation("account", "bob", null))
        // One microphone: the next chat waits until this recording has been finished.
        assertFalse(controller.canQueueDictation("account", "carol"))
        controller.send()
        assertTrue(controller.queueDictation("account", "carol", null))
        controller.paste()
        assertTrue(controller.continueDictation())
        controller.paste()
        assertEquals(listOf("bob", "carol", "group"), controller.followUp.queue.map { it.target.groupIdHex })

        controller.complete(fixture.target)
        assertEquals("bob", controller.state.target?.groupIdHex)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, controller.requestedDeliveryMode)

        controller.complete(requireNotNull(controller.state.target))
        assertEquals("carol", controller.state.target?.groupIdHex)
        assertEquals(ConversationDictationDeliveryMode.PasteIntoDraft, controller.requestedDeliveryMode)

        controller.complete(requireNotNull(controller.state.target))
        assertEquals("group", controller.state.target?.groupIdHex)
        assertEquals(listOf(2L, 3L, 4L), fixture.platform.adopted)
        assertTrue(controller.followUp.queue.isEmpty())
    }

    @Test
    fun theQueueIsBounded() {
        val fixture = Fixture()
        val controller = fixture.controller
        repeat(ConversationDictationFollowUpOwner.MAX_QUEUED) { index ->
            assertTrue(controller.queueDictation("account", "chat$index", null))
            controller.paste()
        }
        assertFalse(controller.canQueueDictation("account", "another"))
        assertFalse(controller.queueDictation("account", "another", null))
    }

    @Test
    fun anUndeliveredDictationBlocksOnlyAQueuedSendInItsOwnChat() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.queueDictation("account", "bob", null))
        controller.send()
        assertTrue(controller.queueDictation("account", "group", null))
        controller.send()

        controller.cancelSession()
        assertEquals("bob", controller.state.target?.groupIdHex)
        assertFalse(controller.followUpSendBlocked(requireNotNull(controller.state.sessionId)))

        controller.complete(requireNotNull(controller.state.target))
        assertEquals("group", controller.state.target?.groupIdHex)
        assertTrue(controller.followUpSendBlocked(requireNotNull(controller.state.sessionId)))
    }

    @Test
    fun aDeliveredSendConsumesTheReplyOnlyInItsOwnChat() {
        val fixture = Fixture()
        val controller = fixture.controller
        controller.state = ConversationDictationState.Processing(1L, fixture.target.copy(replyToMessageIdHex = "a1"))
        controller.completion.intent.choose(ConversationDictationDeliveryMode.SendOnFinish)
        assertTrue(controller.continueDictation())
        controller.paste()
        assertTrue(controller.queueDictation("account", "bob", "b1"))
        controller.paste()

        controller.complete(requireNotNull(controller.state.target))
        assertEquals("group", controller.state.target?.groupIdHex)
        assertNull(controller.state.target?.replyToMessageIdHex)
        controller.complete(requireNotNull(controller.state.target))
        assertEquals("bob", controller.state.target?.groupIdHex)
        assertEquals("b1", controller.state.target?.replyToMessageIdHex)
    }

    @Test
    fun continuingInTheActiveChatAndQueueingANewReplyKeepTheirDestinations() {
        val fixture = Fixture()
        val controller = fixture.controller
        val first = fixture.target.copy(replyToMessageIdHex = "a1")
        controller.state = ConversationDictationState.Processing(1L, first)
        controller.completion.intent.choose(ConversationDictationDeliveryMode.SendOnFinish)
        assertTrue(controller.queueDictation("account", "bob", "b1"))
        controller.paste()
        assertTrue(controller.continueDictation())
        controller.paste()

        controller.complete(first)
        assertEquals("bob", controller.state.target?.groupIdHex)
        assertTrue(controller.queueDictation("account", "group", "a2"))
        controller.paste()
        controller.complete(requireNotNull(controller.state.target))
        assertEquals("group", controller.state.target?.groupIdHex)
        assertNull(controller.state.target?.replyToMessageIdHex)
        controller.complete(requireNotNull(controller.state.target))
        assertEquals("a2", controller.state.target?.replyToMessageIdHex)
    }

    @Test
    fun removingAChatDropsOnlyItsWaitingRecordings() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.queueDictation("account", "bob", null))
        controller.paste()
        assertTrue(controller.queueDictation("account", "carol", null))
        controller.paste()

        fixture.removed += "bob"
        controller.onTargetRemoved("account", "bob")

        assertEquals(listOf("carol"), controller.followUp.queue.map { it.target.groupIdHex })
        assertEquals(listOf(2L), fixture.platform.discarded)
        fixture.platform.discardedCaptureClosed()
        controller.complete(fixture.target)
        assertEquals("carol", controller.state.target?.groupIdHex)
    }

    @Test
    fun anUnavailableChatCannotQueue() {
        val fixture = Fixture()
        fixture.removed += "bob"
        assertFalse(fixture.controller.canQueueDictation("account", "bob"))
        assertFalse(fixture.controller.queueDictation("account", "bob", null))
    }

    @Test
    fun aHeldGestureFollowsItsRecordingThroughPromotion() {
        val fixture = Fixture()
        val controller = fixture.controller
        assertTrue(controller.queueDictation("account", "bob", null))
        val gesture = controller.gestureSessionId
        assertEquals(2L, gesture)

        controller.complete(fixture.target)

        assertEquals("bob", controller.state.target?.groupIdHex)
        assertEquals(gesture, controller.gestureSessionId)
        assertTrue(controller.captureInProgress)
    }

    private class Fixture {
        val platform = FakePlatform()
        val drafts = mutableMapOf<String, TextFieldValue>()
        val removed = mutableSetOf<String>()
        var draft: TextFieldValue
            get() = drafts["group"] ?: TextFieldValue()
            set(value) {
                drafts["group"] = value
            }
        val target = ConversationDictationTarget("account", "group", TextFieldValue(), 0L, ConversationDictationMode.InApp)
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, group -> ConversationDictationDraftSnapshot(drafts[group] ?: TextFieldValue(), 0L) },
                writeDraft = { _, group, _, value ->
                    drafts[group] = value
                    0L
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
                targetAvailable = { _, group -> group !in removed },
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
        val open = mutableSetOf<Long>()
        val discarded = mutableListOf<Long>()
        val adopted = mutableListOf<Long>()
        lateinit var discardedCaptureClosed: () -> Unit

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun followUpCaptureSupported() = true

        override fun followUpCaptures() =
            object : ConversationDictationFollowUpCaptures {
                override fun beginFollowUp(sessionId: Long): Boolean {
                    captures++
                    open += sessionId
                    return true
                }

                override fun finishFollowUp(
                    sessionId: Long,
                    onClosed: () -> Unit,
                ): Boolean {
                    onClosed()
                    return sessionId in open
                }

                override fun discardFollowUp(
                    sessionId: Long,
                    onClosed: () -> Unit,
                ): Boolean {
                    if (!open.remove(sessionId)) return false
                    discarded += sessionId
                    discardedCaptureClosed = onClosed
                    return true
                }

                override fun adoptFollowUp(sessionId: Long): Boolean {
                    adopted += sessionId
                    return open.remove(sessionId)
                }
            }

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
