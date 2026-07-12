package dev.ipf.whitenoise.android.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.share.ContactSharePreviewDialog
import dev.ipf.whitenoise.android.ui.conversation.share.LocationSharePreviewDialog
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.SharedLocation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the confirm-before-send contract: nothing is sent until the preview's
 * send action, and cancel never sends.
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
        var sent = false
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ContactSharePreviewDialog(
                    contact = SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = "ada@example.org"),
                    onDismiss = { dismissed = true },
                    onSend = { sent = true },
                )
            }
        }
        composeRule.onNodeWithText("Ada Lovelace\n+1 555 0100\nada@example.org").assertIsDisplayed()
        assertFalse(sent)
        composeRule.onNodeWithText(app.getString(R.string.send)).performClick()
        assertTrue(sent)
        assertFalse(dismissed)
    }

    @Test
    fun contactPreviewCancelNeverSends() {
        var sent = false
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ContactSharePreviewDialog(
                    contact = SharedContact(name = "Ada Lovelace", phone = null, email = null),
                    onDismiss = { dismissed = true },
                    onSend = { sent = true },
                )
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        assertTrue(dismissed)
        assertFalse(sent)
    }

    @Test
    fun locationPreviewShowsCoordinatesAccuracyAndConfirms() {
        var sends = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                LocationSharePreviewDialog(
                    location = SharedLocation(latitude = 52.520008, longitude = 13.404954, accuracyMeters = 12),
                    onDismiss = {},
                    onSend = { sends++ },
                )
            }
        }
        composeRule.onNodeWithText("52.520008, 13.404954").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.location_accuracy_format, 12)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.send)).performClick()
        assertEquals(1, sends)
    }
}
