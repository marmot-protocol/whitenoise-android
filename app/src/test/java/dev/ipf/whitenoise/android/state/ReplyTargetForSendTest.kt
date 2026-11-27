package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.GROUP_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.MESSAGE_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.TARGET_REF
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.appState
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.controller
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.native
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A text or media reply to the user's own message never publishes that send's client token as its target: MDK
 * writes any non-empty target into the `e` and `q` tags, so the token is resolved to the event id or refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ReplyTargetForSendTest {
    /** A text reply to a message still sending reaches no native call, and the composer keeps its text and reply. */
    @Test
    fun textReplyToAnUnsentMessageIsRefused() =
        runTest {
            val recorder = NativeRecorder()
            val state = appState(recorder.engine)
            val controller = controller(state, customEmojiReader = { emptyList() })
            controller.timeline = listOf(localSend(record(PENDING_ID), MessageStatus.Pending))
            val pending = record(PENDING_ID)
            controller.replyingTo = pending
            var accepted = false

            controller.send("Reply", onAccepted = { accepted = true })

            assertEquals(emptyList<String>(), recorder.sendCalls())
            assertFalse(accepted)
            assertSame(pending, controller.replyingTo)
            assertEquals(AppText.Resource(R.string.reply_target_not_sent), state.toast?.title)
        }

    /** Once the replied-to send is accepted, the text reply MDK admits names its event id, not the client token. */
    @Test
    fun textReplyToAnAcceptedSendPublishesItsEventId() =
        runTest {
            val recorder =
                NativeRecorder("replyToMessageWithClientToken") { args ->
                    LocalSendAcceptanceFfi(clientToken = args[4] as String, messageIdHex = REPLY_ID)
                }
            val controller = controller(appState(recorder.engine), customEmojiReader = { emptyList() })
            controller.replyingTo = record(PENDING_ID)
            controller.timeline = listOf(localSend(record(MESSAGE_ID), MessageStatus.Sent))

            controller.send("Reply")

            val (_, args) = recorder.calls.single { it.first == "replyToMessageWithClientToken" }
            assertEquals(listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, "Reply"), args.take(4))
        }

    /**
     * A text MDK accepted before its row was projected still shows the client token on that row. The reply
     * takes the event id from the acceptance map, and polls and stickers are offered for it.
     */
    @Test
    fun replyToATextAcceptedBeforeProjectionPublishesItsEventId() =
        runTest {
            val recorder =
                NativeRecorder("replyToMessageWithClientToken") { args ->
                    LocalSendAcceptanceFfi(clientToken = args[4] as String, messageIdHex = REPLY_ID)
                }
            val controller = controller(appState(recorder.engine), customEmojiReader = { emptyList() })
            controller.timeline = listOf(localSend(record(PENDING_ID), MessageStatus.Pending))
            controller.acceptedPendingTextOptimisticIds[MESSAGE_ID] = PENDING_ID
            controller.replyingTo = record(PENDING_ID)

            assertTrue(controller.replyAllowsTaggedSend())
            controller.send("Reply")

            val (_, args) = recorder.calls.single { it.first == "replyToMessageWithClientToken" }
            assertEquals(listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, "Reply"), args.take(4))
        }

    /** A media reply to a message still sending is refused before its reply draft is staged in MDK. */
    @Test
    fun mediaReplyToAnUnsentMessageIsRefused() =
        runTest {
            val recorder = NativeRecorder()
            val state = appState(recorder.engine, activeAccountRef = TARGET_REF)
            val controller = controller(state)
            controller.timeline = listOf(localSend(record(PENDING_ID), MessageStatus.Pending))
            val pending = record(PENDING_ID)
            controller.replyingTo = pending

            assertNull(controller.queueAttachments(listOf(photo()), caption = null))

            assertEquals(emptyList<String>(), recorder.sendCalls())
            assertSame(pending, controller.replyingTo)
            assertEquals(AppText.Resource(R.string.reply_target_not_sent), state.toast?.title)
        }

    /** Once the replied-to send is accepted, the media reply stages and shows its event id, not the client token. */
    @Test
    fun mediaReplyToAnAcceptedSendStagesItsEventId() =
        runTest {
            val recorder =
                NativeRecorder("selectedMessageDraft", "saveMessageDraftIfRevision") {
                    SelectedMessageDraftFfi(revision(), null)
                }
            val state = appState(recorder.engine, activeAccountRef = TARGET_REF)
            val controller = controller(state)
            controller.replyingTo = record(PENDING_ID)
            controller.timeline = listOf(localSend(record(MESSAGE_ID), MessageStatus.Sent))

            val queued = controller.queueAttachments(listOf(photo()), caption = null)

            assertNotNull(queued)
            val (_, staged) = recorder.calls.single { it.first == "saveMessageDraftIfRevision" }
            assertEquals(MESSAGE_ID, staged[3])
            assertEquals(MESSAGE_ID, MessageProjector.replyTargetMessageId(requireNotNull(queued).optimistic))
        }

    /** Owner revocation while native stages a reply rolls back that revision before returning refusal. */
    @Test
    fun mediaReplyOwnerRevokedDuringStagingRollsBackWithoutQueuing() =
        runTest {
            val current = AtomicBoolean(true)
            val selected = revision()
            val staged = revision()
            val recorder =
                NativeRecorder(
                    "selectedMessageDraft",
                    "saveMessageDraftIfRevision",
                    "clearMessageDraftIfRevision",
                ) { args ->
                    if (args.size == 5) {
                        current.set(false)
                        SelectedMessageDraftFfi(staged, null)
                    } else {
                        SelectedMessageDraftFfi(selected, null)
                    }
                }
            val state = appState(recorder.engine, activeAccountRef = TARGET_REF)
            val controller = controller(state)
            controller.replyingTo = record(MESSAGE_ID)

            val queued =
                controller.queueAttachments(
                    listOf(PendingAttachment(byteArrayOf(1, 2), "audio/mp4", "voice.m4a")),
                    caption = null,
                    canQueue = current::get,
                    beforeQueue = { error("revoked source must not retire") },
                )

            assertNull(queued)
            val (_, rollback) = recorder.calls.single { it.first == "clearMessageDraftIfRevision" }
            assertSame(staged, rollback[1])
            assertTrue(controller.timeline.isEmpty())
        }

    /**
     * Records every native call, without the trailing coroutine continuation, and answers the [answered] ones
     * with [answer]. Any other call fails, like the background profile reads the app makes on its own.
     */
    private class NativeRecorder(
        private vararg val answered: String,
        private val answer: (List<Any?>) -> Any? = { error("unanswered") },
    ) {
        /**
         * Every call so far. Background reads land on MarmotKit's IO threads while a test reads this list, so it
         * is copy-on-write and each read iterates a stable snapshot — a plain list throws mid-iteration.
         */
        val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val engine =
            native { name, args ->
                val recorded = args.toList().filterNot { it is kotlin.coroutines.Continuation<*> }
                calls += name to recorded
                if (name !in answered) throw UnsupportedOperationException("Unused native test call: $name")
                answer(recorded)
            }

        /** Calls a send makes to style, publish or stage its reply; a refused send makes none. */
        fun sendCalls(): List<String> = calls.map { it.first }.filter { it in SEND_CALLS }
    }

    private companion object {
        const val PENDING_ID = "6f1d2a3b-0c4d-4e5f-8a9b-0c1d2e3f4a5b"
        val SEND_CALLS =
            setOf(
                "localSendStatus",
                "parseMarkdown",
                "replyToMessageWithClientToken",
                "saveMessageDraftIfRevision",
                "selectedMessageDraft",
                "sendMessageDraftWithClientToken",
                "sendTaggedMedia",
                "sendTextWithClientToken",
                "uploadMediaWithClientToken",
            )
        val REPLY_ID = "d4".repeat(32)
        val EMPTY_MARKDOWN =
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))

        /** A received chat message with [id], the shape the composer keeps as its reply selection. */
        fun record(id: String) =
            AppMessageRecordFfi(
                messageIdHex = id,
                invalidated = false,
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

        /** A row of the user's own send started under [PENDING_ID], now showing [record] in [status]. */
        fun localSend(
            record: AppMessageRecordFfi,
            status: MessageStatus,
        ) = TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = record,
            status = status,
            presentationId = "msg:$PENDING_ID",
        )

        /** A small in-memory photo, the shape a reply carries as one native draft attachment. */
        fun photo() = PendingAttachment(byteArrayOf(1, 2, 3), "image/jpeg", "photo.jpg")

        /** An opaque draft revision; Android never reads inside it, so an uninitialized handle is enough. */
        fun revision(): MessageDraftRevisionFfi {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, MessageDraftRevisionFfi::class.java) as MessageDraftRevisionFfi
        }
    }
}
