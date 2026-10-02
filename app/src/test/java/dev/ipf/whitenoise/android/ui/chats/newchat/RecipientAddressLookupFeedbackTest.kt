package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual lookup-failure row used by Add members and New group. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecipientAddressLookupFeedbackTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun failedLookupOffersExplicitRetry() {
        var retries = 0
        composeRule.setContent { WhiteNoiseTheme { RecipientAddressLookupFeedback(false) { retries++ } } }
        composeRule.onNodeWithText(context.getString(R.string.user_search_address_unverified)).assertExists()
        composeRule.onNodeWithTag("address.retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun addingMembersDisablesCompetingRetry() {
        composeRule.setContent { WhiteNoiseTheme { RecipientAddressLookupFeedback(true) {} } }
        composeRule.onNodeWithTag("address.retry").assertIsNotEnabled()
    }
}
