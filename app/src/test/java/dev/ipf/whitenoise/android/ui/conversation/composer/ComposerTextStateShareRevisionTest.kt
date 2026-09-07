package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerTextStateShareRevisionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun externalShareRevisionRehydratesMountedComposerFromMergedDraft() {
        var persistedDraft by mutableStateOf(TextFieldValue("existing", TextRange(8)))
        var externalShareRevision by mutableIntStateOf(0)
        lateinit var composerState: ComposerTextState

        composeRule.setContent {
            composerState =
                rememberComposerTextState(
                    draftKey = "group-1",
                    initialDraft = persistedDraft,
                    externalRevision = externalShareRevision,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            composerState.valueState.value = TextFieldValue("existing", TextRange(3))
            persistedDraft = TextFieldValue("existing\nshared", TextRange(15))
            externalShareRevision += 1
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(
                TextFieldValue("existing\nshared", TextRange(15)),
                composerState.valueState.value,
            )
        }
    }

    @Test
    fun inboundShareRevisionWaitsForEditCompletionBeforeRehydratingComposerText() {
        var persistedDraft by mutableStateOf(TextFieldValue("existing", TextRange(8)))
        var externalShareRevision by mutableIntStateOf(0)
        var editingMessageId by mutableStateOf<String?>("msg-edit-1")
        lateinit var composerState: ComposerTextState

        composeRule.setContent {
            val appliedShareRevision =
                rememberComposerShareRevision(
                    externalRevision = externalShareRevision,
                    editingMessageId = editingMessageId,
                )
            composerState =
                rememberComposerTextState(
                    draftKey = "group-1",
                    initialDraft = persistedDraft,
                    externalRevision = appliedShareRevision,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            persistedDraft = TextFieldValue("shared inbound", TextRange(15))
            externalShareRevision += 1
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("msg-edit-1", editingMessageId)
            assertEquals(
                TextFieldValue("existing", TextRange(8)),
                composerState.valueState.value,
            )
            editingMessageId = null
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(
                TextFieldValue("shared inbound", TextRange(15)),
                composerState.valueState.value,
            )
        }
    }

    @Test
    fun independentExternalRevisionsCannotAliasWhenTheirSumMatches() {
        var persistedDraft by mutableStateOf(TextFieldValue("dictated", TextRange(8)))
        var shareRevision by mutableIntStateOf(0)
        var dictationRevision by mutableIntStateOf(1)
        lateinit var composerState: ComposerTextState

        composeRule.setContent {
            composerState =
                rememberComposerTextState(
                    draftKey = "group-1",
                    initialDraft = persistedDraft,
                    externalRevision = shareRevision to dictationRevision,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            persistedDraft = TextFieldValue("shared", TextRange(6))
            shareRevision = 1
            dictationRevision = 0
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(
                TextFieldValue("shared", TextRange(6)),
                composerState.valueState.value,
            )
        }
    }

    /** Content ABA invalidates a prior token even when the visible string returns to its old value. */
    @Test
    fun acceptanceTokenTracksContentGenerationRatherThanOnlyTextEquality() {
        val state = ComposerTextState(TextFieldValue("draft", TextRange(5)))
        val token = state.acceptanceToken()

        state.updateValue(TextFieldValue("interim", TextRange(7)))
        state.updateValue(TextFieldValue("draft", TextRange(5)))

        assertFalse(state.clearAccepted(token))
        assertEquals("draft", state.valueState.value.text)
    }

    /** Selection-only changes preserve the acceptance generation because content is unchanged. */
    @Test
    fun selectionOnlyChangeDoesNotInvalidateAcceptedContent() {
        val state = ComposerTextState(TextFieldValue("draft", TextRange(5)))
        val token = state.acceptanceToken()

        state.updateValue(TextFieldValue("draft", TextRange(2)))

        assertTrue(state.clearAccepted(token))
        assertEquals("", state.valueState.value.text)
    }

    /** A media callback's old token cannot clear an identical new state created by external rehydration. */
    @Test
    fun mediaAcceptanceTokenCannotClearAnIdenticalReplacementState() {
        val previousState = ComposerTextState(TextFieldValue("draft", TextRange(5)))
        val transportedToken = previousState.acceptanceToken()
        val replacementState = ComposerTextState(TextFieldValue("draft", TextRange(5)))

        assertFalse(replacementState.clearAccepted(transportedToken))
        assertEquals("draft", replacementState.valueState.value.text)
    }
}
