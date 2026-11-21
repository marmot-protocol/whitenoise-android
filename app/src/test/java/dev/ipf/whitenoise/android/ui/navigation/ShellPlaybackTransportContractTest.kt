package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Each surface that shows the player bar keeps its own bar until that surface has left the screen. Selecting or
 * closing a conversation changes the route at once, and must not strip the bar from the surface still animating
 * out, which would collapse its header mid-transition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellPlaybackTransportContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts the shadow navigator agreed with the hand-written route on every comparison this test drove. */
    @get:Rule
    val shadow = ShellShadowRule()

    private val shell = MountedContractShell(composeRule)

    /** Releases the process-wide player so one test's session cannot reach the next. */
    @After
    fun resetPlayer() {
        VoicePlaybackController.stop()
    }

    /**
     * Back clears the selection at once, yet the closing conversation still shows its player bar beside the
     * returning list's until the conversation has gone.
     */
    @Test
    fun backKeepsTheClosingConversationsPlayerBarUntilItHasLeft() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            composeRule.runOnIdle { startVoiceSession() }
            shell.openTarget(fixture, holder)
            assertEquals("the open conversation shows the one bar", 1, bars())

            composeRule.mainClock.autoAdvance = false
            Espresso.pressBack()
            advanceHeldClockUntil { holder.selectedChat.value == null }
            assertEquals("the closing conversation and the returning list each show a bar", 2, bars())

            composeRule.mainClock.autoAdvance = true
            shell.settle()
            assertNull(holder.selectedChat.value)
            assertEquals("only the list's bar is left", 1, bars())
        }
    }

    /**
     * A tapped chat becomes the selection while the list is still leaving, and the list keeps its player bar
     * beside the arriving conversation's until the list has gone.
     */
    @Test
    fun openingAConversationKeepsTheListsPlayerBarUntilTheListHasLeft() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            composeRule.runOnIdle { startVoiceSession() }
            shell.settle()
            assertEquals("the list shows the one bar", 1, bars())

            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
            assertEquals("the tap alone leaves the list's bar in place", 1, bars())
            advanceHeldClockUntil { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            assertEquals("the leaving list and the arriving conversation each show a bar", 2, bars())

            composeRule.mainClock.autoAdvance = true
            shell.settle()
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            assertEquals("only the conversation's bar is left", 1, bars())
        }
    }

    /** Steps the held clock two frames at a time until [condition] holds, then composes that frame. */
    private fun advanceHeldClockUntil(condition: () -> Boolean) {
        repeat(MAX_HELD_STEPS) {
            composeRule.mainClock.advanceTimeBy(HELD_STEP_MILLIS)
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            // The conversation's first page is read on a worker thread, which needs wall-clock time.
            Thread.sleep(WORKER_YIELD_MILLIS)
        }
        throw AssertionError("the route never changed while the clock was held")
    }

    /** How many voice player bars are composed. */
    private fun bars(): Int = composeRule.onAllNodesWithTag(VOICE_BAR_TAG).fetchSemanticsNodes().size

    /** Publishes a ready voice session owned by the fixture's account, without any platform codec work. */
    @Suppress("UNCHECKED_CAST")
    private fun startVoiceSession() {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        val source = VoicePlaybackSource(ConversationTimelineTestIds.ACCOUNT_REF, "group", "message", "Maya")
        state.value =
            VoicePlaybackController.PlaybackState(key = "voice", ready = true, sessionId = 10, source = source)
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val HELD_STEP_MILLIS = 32L
        const val MAX_HELD_STEPS = 60
        const val WORKER_YIELD_MILLIS = 10L
        const val VOICE_BAR_TAG = "voice-transport"
    }
}
