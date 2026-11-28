package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.common.ExplanationHostState
import dev.ipf.whitenoise.android.ui.common.LocalExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Which surface a hold in the header title opens: the timer's tooltip or the Chat Details explanation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationTopBarHoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Holding the chat name explains Chat Details, even directly above the timer, inside its widened touch area. */
    @Test
    fun holdingTheChatNameExplainsDetails() {
        val (explained, tooltip) = holdInTimerHeader(NAME_TAG) { timerX -> Offset(timerX, bottom - 4.dp.toPx()) }
        assertTrue("holding the chat name explains Chat Details", explained)
        assertFalse("holding the chat name must not open the timer tooltip", tooltip)
    }

    /** Holding the timer opens only its tooltip, not the Chat Details explanation as well. */
    @Test
    fun holdingTheTimerOpensOnlyItsTooltip() {
        val (explained, tooltip) = holdInTimerHeader(TIMER_TAG) { center }
        assertFalse("holding the timer must not also open the Chat Details explanation", explained)
        assertTrue("holding the timer opens its tooltip", tooltip)
    }

    /** Compose widens the short timer line to a 48dp touch target, so a hold just below it is still the timer's. */
    @Test
    fun holdingTheTimersWidenedTargetOpensOnlyItsTooltip() {
        val (explained, tooltip) = holdInTimerHeader(TIMER_TAG) { Offset(centerX, bottom + 3.dp.toPx()) }
        assertFalse("a hold in the timer's widened touch target must not open the explanation", explained)
        assertTrue("a hold in the timer's widened touch target opens its tooltip", tooltip)
    }

    /** A mouse hold on the timer still explains Chat Details, since Material's tooltip opens on touch and stylus. */
    @Test
    fun mouseHoldOnTheTimerStillExplainsDetails() {
        val host = renderTimerHeader()
        composeRule.onNodeWithTag(TIMER_TAG, useUnmergedTree = true).performMouseInput { longClick() }
        composeRule.waitForIdle()
        assertTrue("a mouse hold on the timer explains Chat Details", host.shown != null)
    }

    /**
     * Renders the timer header, holds [tag] at [at], and returns whether the Chat Details explanation and the timer
     * tooltip opened. [at] gets the timer's centre x in [tag]'s local coordinates.
     */
    private fun holdInTimerHeader(
        tag: String,
        at: TouchInjectionScope.(timerX: Float) -> Offset,
    ): Pair<Boolean, Boolean> {
        val host = renderTimerHeader()
        val held = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        val timer = composeRule.onNodeWithTag(TIMER_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timerX = timer.center.x - held.fetchSemanticsNode().boundsInRoot.left
        held.performTouchInput { longClick(at(timerX)) }
        composeRule.waitForIdle()
        val tooltips = composeRule.onAllNodesWithTag("conversation.timer.tooltip").fetchSemanticsNodes()
        return (host.shown != null) to tooltips.isNotEmpty()
    }

    /** Renders a header with the timer on, its first-run hint spent and the tooltip closed, and returns its host. */
    private fun renderTimerHeader(): ExplanationHostState {
        val appState = topBarTestAppState(context, ACCOUNT_REF, ACCOUNT_ID)
        appState.markDisappearingTooltipShown()
        val host = ExplanationHostState()
        val group = topBarTestGroup(GROUP_ID, adminId = ACCOUNT_ID).copy(disappearingMessageSecs = 86_400uL)
        val controller = ConversationController(appState, group)
        composeRule.setContent {
            CompositionLocalProvider(LocalExplanationHost provides host) {
                WhiteNoiseTheme { Surface { TopBarUnderTest(appState, controller, "Open group details") } }
            }
        }
        composeRule.onNodeWithTag("conversation.timer.tooltip").assertDoesNotExist()
        return host
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val NAME_TAG = "conversation.header.name"
        const val TIMER_TAG = "conversation.header.timer"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
