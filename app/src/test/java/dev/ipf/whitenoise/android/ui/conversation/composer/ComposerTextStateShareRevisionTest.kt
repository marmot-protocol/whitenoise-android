package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
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
}
