package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The question an admin invitee answers before Decline steps them down, across light, dark, AMOLED and large RTL
 * text, with each button wired to exactly one callback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class InviteDeclineStepDownDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The step-down question and both answers in the light dialog surface. */
    @Test
    fun stepDownQuestionLight() = capture("light", dark = false)

    /** The step-down question stays legible on the dark dialog surface. */
    @Test
    fun stepDownQuestionDark() = capture("dark", dark = true)

    /** AMOLED keeps the dialog outline and the error-coloured confirm action. */
    @Test
    fun stepDownQuestionAmoled() = capture("amoled", dark = true, amoled = true)

    /** At 200% RTL text the explanation wraps and both answers stay reachable. */
    @Test
    fun stepDownQuestionLargeRtl() = capture("large_rtl", dark = true, largeRtl = true)

    /** Renders the dialog, records its baseline, and checks that each answer reaches only its own callback. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    InviteDeclineStepDownDialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
                }
            }
        }
        composeRule.onNodeWithText("Step down and decline?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "This invitation makes you an admin. To decline, you’ll step down as admin first, " +
                    "and the group’s members will see that you stepped down.",
            ).assertIsDisplayed()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/invite_decline_step_down_$name.png")

        composeRule.onNodeWithTag("invite.decline.stepDown.cancel").performClick()
        composeRule.runOnIdle { assertEquals(0 to 1, confirmed to dismissed) }
        composeRule.onNodeWithTag("invite.decline.stepDown.confirm").performClick()
        composeRule.runOnIdle { assertEquals(1 to 1, confirmed to dismissed) }
    }
}
