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
}
