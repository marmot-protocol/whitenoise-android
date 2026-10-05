package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.share.SharePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the shipping AppState share callback and coalesced native-draft writer, without a text-stager override. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class InboundShareDraftFormattingTest {
    /** Indentation and trailing line breaks reach both native persistence and composer hydration unchanged. */
    @Test
    fun sharedFormattingSurvivesTheProductionDraftMerge() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val gateway = FormattingDraftGateway()
            val account = AccountSummaryFfi("share", "ab".repeat(32), true, false, false, true)
            val state =
                WhiteNoiseAppState(
                    context = ApplicationProvider.getApplicationContext(),
                    draftStore = DraftStore(FormattingDraftPersistence()),
                    accountIdHexResolver = { account.accountIdHex },
                    accounts = listOf(account),
                    activeAccountRef = account.label,
                    messageDraftRepository =
                        MessageDraftRepository(gateway, EditorSessionStore(FormattingEditorStrings()), dispatcher),
                )
            try {
                val incoming = "  original text\n\tsecond line\n"
                assertTrue(
                    state.stageInboundShare(
                        account.label,
                        listOf("group"),
                        SharePayload(incoming, emptyList(), "text/plain"),
                    ),
                )
                runCurrent()
                assertEquals(incoming, gateway.current?.content)
                assertEquals(incoming, state.draftStore.get(account.label, "group"))
            } finally {
                withContext(NonCancellable) {
                    state.mutationsScope.coroutineContext.job
                        .cancelAndJoin()
                }
                Dispatchers.resetMain()
            }
        }
}

/** The composer owns its in-memory projection; this fixture does not touch Android preference or encrypted files. */
private class FormattingDraftPersistence : DraftPersistence {
    /** Starts with no composer content. */
    override fun read(): Map<String, String> = emptyMap()

    /** Leaves native persistence observation to FormattingDraftGateway. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

/** Records the exact content passed through the native draft boundary; it performs no merge or normalization. */
private class FormattingDraftGateway : MessageDraftGateway {
    var current: MessageDraftFfi? = null

    /** Returns the last native draft snapshot. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = current

    /** Saves exactly the supplied content and metadata, exposing host-side trimming to the regression. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi =
        MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 2L).also { current = it }

    /** Removes the captured draft when the production repository asks to clear it. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        current = null
    }

    /** No summaries are needed for the single-destination fixture. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

/** Supplies an empty editor-session store without invoking platform credential or editor services. */
private class FormattingEditorStrings : EditorStringStore {
    /** No media editor sessions exist in this text-only fixture. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts editor bookkeeping without changing draft text. */
    override fun replaceAll(values: Map<String, String>): Boolean = true

    /** Clears no external state. */
    override fun clear() = Unit
}
