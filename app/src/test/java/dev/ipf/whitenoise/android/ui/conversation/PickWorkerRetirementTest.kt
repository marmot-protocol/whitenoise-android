package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.graphics.Bitmap
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
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Removing or sending a pick retires that pick's own worker, and never another pick's. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PickWorkerRetirementTest {
    /** Removing a photo or a document from the draft retires exactly that pick's outgoing worker. */
    @Test
    fun removingAPickRetiresOnlyThatPicksWorker() =
        withDraft { owner, group, retired ->
            val document = Uri.parse("content://picker/document-b")
            owner.releasePreparedPhoto(PHOTO_A)
            owner.releasePreparedDocument(document)
            assertEquals(
                listOf(
                    MediaTrustDomain.Outgoing(ACCOUNT, group, PHOTO_A),
                    MediaTrustDomain.Outgoing(ACCOUNT, group, document.toString()),
                ),
                retired.toList(),
            )
        }

    /**
     * Sending retires a document's worker at acceptance, as it gets no poster, while a sent photo keeps its worker
     * for its pending preview and just-sent poster.
     */
    @Test
    fun sendingRetiresADocumentAtOnceAndLeavesAPhotoForItsPoster() =
        withDraft { owner, group, retired ->
            val document = Uri.parse("content://picker/document-b")
            owner.forgetAcceptedAttachments(setOf(PHOTO_A), setOf(document))
            assertEquals(listOf(MediaTrustDomain.Outgoing(ACCOUNT, group, document.toString())), retired.toList())
        }

    /** Runs [block] against a fresh draft owner while recording every domain the facade is asked to retire. */
    private fun withDraft(block: DraftCheck) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val sessions = EditorSessionStore(RetirementStrings)
            val app =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(RetirementDrafts),
                    accountIdHexResolver = { null },
                    accounts = emptyList(),
                    activeAccountRef = ACCOUNT,
                    messageDraftRepository = MessageDraftRepository(RetirementDraftGateway(), sessions, dispatcher),
                )
            val group = conversationTimelineTestGroup()
            val controller = ConversationController(app, group)
            val recorder = RetireRecorder()
            IsolatedMediaDecoder.installTestTransport(recorder)
            try {
                val messages = PhotoEditorMessages("", "", "", "")
                val owner = ConversationMediaDraftState(app, controller, context, backgroundScope, messages)
                block(owner, group.groupIdHex, recorder.retired)
            } finally {
                IsolatedMediaDecoder.installTestTransport(null)
                controller.onCleared()
                app.mutationsScope.coroutineContext[Job]?.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    private companion object {
        const val ACCOUNT = "account"
        const val PHOTO_A = "slot-a"
    }
}

/** One check against a fresh draft owner, its conversation id and the domains retired so far. */
private typealias DraftCheck = suspend TestScope.(ConversationMediaDraftState, String, List<MediaTrustDomain>) -> Unit

/** Decodes nothing and records each domain the facade retires. */
private class RetireRecorder : MediaDecodeTransport {
    val retired = CopyOnWriteArrayList<MediaTrustDomain>()

    /** No pixels: these tests only follow retirement. */
    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? = null

    /** No inspection: these tests only follow retirement. */
    override fun inspect(bytes: ByteArray): ImageInspection? = null

    /** Records [domain] in call order. */
    override fun retireDomain(domain: MediaTrustDomain) {
        retired += domain
    }
}

/** An in-memory native draft, so removal never reaches the bindings. */
private class RetirementDraftGateway : MessageDraftGateway {
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
private object RetirementStrings : EditorStringStore {
    /** Nothing stored. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts and drops every value. */
    override fun replaceAll(values: Map<String, String>) = true

    /** Nothing to clear. */
    override fun clear() = Unit
}

/** Keeps unrelated composer text persistence in memory. */
private object RetirementDrafts : DraftPersistence {
    /** Nothing stored. */
    override fun read(): Map<String, String> = emptyMap()

    /** Drops every write. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
