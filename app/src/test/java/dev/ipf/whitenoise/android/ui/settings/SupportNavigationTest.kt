package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual hub→Support→Relays route wiring without creating or sending a support message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SupportNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    /** Both toolbar backs return through Support and restore the original Settings home viewport. */
    @Test fun recoveryReturnsThroughSupportToTheSameHubPosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = supportTestAccount(context, "alice")
        val support = context.getString(R.string.chat_with_support)
        var current: SettingsDetail? = null
        composeRule.setContent {
            var detail by remember { mutableStateOf<SettingsDetail?>(null) }
            var viewport by remember { mutableStateOf(SettingsHomeViewport.Top) }
            WhiteNoiseTheme {
                SettingsScreen(
                    appState,
                    {},
                    {},
                    {},
                    detail,
                    onDetailChange = {
                        detail = it
                        current = it
                    },
                    homeViewport = viewport,
                    onHomeViewportChange = { viewport = it },
                )
            }
        }
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(support))
        val original = composeRule.onNodeWithText(support).fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText(support).performClick()
        assertEquals(SettingsDetail.Support, current)
        composeRule.onNodeWithTag("support.relays").performScrollTo().performClick()
        assertEquals(SettingsDetail.SupportRelays, current)
        val back = context.getString(R.string.back)
        composeRule.onNodeWithContentDescription(back).performClick()
        assertEquals(SettingsDetail.Support, current)
        composeRule.onNodeWithContentDescription(back).performClick()
        assertNull(current)
        assertEquals(original, composeRule.onNodeWithText(support).fetchSemanticsNode().boundsInRoot)
    }
}
