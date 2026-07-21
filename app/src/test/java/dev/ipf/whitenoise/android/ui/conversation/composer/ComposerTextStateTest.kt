package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerTextStateTest {
    @Test
    fun matchingDraftIsConsumedAfterAcceptedSend() {
        val state = ComposerTextState(TextFieldValue("draft caption", selection = TextRange(5)))
        var persistedDraft: TextFieldValue? = null

        state.clearDraftIfUnchanged("draft caption") { value ->
            persistedDraft = value
        }

        assertEquals(TextFieldValue(""), state.valueState.value)
        assertEquals(TextFieldValue(""), persistedDraft)
    }

    @Test
    fun newerComposerTextSurvivesLateSendAcceptance() {
        val state = ComposerTextState(TextFieldValue("next message"))
        var persistedDraft: TextFieldValue? = null

        state.clearDraftIfUnchanged("draft caption") { value ->
            persistedDraft = value
        }

        assertEquals(TextFieldValue("next message"), state.valueState.value)
        assertNull(persistedDraft)
    }
}
