package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.GROUP_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.MESSAGE_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.TARGET_REF
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.appState
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.controller
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.native
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.summary
import dev.ipf.whitenoise.android.ui.conversation.createPollAction
import dev.ipf.whitenoise.android.ui.conversation.stickerSheetAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Polls and stickers sent while replying carry MDK's reply tags and clear only the reply they carried. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ReplyTaggedSendsTest {
    /** The tags match MDK's own text reply, and only a lowercase 32-byte hex event id is accepted. */
    @Test
    fun replyTagsMatchTheTextReplyShape() {
        assertEquals(REPLY_TAGS, replyMessageTags(MESSAGE_ID))
        assertTrue(isPublishableReplyTarget(MESSAGE_ID))
        listOf(PENDING_ID, MESSAGE_ID.dropLast(2), MESSAGE_ID.uppercase(), "zz".repeat(32)).forEach { target ->
            assertFalse(isPublishableReplyTarget(target))
            assertThrows(IllegalArgumentException::class.java) { replyMessageTags(target) }
        }
    }

    /** A poll created while replying goes through the tagged call on the pinned account and ends the reply. */
    @Test
    fun pollReplyCarriesTheReplyTagsAndClearsTheReply() =
        runTest {
            val recorder = SendRecorder()
            val controller = controller(appState(recorder.engine))
            controller.replyingTo = record(MESSAGE_ID)

            assertTrue(controller.createLunchPoll())

            val (name, args) = recorder.sends.single()
            assertEquals("createTaggedPoll", name)
            assertEquals(listOf(TARGET_REF, GROUP_ID, "Lunch?", listOf("Soup", "Salad")), args.take(4))
            assertEquals(REPLY_TAGS, args.last())
            assertNull(controller.replyingTo)
        }

    /** Without a reply selected, the poll carries no extra tags. */
    @Test
    fun pollWithoutAReplySendsNoTags() =
        runTest {
            val recorder = SendRecorder()

            assertTrue(controller(appState(recorder.engine)).createLunchPoll())

            assertEquals(emptyList<List<String>>(), recorder.onlyTags())
        }

    /** A sticker sent while replying goes through the tagged call with the sticker's reference. */
    @Test
    fun stickerReplyCarriesTheReplyTagsAndClearsTheReply() =
        runTest {
            val recorder = SendRecorder()
            val controller = controller(appState(recorder.engine))
            controller.replyingTo = record(MESSAGE_ID)

            assertTrue(controller.sendSticker(STICKER, controller.snapshotReply()))

            val (name, args) = recorder.sends.single()
            assertEquals("sendTaggedSticker", name)
            assertEquals(
                listOf(TARGET_REF, GROUP_ID, StickerRefFfi(PACK, "wave", "ab".repeat(32)), REPLY_TAGS),
                args,
            )
            assertNull(controller.replyingTo)
        }

    /** A reply picked while MDK is still sending stays selected, and the send keeps its own target. */
    @Test
    fun aReplyPickedDuringTheSendIsKept() =
        runTest {
            val recorder = SendRecorder()
            val controller = controller(appState(recorder.engine))
            controller.replyingTo = record(MESSAGE_ID)
            val newer = record(OTHER_ID)
            recorder.onSend = { controller.replyingTo = newer }

            assertTrue(controller.sendSticker(STICKER, controller.snapshotReply()))

            assertEquals(REPLY_TAGS, recorder.onlyTags())
            assertSame(newer, controller.replyingTo)
        }

    /**
     * A reply to a message still being sent has only a client token, so a sticker or poll sends nothing and says,
     * as a text reply does, that the message hasn't been sent yet. The reply stays selected.
     */
    @Test
    fun replyToAnUnsentMessageIsNeverPublished() =
        runTest {
            val attempts =
                listOf<suspend ConversationController.() -> Boolean>(
                    { sendSticker(STICKER, snapshotReply()) },
                    { createLunchPoll() },
                )
            attempts.forEach { attempt ->
                val recorder = SendRecorder()
                val state = appState(recorder.engine)
                val controller = controller(state)
                controller.timeline = listOf(localSend(record(PENDING_ID), MessageStatus.Pending))
                val pending = record(PENDING_ID)
                controller.replyingTo = pending

                assertFalse(controller.replyAllowsTaggedSend())
                assertFalse(attempt(controller))
                assertEquals(AppText.Resource(R.string.reply_target_not_sent), state.toast?.title)
                assertEquals(emptyList<Pair<String, List<Any?>>>(), recorder.sends)
                assertSame(pending, controller.replyingTo)
            }
        }

    /** A reply captured while its message was still sending goes out with the event id that message has by then. */
    @Test
    fun aReplyCapturedBeforeItsTargetWasAcceptedUsesTheEventId() =
        runTest {
            val recorder = SendRecorder()
            val controller = controller(appState(recorder.engine))
            controller.timeline = listOf(localSend(record(PENDING_ID), MessageStatus.Pending))
            controller.replyingTo = record(PENDING_ID)
            val reply = controller.snapshotReply()
            controller.timeline = listOf(localSend(record(MESSAGE_ID), MessageStatus.Sent))

            assertTrue(controller.sendSticker(STICKER, reply))

            assertEquals(REPLY_TAGS, recorder.onlyTags())
            assertNull(controller.replyingTo)
        }

    /** Once the replied-to send is accepted, its row's event id replaces the client token the reply kept. */
    @Test
    fun replyToAnAcceptedSendUsesItsEventId() =
        runTest {
            val recorder = SendRecorder()
            val controller = controller(appState(recorder.engine))
            controller.replyingTo = record(PENDING_ID)
            controller.timeline = listOf(localSend(record(MESSAGE_ID), MessageStatus.Sent))

            assertTrue(controller.replyAllowsTaggedSend())
            assertTrue(controller.sendSticker(STICKER, controller.snapshotReply()))

            assertEquals(REPLY_TAGS, recorder.onlyTags())
            assertNull(controller.replyingTo)
        }

    /** A projected row that carries the reply's client token also resolves it, without the row identity. */
    @Test
    fun replyToAProjectedSendUsesItsClientToken() =
        runTest {
            val recorder = SendRecorder()
            val controller = controller(appState(recorder.engine))
            controller.replyingTo = record(PENDING_ID)
            controller.timeline =
                listOf(
                    TimelineMessage(
                        id = "msg:$MESSAGE_ID",
                        record = record(MESSAGE_ID),
                        status = MessageStatus.Sent,
                        projected = projected(MESSAGE_ID, clientToken = PENDING_ID),
                    ),
                )

            assertTrue(controller.replyAllowsTaggedSend())
            assertTrue(controller.sendSticker(STICKER, controller.snapshotReply()))

            assertEquals(REPLY_TAGS, recorder.onlyTags())
        }

    /** A failed send reports the failure and leaves the reply in place for another try. */
    @Test
    fun failedStickerKeepsTheReply() =
        runTest {
            val recorder = SendRecorder(failure = IllegalStateException("relay refused"))
            val state = appState(recorder.engine)
            val controller = controller(state)
            val selected = record(MESSAGE_ID)
            controller.replyingTo = selected

            assertFalse(controller.sendSticker(STICKER, controller.snapshotReply()))

            assertEquals("sendTaggedSticker", recorder.sends.single().first)
            assertSame(selected, controller.replyingTo)
            assertNotNull(state.toast)
        }

    /** Create poll and Stickers are offered with no reply and with a reply to a message that has an event id. */
    @Test
    fun menuActionsAreOfferedForAPublishableReply() =
        runTest {
            val controller = controller(appState(SendRecorder().engine))

            assertSame(OPEN, controller.createPollAction(OPEN))
            assertSame(OPEN, controller.stickerSheetAction(OPEN))
            controller.replyingTo = record(MESSAGE_ID)
            assertSame(OPEN, controller.createPollAction(OPEN))
            assertSame(OPEN, controller.stickerSheetAction(OPEN))
        }

    /** Both actions hide while the reply has only a client token, and return once that send is accepted. */
    @Test
    fun menuActionsWaitForTheReplyTargetToBeAccepted() =
        runTest {
            val controller = controller(appState(SendRecorder().engine))
            controller.timeline = listOf(localSend(record(PENDING_ID), MessageStatus.Pending))
            controller.replyingTo = record(PENDING_ID)

            assertNull(controller.createPollAction(OPEN))
            assertNull(controller.stickerSheetAction(OPEN))
            controller.timeline = listOf(localSend(record(MESSAGE_ID), MessageStatus.Sent))
            assertSame(OPEN, controller.createPollAction(OPEN))
            assertSame(OPEN, controller.stickerSheetAction(OPEN))
        }

    /** An edit hides Stickers, since a sticker cannot join an existing message, but leaves Create poll. */
    @Test
    fun stickersHideDuringAnEdit() =
        runTest {
            val controller = controller(appState(SendRecorder().engine))
            controller.editingMessageId = MESSAGE_ID

            assertNull(controller.stickerSheetAction(OPEN))
            assertSame(OPEN, controller.createPollAction(OPEN))
        }

    /** Creates the two-option lunch poll, replying to whatever the composer has selected. */
    private suspend fun ConversationController.createLunchPoll(): Boolean =
        createPoll("Lunch?", listOf("Soup", "Salad"), PollTypeFfi.SINGLE_CHOICE, null, snapshotReply())

    /** MDK's projection of the user's own send [id], admitted under [clientToken]. */
    private fun projected(
        id: String,
        clientToken: String,
    ) = TimelineMessageRecordFfi(
        clientToken = clientToken,
        messageIdHex = id,
        sourceMessageIdHex = id,
        direction = "sent",
        groupIdHex = GROUP_ID,
        sender = "e5".repeat(32),
        plaintext = "Original",
        contentTokens = EMPTY_MARKDOWN,
        kind = 9uL,
        tags = emptyList(),
        timelineAt = 1uL,
        receivedAt = 1uL,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        poll = null,
        groupSystem = null,
        hasReports = false,
        edit = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletionSource = DeletionSourceFfi.UNKNOWN,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = 1uL,
        retentionSeconds = null,
        retentionExpiresAt = null,
        sticker = null,
        importedFrom = null,
        recipientConfirmed = false,
        historyImportProvenance = null,
    )

    /** A row of the user's own send started under [PENDING_ID], now showing [record] in [status]. */
    private fun localSend(
        record: AppMessageRecordFfi,
        status: MessageStatus,
    ) = TimelineMessage(
        id = "msg:${record.messageIdHex}",
        record = record,
        status = status,
        presentationId = "msg:$PENDING_ID",
    )

    /**
     * Records the poll and sticker sends, without the trailing coroutine continuation, and answers them with
     * [failure] or a published summary. Background reads are not scripted here and fail like any unused call.
     */
    private class SendRecorder(
        private val failure: Throwable? = null,
    ) {
        val sends = mutableListOf<Pair<String, List<Any?>>>()

        /** Runs inside the native send, before it returns, as a user acting while MDK is still sending would. */
        var onSend: () -> Unit = {}
        val engine =
            native { name, args ->
                if (name !in SEND_CALLS) throw UnsupportedOperationException("Unused native test call: $name")
                sends += name to args.dropLast(1)
                onSend()
                failure?.let { throw it }
                summary()
            }

        /** The tags argument of the only recorded send. */
        fun onlyTags(): Any? = sends.single().second.last()
    }

    private companion object {
        val SEND_CALLS = setOf("createTaggedPoll", "sendTaggedSticker")
        val OPEN: () -> Unit = {}
        val OTHER_ID = "d4".repeat(32)
        const val PENDING_ID = "6f1d2a3b-0c4d-4e5f-8a9b-0c1d2e3f4a5b"
        val REPLY_TAGS = listOf(listOf("e", MESSAGE_ID), listOf("q", MESSAGE_ID))
        val PACK = "30031:${"cd".repeat(32)}:pack"
        val EMPTY_MARKDOWN =
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        val STICKER =
            StickerFfi(
                PACK,
                "wave",
                "https://example.invalid/wave.webp",
                "ab".repeat(32),
                "image/webp",
                null,
                null,
                null,
                null,
            )

        /** A received chat message with [id], the shape the composer keeps as its reply selection. */
        fun record(id: String) =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = GROUP_ID,
                sender = "f6".repeat(32),
                plaintext = "Original",
                contentTokens = EMPTY_MARKDOWN,
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = 1uL,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
                sticker = null,
            )
    }
}
