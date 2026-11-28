package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.navigation.ShellRoute
import dev.ipf.whitenoise.android.ui.navigation.settingsChain
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val hub = context.getString(R.string.settings_help_support)
        var current: SettingsDetail? = null
        composeRule.setContent {
            var stack by remember { mutableStateOf(settingsChain(null)) }
            var viewport by remember { mutableStateOf(SettingsHomeViewport.Top) }
            WhiteNoiseTheme {
                SettingsScreen(
                    appState,
                    {},
                    {},
                    {},
                    stack,
                    onStackChange = {
                        stack = it
                        current = it.last().detail
                    },
                    homeViewport = viewport,
                    onHomeViewportChange = { viewport = it },
                )
            }
        }
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(hub))
        val original = composeRule.onNodeWithText(hub).fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText(hub).performClick()
        assertEquals(SettingsDetail.HelpSupport, current)
        composeRule.onNodeWithText(support).performClick()
        assertEquals(SettingsDetail.Support, current)
        // The relays row is only emitted once the support relay state has left
        // Loading, which resolves asynchronously after the screen opens. Wait for
        // the row itself rather than racing that resolution.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("support.relays").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("support.relays").performScrollTo().performClick()
        assertEquals(SettingsDetail.SupportRelays, current)
        val back = context.getString(R.string.back)
        composeRule.onNodeWithContentDescription(back).performClick()
        assertEquals(SettingsDetail.Support, current)
        composeRule.onNodeWithContentDescription(back).performClick()
        assertEquals(SettingsDetail.HelpSupport, current)
        composeRule.onNodeWithContentDescription(back).performClick()
        assertNull(current)
        composeRule.waitForIdle()
        val restored = composeRule.onNodeWithText(hub).fetchSemanticsNode().boundsInRoot
        assertTrue("expected $original but was $restored", kotlin.math.abs(original.top - restored.top) <= 1f)
    }

    /** Developer opened from Help & support returns there, and Licenses and a notice page Back through About. */
    @Test fun pagesReturnToThePageThatOpenedThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = supportTestAccount(context, "alice")
        composeRule.runOnIdle { appState.updateDeveloperMode(true) }
        var stack by mutableStateOf(settingsChain(SettingsDetail.HelpSupport))
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsScreen(
                    appState,
                    {},
                    {},
                    {},
                    stack,
                    onStackChange = { stack = it },
                    homeViewport = SettingsHomeViewport.Top,
                    onHomeViewportChange = {},
                )
            }
        }
        val back = context.getString(R.string.back)
        val developerTools = context.getString(R.string.settings_developer_tools)
        composeRule.onNodeWithText(developerTools).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(SettingsDetail.Developer, stack.last().detail) }
        composeRule.onNodeWithContentDescription(back).performClick()
        composeRule.runOnIdle { assertEquals(settingsChain(SettingsDetail.HelpSupport), stack) }

        composeRule.runOnIdle { stack = settingsChain(SettingsDetail.About) }
        composeRule.onNodeWithTag("about.licenses").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(settingsChain(SettingsDetail.Licenses), stack) }
        composeRule.runOnIdle { stack = stack + ShellRoute.Settings(SettingsDetail.LicenseNotice, "missing") }
        // A notice this build does not bundle returns to the list, then Back leaves the list for About.
        composeRule.waitUntil(5_000) { stack.last().detail == SettingsDetail.Licenses }
        composeRule.onNodeWithContentDescription(back).performClick()
        composeRule.runOnIdle { assertEquals(settingsChain(SettingsDetail.About), stack) }
    }
}
