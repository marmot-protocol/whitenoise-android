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
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sign-in offer and the chat list's reminder send only the answer the user chose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class DeviceLinkOfferTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Link this device on the offer asks to link and does not postpone. */
    @Test
    fun theOfferLinks() {
        var links = 0
        var postponed = 0
        compose.setContent { WhiteNoiseTheme { DeviceLinkOfferDialog({ links++ }, { postponed++ }) } }

        compose.onNodeWithText(context.getString(R.string.link_offer_title)).assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_LINK_OFFER_LINK_TAG).performClick()

        compose.runOnIdle { assertEquals(1 to 0, links to postponed) }
    }

    /** Not now and Back both postpone, and neither asks to link. */
    @Test
    fun notNowAndBackPostpone() {
        var links = 0
        var postponed = 0
        compose.setContent { WhiteNoiseTheme { DeviceLinkOfferDialog({ links++ }, { postponed++ }) } }

        compose.onNodeWithText(context.getString(R.string.link_request_not_now)).performClick()
        Espresso.pressBack()

        compose.runOnIdle { assertEquals(0 to 2, links to postponed) }
    }

    /** The reminder's two actions each send their own answer. */
    @Test
    fun theReminderLinksOrDismisses() {
        var links = 0
        var dismissed = 0
        compose.setContent { WhiteNoiseTheme { DeviceLinkReminderBanner({ links++ }, { dismissed++ }) } }

        compose.onNodeWithText(context.getString(R.string.link_offer_reminder)).assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_LINK_REMINDER_LINK_TAG).performClick()
        compose.runOnIdle { assertEquals(1 to 0, links to dismissed) }
        compose.onNodeWithTag(DEVICE_LINK_REMINDER_DISMISS_TAG).performClick()
        compose.runOnIdle { assertEquals(1 to 1, links to dismissed) }
    }
}
