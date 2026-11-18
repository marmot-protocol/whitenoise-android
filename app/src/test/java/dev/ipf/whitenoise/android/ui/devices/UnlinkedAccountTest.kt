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

/** What a device shows once another device unlinked it, and how a newly linked device treats its own chats. */
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

    /** Only a pending chat welcomed by the account itself counts as added by one of its own devices. */
    @Test
    fun onlyAPendingChatWelcomedByTheAccountItselfIsItsOwn() {
        assertTrue(addedByOwnDevice(pending = true, welcomerAccountIdHex = "AB12", ownAccountIdHex = " ab12 "))
        assertFalse(addedByOwnDevice(pending = false, welcomerAccountIdHex = "ab12", ownAccountIdHex = "ab12"))
        assertFalse(addedByOwnDevice(pending = true, welcomerAccountIdHex = "cd34", ownAccountIdHex = "ab12"))
        assertFalse(addedByOwnDevice(pending = true, welcomerAccountIdHex = null, ownAccountIdHex = "ab12"))
        assertFalse(addedByOwnDevice(pending = true, welcomerAccountIdHex = "", ownAccountIdHex = ""))
        assertFalse(addedByOwnDevice(pending = true, welcomerAccountIdHex = "ab12", ownAccountIdHex = null))
    }

    /** The arrival hint belongs to the account that linked, in any letter case, until it is ended. */
    @Test
    fun theArrivalHintIsForTheAccountThatLinked() {
        assertFalse(ChatsArrivingHint.shown)

        ChatsArrivingHint.mark("AB12")

        assertTrue(ChatsArrivingHint.shown)
        assertTrue(ChatsArrivingHint.showsFor("ab12"))
        assertFalse(ChatsArrivingHint.showsFor("cd34"))
        assertFalse(ChatsArrivingHint.showsFor(null))

        ChatsArrivingHint.end()

        assertFalse(ChatsArrivingHint.showsFor("ab12"))
    }
}
