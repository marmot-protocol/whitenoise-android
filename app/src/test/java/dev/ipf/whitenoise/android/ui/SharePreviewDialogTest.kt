package dev.ipf.whitenoise.android.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.share.ContactPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the confirm-before-send contract for the contact preview screen:
 * nothing is sent until the send action, cancel never sends, and deselected
 * fields are dropped from what goes out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SharePreviewDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun contactPreviewShowsFieldsAndSendsOnlyOnConfirm() {
        var sent: SharedContact? = null
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ContactPreviewScreen(
                    contact = SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = "ada@example.org"),
                    onDismiss = { dismissed = true },
                    onSend = { sent = it },
                )
            }
        }
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("+1 555 0100").assertIsDisplayed()
        assertNull(sent)
        composeRule.onNodeWithContentDescription(app.getString(R.string.send)).performClick()
        assertEquals("+1 555 0100", sent?.phone)
        assertEquals("ada@example.org", sent?.email)
        assertFalse(dismissed)
    }

    @Test
    fun deselectedFieldsAreDroppedFromTheSend() {
        var sent: SharedContact? = null
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ContactPreviewScreen(
                    contact = SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = "ada@example.org"),
                    onDismiss = {},
                    onSend = { sent = it },
                )
            }
        }
        // Untick the email row, then send — only the phone should ride along.
        composeRule.onNodeWithText("ada@example.org").performClick()
        composeRule.onNodeWithContentDescription(app.getString(R.string.send)).performClick()
        assertEquals("+1 555 0100", sent?.phone)
        assertNull(sent?.email)
    }

    @Test
    fun cancelNeverSends() {
        var sent: SharedContact? = null
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ContactPreviewScreen(
                    contact = SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = null),
                    onDismiss = { dismissed = true },
                    onSend = { sent = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        assertTrue(dismissed)
        assertNull(sent)
    }

    @Test
    fun deselectingEveryContactMethodDisablesSend() {
        var sent: SharedContact? = null
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ContactPreviewScreen(
                    contact = SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = "ada@example.org"),
                    onDismiss = {},
                    onSend = { sent = it },
                )
            }
        }
        composeRule.onNodeWithText("+1 555 0100").performClick()
        composeRule.onNodeWithText("ada@example.org").performClick()
        composeRule
            .onNodeWithContentDescription(app.getString(R.string.send))
            .assertIsNotEnabled()
        assertNull(sent)
    }
}
