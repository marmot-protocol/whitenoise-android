package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real composer owner while a controlled native read returns a stale snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDraftRestoreRemovalTest {
    /** Removing a saved URI during native restoration neither resurrects it nor retains its native bytes. */
    @Test
    fun removingSavedDocumentWhileDraftReadIsPendingRemovesNativeAttachment() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val group = conversationTimelineTestGroup()
                val uri = Uri.parse("content://picker/document/saved")
                val attachment =
                    MessageDraftAttachmentFfi(
                        stagedDocumentAttachmentId("account", group.groupIdHex, uri.toString()),
                        "document.pdf",
                        "application/pdf",
                        byteArrayOf(1, 2, 3),
                        null,
                        null,
                        null,
                        emptyList(),
                    )
                val gateway = RestoreGateway(MessageDraftFfi(group.groupIdHex, "", null, listOf(attachment), 1L, 1L))
                val repository = MessageDraftRepository(gateway, EditorSessionStore(EmptyStrings), dispatcher)
                val app =
                    WhiteNoiseAppState(
                        context = context,
                        draftStore = DraftStore(EmptyDrafts),
                        accountIdHexResolver = { null },
                        accounts = emptyList(),
                        activeAccountRef = "account",
                        messageDraftRepository = repository,
                    )
                val controller = ConversationController(appState = app, initialGroup = group)
                val owner =
                    ConversationMediaDraftState(
                        app,
                        controller,
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                owner.updateInputs(emptyList(), listOf(uri), "account")
                gateway.beforeReadReturns = {
                    owner.releasePreparedDocument(uri)
                    owner.updateInputs(emptyList(), emptyList(), "account")
                }

                val restored = owner.restorePersistedAttachments()
                advanceUntilIdle()

                assertTrue(requireNotNull(restored).documentUris.isEmpty())
                assertTrue(owner.preparedDocumentAttachments().isEmpty())
                assertNull(gateway.current)
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Keeps editor bookkeeping isolated from the Android key store. */
    private object EmptyStrings : EditorStringStore {
        override fun readAll(): Map<String, String> = emptyMap()

        override fun replaceAll(values: Map<String, String>) = true

        override fun clear() = Unit
    }

    /** Keeps unrelated text draft storage entirely in memory. */
    private object EmptyDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}

/** Returns the captured native snapshot after a shelf action, then accepts exact draft cleanup. */
private class RestoreGateway(
    var current: MessageDraftFfi?,
) : MessageDraftGateway {
    var beforeReadReturns: (() -> Unit)? = null

    /** Delivers one controlled stale read without replaying the removal callback during cleanup. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? {
        val snapshot = current
        val action = beforeReadReturns
        beforeReadReturns = null
        action?.invoke()
        return snapshot
    }

    /** Preserves other native draft fields when only one attachment is removed. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi =
        MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 1L)
            .also { current = it }

    /** Removes the empty native draft after its last attachment is explicitly discarded. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        current = null
    }

    /** No chat-list background work is part of this restoration fixture. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}
