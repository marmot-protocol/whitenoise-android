package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.notifications.NotificationReplyDraft
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationReplyDraftHandoffTest {
    @Test
    fun coldImportPreservesWhitespaceAndSameTapIsConsumedOnlyOnce() =
        runTest {
            val fixture = fixture()
            val target = target(" unfinished\nreply ")
            assertTrue(fixture.handoff.stage(target))
            assertTrue(fixture.handoff.stage(target))
            assertEquals(" unfinished\nreply ", fixture.store.get("account-b", "group-b"))
            assertEquals(1, fixture.gateway.saves)
            assertEquals(1, fixture.hydrations)
        }

    @Test
    fun existingDraftAndReplyTargetRemainInTheirOwnAccount() =
        runTest {
            val fixture = fixture()
            val attachment =
                MessageDraftAttachmentFfi(
                    "file", "note.txt", "text/plain", byteArrayOf(1), null, null, null, emptyList(),
                )
            fixture.gateway.drafts["account-b" to "group-b"] = draft("existing").copy(
                replyToMessageIdHex = "quoted-message",
                mediaAttachments = listOf(attachment),
            )
            fixture.gateway.drafts["account-a" to "group-b"] = draft("other account")
            assertTrue(fixture.handoff.stage(target("notification reply")))
            val saved = fixture.gateway.drafts.getValue("account-b" to "group-b")
            assertEquals("existing\nnotification reply", saved.content)
            assertEquals("quoted-message", saved.replyToMessageIdHex)
            assertEquals(listOf(attachment), saved.mediaAttachments)
            assertEquals("other account", fixture.gateway.drafts.getValue("account-a" to "group-b").content)
        }

    @Test
    fun cancelledNavigationWaiterDoesNotCancelOrRepeatAcceptedSave() =
        runTest {
            val fixture = fixture()
            val target = target("partial")
            fixture.writer.submit("account-b", "group-b", "pending edit")
            val first = async { fixture.handoff.stage(target) }
            runCurrent()
            assertTrue(first.isActive)
            first.cancel()
            val second = async { fixture.handoff.stage(target) }
            advanceUntilIdle()
            assertTrue(second.await())
            assertEquals(2, fixture.gateway.saves)
            assertEquals("pending edit\npartial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun saveFailureKeepsHandoffForRetryWithoutDuplicatingAmbiguouslyCommittedText() =
        runTest {
            val fixture = fixture()
            fixture.gateway.failAfterSave = true
            fixture.gateway.failReadAfterCommit = true
            val target = target("partial")
            assertTrue(fixture.handoff.stage(target))
            assertEquals("partial", fixture.gateway.drafts.getValue("account-b" to "group-b").content)
            assertEquals(1, fixture.gateway.saves)
            assertEquals("partial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun persistentFailureIsBoundedAndDoesNotConsumeTheDraft() =
        runTest {
            val fixture = fixture()
            fixture.gateway.failBeforeSaveCount = 3
            val target = target("partial")
            assertFalse(fixture.handoff.stage(target))
            assertEquals(3, fixture.gateway.attempts)
            assertTrue(fixture.handoff.stage(target))
            assertEquals("partial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun failureAfterCancelledWaiterDoesNotPoisonTheNextRouteAttempt() =
        runTest {
            val fixture = fixture()
            fixture.writer.submit("account-b", "group-b", "pending edit")
            fixture.gateway.failBeforeSaveCount = 4
            val target = target("partial")
            val cancelled = async { fixture.handoff.stage(target) }
            runCurrent()
            cancelled.cancel()
            advanceUntilIdle()
            assertTrue(fixture.handoff.stage(target))
            assertEquals("pending edit\npartial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun pendingComposerKeystrokesAreFlushedBeforeMerge() =
        runTest {
            val fixture = fixture()
            fixture.writer.submit("account-b", "group-b", "new local edit")
            runCurrent()
            assertTrue(fixture.handoff.stage(target("partial")))
            assertEquals("new local edit\npartial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun newerComposerEditCannotBeOverwrittenByDelayedMergeHydration() =
        runTest {
            val fixture = fixture()
            val completion = fixture.writer.mergeText("account-b", "group-b", "old incoming")
            fixture.writer.submit("account-b", "group-b", "new local edit")
            fixture.store.set("account-b", "group-b", TextFieldValue("new local edit"))
            fixture.writer.hydrateMergedDraft(fixture.store, "account-b", "group-b", completion) {
                fixture.hydrations += 1
            }
            assertEquals("new local edit", fixture.store.get("account-b", "group-b"))
            assertEquals(0, fixture.hydrations)
        }

    @Test
    fun acceptedSendCannotBeResurrectedByAnOldMergeCompletion() =
        runTest {
            val fixture = fixture()
            val completion = fixture.writer.mergeText("account-b", "group-b", "already sent")
            fixture.writer.beginSuccessfulSendCleanup("account-b", "group-b", checkNotNull(completion.generation)) {
                fixture.store.set("account-b", "group-b", TextFieldValue(""))
            }
            fixture.writer.hydrateMergedDraft(fixture.store, "account-b", "group-b", completion) {
                fixture.hydrations += 1
            }
            assertNull(fixture.store.get("account-b", "group-b"))
            assertEquals(0, fixture.hydrations)
        }

    @Test
    fun firstImportDoesNotDiscardTextThatRepeatsTheExistingDraftSuffix() =
        runTest {
            val fixture = fixture()
            fixture.gateway.drafts["account-b" to "group-b"] = draft("existing\npartial")
            assertTrue(fixture.handoff.stage(target("partial")))
            assertEquals("existing\npartial\npartial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun failedBeforeCommitDoesNotMistakeAnOldMatchingSuffixForThisDelivery() =
        runTest {
            val fixture = fixture()
            fixture.gateway.drafts["account-b" to "group-b"] = draft("existing\npartial")
            fixture.gateway.failBeforeSaveCount = 1
            assertTrue(fixture.handoff.stage(target("partial")))
            assertEquals("existing\npartial\npartial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun deferredFailureRecoversOnlyItsDraftWithoutANavigationCallback() =
        runTest {
            val fixture = fixture()
            fixture.gateway.failBeforeSaveCount = 3
            assertFalse(fixture.handoff.stage(target("partial")))
            fixture.handoff.retryPending("other-account", "group-b")
            assertEquals(3, fixture.gateway.attempts)
            fixture.handoff.retryPending("account-b", "group-b")
            assertEquals("partial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun retainedFailedComposerEditBlocksStaleNativeHydrationUntilMergeRecoversIt() =
        runTest {
            val fixture = fixture()
            fixture.gateway.failBeforeSaveCount = 1
            val generation = fixture.writer.submit("account-b", "group-b", "unsaved local edit")
            advanceUntilIdle()
            assertNull(fixture.writer.loadIfCurrent("account-b", "group-b", generation))
            assertTrue(fixture.handoff.stage(target("partial")))
            assertEquals("unsaved local edit\npartial", fixture.store.get("account-b", "group-b"))
        }

    @Test
    fun cancelledDeliveryDoesNotCancelAnActiveRoutingCaller() =
        runTest {
            val fixture = fixture()
            fixture.gateway.cancelNextRead = true
            assertFalse(fixture.handoff.stage(target("partial")))
            assertTrue(fixture.handoff.stage(target("partial")))
        }

    private fun TestScope.fixture(): DraftHandoffFixture {
        val gateway = HandoffDraftGateway()
        val repository =
            MessageDraftRepository(
                gateway,
                EditorSessionStore(HandoffEditorStrings),
                StandardTestDispatcher(testScheduler),
            )
        val writer = CoalescingMessageDraftWriter(this, repository)
        val store = DraftStore(HandoffDraftPersistence)
        return DraftHandoffFixture(gateway, writer, store).also {
            it.handoff = NotificationReplyDraftHandoff(this, writer, store) { it.hydrations += 1 }
        }
    }

    private fun target(text: String) =
        NotificationTarget(
            "account-b",
            "group-b",
            "message-b",
            NotificationTargetKind.MESSAGE,
            NotificationReplyDraft("delivery-id", text),
        )

    private fun draft(text: String) = MessageDraftFfi("group-b", text, null, emptyList(), 1L, 2L)
}

private class DraftHandoffFixture(
    val gateway: HandoffDraftGateway,
    val writer: CoalescingMessageDraftWriter,
    val store: DraftStore,
) {
    lateinit var handoff: NotificationReplyDraftHandoff
    var hydrations = 0
}

private class HandoffDraftGateway : MessageDraftGateway {
    val drafts = mutableMapOf<Pair<String, String>, MessageDraftFfi>()
    var saves = 0
    var failAfterSave = false
    var failReadAfterCommit = false
    var failReads = 0
    var cancelNextRead = false
    var failBeforeSaveCount = 0
    var attempts = 0

    override fun read(accountRef: String, groupIdHex: String): MessageDraftFfi? {
        if (cancelNextRead) {
            cancelNextRead = false
            throw CancellationException("cancelled native delivery")
        }
        if (failReads > 0) {
            failReads -= 1
            error("authoritative read unavailable")
        }
        return drafts[accountRef to groupIdHex]
    }

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        attempts += 1
        if (failBeforeSaveCount > 0) {
            failBeforeSaveCount -= 1
            error("save failed before commit")
        }
        saves += 1
        val draft = MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 2L)
        drafts[accountRef to groupIdHex] = draft
        if (failAfterSave) {
            failAfterSave = false
            if (failReadAfterCommit) failReads = 1
            error("ambiguous commit")
        }
        return draft
    }

    override fun delete(accountRef: String, groupIdHex: String) {
        drafts.remove(accountRef to groupIdHex)
    }

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

private object HandoffEditorStrings : EditorStringStore {
    override fun readAll(): Map<String, String> = emptyMap()
    override fun replaceAll(values: Map<String, String>): Boolean = true
    override fun clear() = Unit
}

private object HandoffDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()
    override fun write(key: String, value: String?) = Unit
}
