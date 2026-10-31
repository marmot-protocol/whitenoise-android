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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A conversation's draft keeps one shared outgoing worker for all its picks: removing a pick or sending some never
 * retires it, so the next pick's decode reaches the same worker. It retires only by its idle, input-cap, failure and
 * account-reset rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedOutgoingWorkerTest {
    /** Removing a photo and a document and sending others leaves the draft's shared worker serving the next pick. */
    @Test
    fun removingOrSendingPicksNeverRetiresTheSharedWorker() =
        runTest {
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
            val group = conversationTimelineTestGroup()
            val controller = ConversationController(app, group)
            val fixture = BindingFixture(context, validReplyAt = { true })
            val transport = IsolatedMediaTransport(fixture, "images", spares = 0, liveLimit = 1)
            IsolatedMediaDecoder.installTestTransport(transport)
            try {
                val outgoing = controller.outgoingMediaTrustDomain
                assertEquals(MediaTrustDomain.Outgoing(ACCOUNT, group.groupIdHex), outgoing)
                val first = startDecode(transport, 1, outgoing, sized = true)
                first.join()
                first.result.get()?.recycle()
                val worker = fixture.bindings.single()
                val owner =
                    ConversationMediaDraftState(
                        app,
                        controller,
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                owner.releasePreparedPhoto("slot-a")
                owner.releasePreparedDocument(Uri.parse("content://picker/document-b"))
                owner.forgetAcceptedAttachments(setOf("slot-c"), setOf(Uri.parse("content://picker/document-d")))
                assertEquals("the shared worker was told to retire", 1L, worker.remote.retired.count)
                val next = startDecode(transport, 2, outgoing, sized = true)
                next.join()
                next.result.get()?.recycle()
                assertEquals("the next pick reached the same worker", listOf(1, 2), worker.remote.inputs.toList())
                assertEquals(1, fixture.bindings.size)
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
                fixture.dieAll()
                transport.close()
                controller.onCleared()
                app.mutationsScope.coroutineContext[Job]?.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    private companion object {
        const val ACCOUNT = "account"
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
