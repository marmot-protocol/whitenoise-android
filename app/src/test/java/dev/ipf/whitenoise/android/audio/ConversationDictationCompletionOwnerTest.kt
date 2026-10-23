package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCompletionOwnerTest {
    /** Every explicit choice stays editable until the synchronous policy claim. */
    @Test
    fun latestExplicitChoiceWinsUntilCommitted() {
        val intent = ConversationDictationCompletionIntent()
        org.junit.Assert.assertTrue(intent.choose(ConversationDictationDeliveryMode.SendOnFinish))
        org.junit.Assert.assertTrue(intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft))
        org.junit.Assert.assertFalse(intent.choose(ConversationDictationDeliveryMode.SendOnFinish, automatic = true))
        assertEquals(ConversationDictationDeliveryMode.PasteIntoDraft, intent.mode)
        org.junit.Assert.assertTrue(intent.choose(ConversationDictationDeliveryMode.SendOnFinish))
        intent.commit()
        org.junit.Assert.assertFalse(intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft))
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, intent.mode)
    }

    /** A thrown draft write is not a CAS conflict and must not trigger repeated writes or append fallback. */
    @Test
    fun pasteRetainsAfterOneThrowingWrite() {
        val draft = TextFieldValue("Draft", TextRange(5))
        val target =
            ConversationDictationTarget(
                accountRef = "account",
                groupIdHex = "group",
                capturedDraft = draft,
                capturedDraftRevision = 5L,
                mode = ConversationDictationMode.InApp,
            )
        var reads = 0
        var writes = 0
        val owner =
            ConversationDictationCompletionOwner(
                readDraft = { _, _ ->
                    reads += 1
                    ConversationDictationDraftSnapshot(draft, 5L)
                },
                writeDraft = { _, _, _, _ ->
                    writes += 1
                    throw IllegalStateException("draft store unavailable")
                },
            )

        val result =
            owner.paste(
                ConversationDictationCompletionOwner.DraftOperation(
                    sessionId = 7L,
                    target = target,
                    text = "recognized",
                    attempts = 3,
                    currentSessionId = { 7L },
                    validate = { ConversationDictationTargetValidation.Available },
                ),
            )

        assertEquals(ConversationDictationCompletionOwner.PasteResult.Retained, result)
        assertEquals(1, reads)
        assertEquals(1, writes)
    }

    /** Unlock retries the captured CAS exactly; a later editor revision is never rebased. */
    @Test
    fun deferredRetryPreservesCapturedRevisionAndRejectsConcurrentEdits() {
        val fixture = DeferredDraftFixture()
        assertEquals(ConversationDictationCompletionOwner.PasteResult.DeferredByHost, fixture.capture())
        fixture.revision = 6L
        assertEquals(
            ConversationDictationCompletionOwner.PasteResult.Superseded,
            fixture.owner.retryDeferred(7L, fixture.target),
        )
        assertEquals(listOf(5L), fixture.writtenRevisions)
        val exact = DeferredDraftFixture()
        exact.capture()
        exact.defer = false
        assertEquals(
            ConversationDictationCompletionOwner.PasteResult.Accepted,
            exact.owner.retryDeferred(7L, exact.target),
        )
        assertEquals(listOf(5L, 5L), exact.writtenRevisions)
        assertEquals(
            ConversationDictationCompletionOwner.PasteResult.Retained,
            exact.owner.retryDeferred(7L, exact.target),
        )
        assertEquals(2, exact.writtenRevisions.size)
    }

    /** Unknown, removed and replacement owners cannot turn a deferred draft into a write. */
    @Test
    fun deferredRetryRequiresTheCapturedLiveOwnerAndAvailableTarget() {
        val fixture = DeferredDraftFixture()
        fixture.capture()
        fixture.validation = ConversationDictationTargetValidation.Indeterminate
        assertEquals(
            ConversationDictationCompletionOwner.PasteResult.DeferredByHost,
            fixture.owner.retryDeferred(7L, fixture.target),
        )
        fixture.validation = ConversationDictationTargetValidation.DefinitelyRemoved
        assertEquals(
            ConversationDictationCompletionOwner.PasteResult.Retained,
            fixture.owner.retryDeferred(7L, fixture.target),
        )
        fixture.currentSession = 8L
        assertEquals(
            ConversationDictationCompletionOwner.PasteResult.Abandoned,
            fixture.owner.retryDeferred(7L, fixture.target),
        )
        assertEquals(listOf(5L), fixture.writtenRevisions)
    }

    private class DeferredDraftFixture {
        private val draft = TextFieldValue("Draft", TextRange(5))
        val target = ConversationDictationTarget("account", "group", draft, 5L, ConversationDictationMode.InApp)
        var revision = 5L
        var currentSession = 7L
        var validation: ConversationDictationTargetValidation = ConversationDictationTargetValidation.Available
        var defer = true
        val writtenRevisions = mutableListOf<Long>()
        val owner =
            ConversationDictationCompletionOwner(
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
                writeDraft = { _, _, _, _ -> error("Typed completion writer must own this operation") },
                completionWrite = { _, _, expected, _ ->
                    writtenRevisions.add(expected)
                    if (defer) {
                        ConversationDictationDraftWrite.DeferredByHost
                    } else {
                        ConversationDictationDraftWrite.Written(6L)
                    }
                },
            )

        fun capture() =
            owner.paste(
                ConversationDictationCompletionOwner.DraftOperation(
                    7L,
                    target,
                    "recognized",
                    3,
                    { currentSession },
                    { validation },
                ),
            )
    }
}
