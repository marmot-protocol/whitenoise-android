package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountHistoryNoticeBannerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The account-wide notice explains the uncertainty and offers only the user's dismissal. */
    @Test
    fun accountNoticeLight() = capture(dark = false, amoled = false, largeRtl = false, name = "light")

    /** Dark theme keeps the notice readable above the chat rows. */
    @Test
    fun accountNoticeDark() = capture(dark = true, amoled = false, largeRtl = false, name = "dark")

    /** RTL at 200% text wraps the message without pushing Dismiss off screen. */
    @Test
    fun accountNoticeAmoledLargeRtl() = capture(dark = true, amoled = true, largeRtl = true, name = "amoled_large_rtl")

    /** Dismiss is disabled while a dismissal is being recorded. */
    @Test
    fun dismissIsDisabledWhileRecording() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountHistoryNoticeBanner(dismissing = true, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Dismiss").assertIsNotEnabled()
    }

    private fun capture(
        dark: Boolean,
        amoled: Boolean,
        largeRtl: Boolean,
        name: String,
    ) {
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    AccountHistoryNoticeBanner(
                        dismissing = false,
                        onDismiss = { dismissals++ },
                        modifier = Modifier.width(360.dp).testTag("account-history-notice"),
                    )
                }
            }
        }

        composeRule
            .onNodeWithText(
                "Some messages may be missing from this account. " +
                    "White Noise couldn’t confirm that every message arrived.",
            ).assertIsDisplayed()
        composeRule
            .onNodeWithTag("account-history-notice")
            .captureRoboImage("src/test/snapshots/account_history_notice_$name.png")
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }
}
