package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatsArrivingHint
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
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

    /** Leaves no arrival hint behind for another test. */
    @After
    fun endHint() = ChatsArrivingHint.end()

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

    /** The arrival hint belongs to the account that linked, in any letter case, until it is ended. */
    @Test
    fun theArrivalHintIsForTheAccountThatLinked() {
        assertFalse(ChatsArrivingHint.shown)

        ChatsArrivingHint.mark("AB12", nowMs = 1_000L, durationMs = 500L)

        assertTrue(ChatsArrivingHint.shown)
        assertTrue(ChatsArrivingHint.showsFor("ab12"))
        assertFalse(ChatsArrivingHint.showsFor("cd34"))
        assertFalse(ChatsArrivingHint.showsFor(null))
        assertEquals(200L, ChatsArrivingHint.remainingMs(nowMs = 1_300L))
        assertEquals(0L, ChatsArrivingHint.remainingMs(nowMs = 9_000L))

        ChatsArrivingHint.end()

        assertFalse(ChatsArrivingHint.showsFor("ab12"))
    }
}
