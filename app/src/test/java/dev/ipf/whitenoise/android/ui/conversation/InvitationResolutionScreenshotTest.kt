package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Invitation-specific progress and recovery never expose consent actions while authority is unknown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class InvitationResolutionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun checkingInvitationLight() = capture(dark = false, rtl = false, state = GroupRosterLoadState.LOADING)

    @Test fun failedInvitationDarkLargeRtl() = capture(dark = true, rtl = true, state = GroupRosterLoadState.FAILED)

    private fun capture(
        dark: Boolean,
        rtl: Boolean,
        state: GroupRosterLoadState,
    ) {
        var retries = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(1f, if (rtl) 1.5f else 1f),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        Column { InviteAcceptanceResolutionStatus(state = state, onRetry = { retries += 1 }) }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Accept").assertDoesNotExist()
        composeRule.onNodeWithText("Decline").assertDoesNotExist()
        val failed = state == GroupRosterLoadState.FAILED
        if (failed) {
            composeRule.onNodeWithText("Couldn’t check invitation").assertExists()
            composeRule.onNodeWithText("Retry").assertHasClickAction().performClick()
            assertEquals(1, retries)
        } else {
            composeRule.onNodeWithText("Checking invitation…").assertExists()
            composeRule.onNodeWithText("Retry").assertDoesNotExist()
        }
        val variant = if (failed) "failed_dark_large_rtl" else "checking_light"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/invitation_resolution_$variant.png")
    }
}
