package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.GroupChangeCaution
import dev.ipf.whitenoise.android.state.GroupChangeConfirmation
import dev.ipf.whitenoise.android.state.GroupChangeTarget
import dev.ipf.whitenoise.android.ui.group.GroupChangeConfirmationDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app-level question before overriding a group change MDK refused (#3289): Wait is the safe dismissal, and the
 * continue action takes the error role. It names the chat the change is for. Covers both cautions, the stronger invite
 * wording, themes and large RTL type.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChangeConfirmationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A change while the group may have moved on, in the light theme; Wait answers "do not proceed". */
    @Test
    fun catchingUpLight() {
        val decision = render(GroupChangeCaution.CatchingUp)
        capture("catching_up_light")

        composeRule.onNodeWithText("Weekend hikers").assertExists()
        composeRule.onNodeWithText("Wait").performClick()
        assertEquals(false, decision.getCompleted())
    }

    /** An invitation while the group may have moved on uses MDK's stronger wording, in the dark theme. */
    @Test
    fun catchingUpInviteDark() {
        render(GroupChangeCaution.CatchingUp, invite = true, dark = true)
        capture("catching_up_invite_dark")
    }

    /** An undelivered last change at large RTL type keeps both actions reachable. */
    @Test
    fun undeliveredLargeRtl() {
        render(GroupChangeCaution.Undelivered, rtl = true, fontScale = LARGE)
        capture("undelivered_large_rtl")
    }

    /** The same large RTL layout in the dark theme. */
    @Test
    fun undeliveredDarkLargeRtl() {
        render(GroupChangeCaution.Undelivered, dark = true, rtl = true, fontScale = LARGE)
        capture("undelivered_dark_large_rtl")
    }

    /** Shows one question and returns the decision it will record. */
    private fun render(
        caution: GroupChangeCaution,
        invite: Boolean = false,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ): CompletableDeferred<Boolean> {
        val decision = CompletableDeferred<Boolean>()
        val question = GroupChangeConfirmation(caution, invite, TARGET, decision)
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) { GroupChangeConfirmationDialog(question) }
            }
        }
        return decision
    }

    /** Captures only the dialog window. */
    private fun capture(variant: String) {
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_change_question_$variant.png")
    }

    private companion object {
        const val LARGE = 2f
        val TARGET = GroupChangeTarget("alice", "0a".repeat(16), runtimeGeneration = 0, title = "Weekend hikers")
    }
}
