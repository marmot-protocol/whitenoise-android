package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.state.StagedAttachmentSendClaim
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry settlement must not complete an already rejected UI attempt or unlock its successor. */
class StagedMediaSendCompletionTest {
    /** A failed send keeps a distinct eventual cleanup owner while its old claim completes only once. */
    @Test
    fun durableRetryCannotReportTwiceOrReleaseNewerClaim() {
        val claim = StagedAttachmentSendClaim()
        val results = mutableListOf<Boolean>()
        var settlements = 0
        lateinit var first: StagedMediaSendCompletion
        claim.send(results::add) { accepted, rejected ->
            first = StagedMediaSendCompletion(accepted, rejected) { settlements++ }
        }
        first.reject()
        claim.send(results::add) { _, _ -> }
        assertTrue(claim.isHeld)
        first.accept()
        first.accept()
        assertTrue(claim.isHeld)
        assertEquals(listOf(false), results)
        assertEquals(1, settlements)
    }

    /** Bubble Retry consumes only its original caption and does not repeat the rejected attempt result. */
    @Test
    fun durableRetrySettlesCaptionWithoutClearingNewerEdits() {
        for (replacement in listOf(null, "new caption", "original")) {
            val state = ComposerTextState(TextFieldValue("original"))
            val results = mutableListOf<Boolean>()
            val completion =
                StagedMediaSendCompletion(
                    { results += true },
                    { results += false },
                    state.captureCaptionSettlement("original"),
                )
            completion.reject()
            assertEquals("original", state.valueState.value.text)
            if (replacement != null) {
                state.updateValue(TextFieldValue("intermediate edit"))
                state.updateValue(TextFieldValue(replacement))
            }
            completion.accept()
            completion.accept()
            assertEquals(replacement.orEmpty(), state.valueState.value.text)
            assertEquals(listOf(false), results)
        }
    }

    /** Initial acceptance clears the exact shelf and reports success once, even when durable cleanup repeats. */
    @Test
    fun firstAcceptanceCompletesShelfAndAttemptOnce() {
        val calls = mutableListOf<String>()
        val completion =
            StagedMediaSendCompletion({ calls += "accepted" }, { calls += "rejected" }, { calls += "settled" })
        completion.accept()
        completion.reject()
        completion.accept()
        assertEquals(listOf("settled", "accepted"), calls)
    }
}
