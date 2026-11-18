package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ComposerDraftSnapshot
import dev.ipf.whitenoise.android.state.DraftStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the saved-draft read used by ConversationScreen under a real Compose observer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerDraftReadCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    /** Live edits stay local to the editor. Restoration re-reads the current owner and selection. */
    @Test
    fun liveDraftWritesDoNotInvalidateTheRestorationReader() {
        val store = DraftStore.forContext(ApplicationProvider.getApplicationContext())
        val owner = mutableStateOf("account" to "chat-a")
        val revision = mutableStateOf(0 to 0)
        val initial = TextFieldValue("original", TextRange(2))
        val edited = TextFieldValue("edited while catching up", TextRange(4))
        val dictated = TextFieldValue("restored dictation", TextRange(6))
        val other = TextFieldValue("other chat", TextRange(3))
        store.set("account", "chat-a", initial)
        store.set("account", "chat-b", other)
        var restored: ComposerDraftSnapshot? = null
        var compositions = 0

        composeRule.setContent {
            val snapshot = rememberRestoredComposerDraft(store, owner.value, revision.value)
            SideEffect {
                restored = snapshot
                compositions++
            }
        }
        val baseline =
            composeRule.runOnIdle {
                assertEquals(initial, restored?.textFieldValue)
                compositions
            }
        composeRule.runOnIdle { store.set("account", "chat-a", edited) }
        composeRule.runOnIdle {
            assertEquals(baseline, compositions)
            assertEquals(initial, restored?.textFieldValue)
            revision.value = 1 to 0
        }
        composeRule.runOnIdle {
            assertEquals(edited, restored?.textFieldValue)
            store.set("account", "chat-a", dictated)
            revision.value = 1 to 1
        }
        composeRule.runOnIdle {
            assertEquals(dictated, restored?.textFieldValue)
            owner.value = "account" to "chat-b"
        }
        composeRule.runOnIdle { assertEquals(other, restored?.textFieldValue) }
    }
}
