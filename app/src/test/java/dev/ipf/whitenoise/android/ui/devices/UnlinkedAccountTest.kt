package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a device shows once another device unlinked it, and the hint a newly linked device shows for its chats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class UnlinkedAccountTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The prompt explains the removal and its one action asks for it once. */
    @Test
    fun thePromptRemovesTheAccount() {
        var removals = 0
        compose.setContent { WhiteNoiseTheme { UnlinkedAccountPrompt(onRemove = { removals++ }) } }

        compose.onNodeWithText(context.getString(R.string.device_unlinked_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.unlinked_account_remove_detail)).assertIsDisplayed()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_REMOVE_TAG).performClick()

        compose.runOnIdle { assertEquals(1, removals) }
    }

    /** Back neither removes the account nor closes the prompt, so its chats never come back into view. */
    @Test
    fun backLeavesThePromptUp() {
        var removals = 0
        compose.setContent { WhiteNoiseTheme { UnlinkedAccountPrompt(onRemove = { removals++ }) } }

        Espresso.pressBack()

        compose.onNodeWithTag(UNLINKED_ACCOUNT_PROMPT_TAG).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, removals) }
    }

    /** Until a removal has failed the prompt offers no way to close it. */
    @Test
    fun thePromptCannotBeClosedBeforeARemovalFailed() {
        compose.setContent { WhiteNoiseTheme { UnlinkedAccountPrompt(onRemove = {}) } }

        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).assertDoesNotExist()
    }

    /** After a failed removal the prompt can be closed, so the device is not stuck behind it. */
    @Test
    fun aFailedRemovalLetsThePromptBeClosed() {
        var closes = 0
        compose.setContent { WhiteNoiseTheme { UnlinkedAccountPrompt(onRemove = {}, onClose = { closes++ }) } }

        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).performClick()

        compose.runOnIdle { assertEquals(1, closes) }
    }

    /** A removal that left the account here offers Close, even though the account left view while it ran. */
    @Test
    fun closeIsOfferedAfterARemovalThatLeftTheAccountHere() {
        var label by mutableStateOf<String?>("a")
        var hidden by mutableStateOf(false)
        compose.setContent { WhiteNoiseTheme { UnlinkedAccountPromptGate(label, hidden) { hidden = true } } }
        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).assertDoesNotExist()

        compose.onNodeWithTag(UNLINKED_ACCOUNT_REMOVE_TAG).performClick()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_PROMPT_TAG).assertDoesNotExist()
        label = null
        compose.waitForIdle()
        label = "a"
        hidden = false

        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).performClick()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_PROMPT_TAG).assertDoesNotExist()
    }

    /** Another unlinked account coming into view starts with no attempt, so it offers no Close. */
    @Test
    fun anotherAccountStartsWithoutClose() {
        var label by mutableStateOf<String?>("a")
        compose.setContent { WhiteNoiseTheme { UnlinkedAccountPromptGate(label, hidden = false) {} } }
        compose.onNodeWithTag(UNLINKED_ACCOUNT_REMOVE_TAG).performClick()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).assertExists()

        label = "b"

        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).assertDoesNotExist()
    }

    /** Closing one account's prompt does not hide it after another account, linked or not, was in view. */
    @Test
    fun aClosedPromptReturnsAfterAnotherAccountWasInView() {
        var inView by mutableStateOf<String?>("a")
        compose.setContent {
            WhiteNoiseTheme { UnlinkedAccountPromptGate(inView.takeIf { it == "a" }, hidden = false, inView) {} }
        }
        compose.onNodeWithTag(UNLINKED_ACCOUNT_REMOVE_TAG).performClick()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).performClick()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_PROMPT_TAG).assertDoesNotExist()

        inView = "b"
        compose.waitForIdle()
        inView = "a"

        compose.onNodeWithTag(UNLINKED_ACCOUNT_PROMPT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).assertDoesNotExist()
    }

    /** A removal takes the account out of view for a moment, which forgets neither the attempt nor a close. */
    @Test
    fun theGapARemovalLeavesKeepsAClosedPromptClosed() {
        var inView by mutableStateOf<String?>("a")
        compose.setContent {
            WhiteNoiseTheme { UnlinkedAccountPromptGate(inView.takeIf { it == "a" }, hidden = false, inView) {} }
        }
        compose.onNodeWithTag(UNLINKED_ACCOUNT_REMOVE_TAG).performClick()
        compose.onNodeWithTag(UNLINKED_ACCOUNT_CLOSE_TAG).performClick()

        inView = null
        compose.waitForIdle()
        inView = "a"

        compose.onNodeWithTag(UNLINKED_ACCOUNT_PROMPT_TAG).assertDoesNotExist()
    }

    /** Only a profile saved as unlinked asks to be removed when picked. */
    @Test
    fun onlyAnUnlinkedProfileAsksToBeRemoved() {
        assertTrue(asksToRemoveUnlinkedProfile("old", unlinked = listOf("old")))
        assertFalse(asksToRemoveUnlinkedProfile("main", unlinked = listOf("old")))
        assertFalse(asksToRemoveUnlinkedProfile("main", unlinked = emptyList()))
    }

    /** The removal question says what is deleted, removes on confirmation and changes nothing on Cancel. */
    @Test
    fun theRemovalQuestionRemovesOnlyOnConfirmation() {
        var removals = 0
        var cancels = 0
        compose.setContent {
            WhiteNoiseTheme { UnlinkedProfileRemovalDialog(onRemove = { removals++ }, onCancel = { cancels++ }) }
        }

        compose.onNodeWithText(context.getString(R.string.unlinked_account_remove_detail)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0 to 1, removals to cancels) }
        compose.onNodeWithTag(UNLINKED_PROFILE_REMOVAL_CONFIRM_TAG).performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    /** A pick is remembered until it is closed, so the question can be asked after its sheet has gone. */
    @Test
    fun aPickedProfileIsRememberedUntilClosed() {
        UnlinkedProfileRemoval.ask("old")
        assertEquals("old", UnlinkedProfileRemoval.askedFor)

        UnlinkedProfileRemoval.close()

        assertEquals(null, UnlinkedProfileRemoval.askedFor)
    }
}
