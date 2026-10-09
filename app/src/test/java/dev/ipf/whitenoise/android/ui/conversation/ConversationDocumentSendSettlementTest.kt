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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Normal document preparation retains the picker occurrence owned by an in-flight send. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDocumentSendSettlementTest {
    /** Capture at the native-write boundary, then accept after the prepared-document map is populated. */
    @Test
    fun documentPreparationAfterSendCaptureRetainsSettlementOwner() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val gateway = SettlementDraftGateway()
            val repository = MessageDraftRepository(gateway, EditorSessionStore(SettlementStrings), dispatcher)
            val app =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(SettlementDrafts),
                    accountIdHexResolver = { null },
                    accounts = emptyList(),
                    activeAccountRef = "account",
                    messageDraftRepository = repository,
                )
            val file = File(context.cacheDir, "settlement-document.txt").apply { writeText("document bytes") }
            val controller = ConversationController(app, conversationTimelineTestGroup())
            try {
                val owner =
                    ConversationMediaDraftState(
                        app,
                        controller,
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                val uri = Uri.fromFile(file)
                owner.updateInputs(emptyList(), listOf(uri), "account")
                owner.restorePersistedAttachments()
                var canSettle: (() -> Boolean)? = null
                gateway.beforeSave = {
                    gateway.beforeSave = {}
                    assertTrue(owner.isPreparing)
                    assertTrue(owner.preparedDocumentAttachments().isEmpty())
                    canSettle = owner.captureSendSettlement()
                }
                owner.prepareMissingAttachments()
                requireNotNull(app.mutationsScope.coroutineContext[Job]).children.toList().joinAll()
                assertTrue(owner.preparedDocumentAttachments().containsKey(uri))
                val settlement = requireNotNull(canSettle)
                assertTrue(settlement())
                val completion =
                    StagedMediaSendCompletion({}, {}) {
                        if (settlement()) owner.forgetAcceptedAttachments(emptySet(), setOf(uri))
                    }
                completion.reject()
                completion.accept()
                assertTrue(owner.preparedDocumentAttachments().isEmpty())
                owner.releasePreparedDocument(uri)
                assertFalse(settlement())
            } finally {
                controller.onCleared()
                app.mutationsScope.coroutineContext[Job]?.cancelAndJoin()
                file.delete()
                Dispatchers.resetMain()
            }
        }
}

/** In-memory native gateway exposes preparation before its bytes reach the UI projection. */
private class SettlementDraftGateway : MessageDraftGateway {
    private var current: MessageDraftFfi? = null
    var beforeSave: () -> Unit = {}

    override fun read(accountRef: String, groupIdHex: String): MessageDraftFfi? = current

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        beforeSave()
        return MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 1L)
            .also { current = it }
    }

    override fun delete(accountRef: String, groupIdHex: String) {
        current = null
    }

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

/** Keeps editor sessions independent of device preferences. */
private object SettlementStrings : EditorStringStore {
    override fun readAll(): Map<String, String> = emptyMap()
    override fun replaceAll(values: Map<String, String>) = true
    override fun clear() = Unit
}

/** Keeps unrelated composer text persistence in memory. */
private object SettlementDrafts : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()
    override fun write(key: String, value: String?) = Unit
}
