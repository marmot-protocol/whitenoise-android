package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.sandbox.BindingFixture
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.startDecode
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * One draft's picks share one outgoing worker, and no later draft of the same conversation ever reaches it (#3373).
 * Removing some picks keeps the draft and its worker. Emptying the composer, or sending the draft, ends it: the next
 * pick starts a new draft in a fresh worker, and the ended draft's worker retires once its send, which still decodes
 * the just-sent thumbnails in it, has ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedOutgoingWorkerTest {
    /**
     * Two picks share their draft's worker, and removing one keeps it. Emptying the composer retires it, and a pick of
     * the next draft decodes in a fresh worker that holds nothing of the earlier draft.
     */
    @Test
    fun aLaterDraftNeverReachesAnEmptiedDraftsWorker() =
        withDraft { controller, owner, fixture, transport ->
            val first = controller.outgoingMediaTrustDomain(SLOT_A)
            assertTrue(first is MediaTrustDomain.Outgoing)
            assertEquals(first, controller.outgoingMediaTrustDomain(DOCUMENT_B.toString()))
            decode(transport, 1, first)
            decode(transport, 2, controller.outgoingMediaTrustDomain(DOCUMENT_B.toString()))
            val worker = fixture.bindings.single()
            owner.updateInputs(listOf(slot(SLOT_A)), emptyList(), ACCOUNT)
            assertEquals("removing one pick keeps the draft's worker", 1L, worker.remote.retired.count)
            owner.updateInputs(emptyList(), emptyList(), ACCOUNT)
            assertTrue("the emptied draft's worker retires", worker.remote.retired.await(2, TimeUnit.SECONDS))
            worker.remote.die()
            val later = controller.outgoingMediaTrustDomain(SLOT_C)
            assertNotEquals(first, later)
            decode(transport, 3, later)
            val fresh = fixture.bindings[1]
            assertEquals(listOf(1, 2), worker.remote.inputs.toList())
            assertEquals(listOf(3), fresh.remote.inputs.toList())
        }

    /**
     * A sent pick keeps decoding in its draft's worker while its send runs, as its just-sent thumbnail does, while a
     * pick made during the send starts the next draft elsewhere. The sent draft's worker retires once the send has
     * ended and its picks have left the composer, and the next draft's later picks never reach it.
     */
    @Test
    fun aPickMadeDuringASendNeverReachesTheSentDraftsWorker() =
        withDraft { controller, owner, fixture, transport ->
            val sentDraft = controller.outgoingMediaTrustDomain(SLOT_A)
            decode(transport, 1, sentDraft)
            val sent = fixture.bindings.single()
            val send = controller.beginOutgoingSend(listOf(SLOT_A))
            val next = controller.outgoingMediaTrustDomain(SLOT_C)
            assertNotEquals(sentDraft, next)
            assertEquals("a sent pick keeps its draft", sentDraft, controller.outgoingMediaTrustDomain(SLOT_A))
            decode(transport, 2, sentDraft)
            owner.updateInputs(listOf(slot(SLOT_C)), emptyList(), ACCOUNT)
            assertEquals("the send still decodes its thumbnails", 1L, sent.remote.retired.count)
            controller.endOutgoingSend(send)
            assertTrue("the sent draft's worker retires", sent.remote.retired.await(2, TimeUnit.SECONDS))
            sent.remote.die()
            decode(transport, 3, next)
            decode(transport, 4, controller.outgoingMediaTrustDomain(SLOT_D))
            val fresh = fixture.bindings[1]
            assertEquals(listOf(1, 2), sent.remote.inputs.toList())
            assertEquals(listOf(3, 4), fresh.remote.inputs.toList())
        }

    /** Runs [body] against a conversation's controller, its draft state and an isolated test lane of one worker. */
    private fun withDraft(
        body: suspend (
            ConversationController,
            ConversationMediaDraftState,
            BindingFixture,
            IsolatedMediaTransport,
        ) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessions = EditorSessionStore(SharedWorkerStrings)
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(SharedWorkerDrafts),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = ACCOUNT,
                messageDraftRepository = MessageDraftRepository(SharedWorkerDraftGateway(), sessions, dispatcher),
            )
        val controller = ConversationController(app, conversationTimelineTestGroup())
        val fixture = BindingFixture(context, validReplyAt = { true })
        val transport = IsolatedMediaTransport(fixture, "images", spares = 0, liveLimit = 1)
        IsolatedMediaDecoder.installTestTransport(transport)
        try {
            val messages = PhotoEditorMessages("", "", "", "")
            val owner = ConversationMediaDraftState(app, controller, context, backgroundScope, messages)
            body(controller, owner, fixture, transport)
        } finally {
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            fixture.dieAll()
            transport.close()
            controller.onCleared()
            app.mutationsScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    /** Decodes input [n] in [domain] to completion. */
    private fun decode(
        transport: IsolatedMediaTransport,
        n: Int,
        domain: MediaTrustDomain,
    ) {
        val call = startDecode(transport, n, domain, sized = true)
        call.join()
        call.result.get()?.recycle()
    }

    /** A composer slot [id] for an arbitrary picked image. */
    private fun slot(id: String) = PendingMediaSlot(id, Uri.parse("content://picker/$id"))

    private companion object {
        const val ACCOUNT = "account"
        const val SLOT_A = "slot-a"
        const val SLOT_C = "slot-c"
        const val SLOT_D = "slot-d"
        val DOCUMENT_B: Uri = Uri.parse("content://picker/document-b")
    }
}

/** An in-memory native draft, so removal never reaches the bindings. */
private class SharedWorkerDraftGateway : MessageDraftGateway {
    private var current: MessageDraftFfi? = null

    /** The draft last saved, if any. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = current

    /** Keeps the saved draft in memory. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        val draft = MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 1L)
        current = draft
        return draft
    }

    /** Forgets the saved draft. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        current = null
    }

    /** No other drafts exist. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

/** Keeps editor sessions independent of device preferences. */
private object SharedWorkerStrings : EditorStringStore {
    /** Nothing stored. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts and drops every value. */
    override fun replaceAll(values: Map<String, String>) = true

    /** Nothing to clear. */
    override fun clear() = Unit
}

/** Keeps unrelated composer text persistence in memory. */
private object SharedWorkerDrafts : DraftPersistence {
    /** Nothing stored. */
    override fun read(): Map<String, String> = emptyMap()

    /** Drops every write. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
