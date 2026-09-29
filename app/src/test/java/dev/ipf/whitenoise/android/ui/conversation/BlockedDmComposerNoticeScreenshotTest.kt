package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.BlockedDmComposerNotice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual and action coverage for the blocked-DM composer replacement. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class BlockedDmComposerNoticeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The light notice exposes an actionable Unblock control. */
    @Test
    fun blockedNoticeLightOffersUnblock() {
        var clicks = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                BlockedDmComposerNotice(unblockInFlight = false, onUnblock = { clicks++ })
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/blocked_dm_composer_light.png")
        composeRule.onNodeWithTag("conversation.blocked_dm_unblock").performClick()
        assertEquals(1, clicks)
    }

    /** Pending unblock disables a second request in dark theme. */
    @Test
    fun blockedNoticeDarkDisablesRepeatedUnblock() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                BlockedDmComposerNotice(unblockInFlight = true, onUnblock = {})
            }
        }
        composeRule.onNodeWithTag("conversation.blocked_dm_unblock").assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/blocked_dm_composer_dark_busy.png")
    }

    /** The Unblock control remains reachable at large text size in RTL. */
    @Test
    fun blockedNoticeLargeTextRtlKeepsUnblockReachable() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    BlockedDmComposerNotice(unblockInFlight = false, onUnblock = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/blocked_dm_composer_large_rtl.png")
    }
}
