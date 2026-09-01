package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.messages.SWIPE_TEST_HOST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.SwipeTestBubbleHost
import dev.ipf.whitenoise.android.ui.conversation.messages.swipeTestSurface
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic mid-swipe captures for a reacted message: the held drag must
 * render the bubble and its reaction chip translated together. The settled
 * state is covered by the existing bubble baselines, which this change leaves
 * pixel-identical at rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-mdpi")
class MessageBubbleSwipeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Incoming reacted text held mid-swipe in the dark theme. */
    @Test
    fun midSwipeReactedIncomingDark() {
        captureMidSwipe(mine = false, rtl = false, amoled = false, path = "message_swipe_reaction_dark.png")
    }

    /** Outgoing reacted text held mid-swipe on AMOLED. */
    @Test
    fun midSwipeReactedOutgoingAmoled() {
        captureMidSwipe(mine = true, rtl = false, amoled = true, path = "message_swipe_reaction_amoled.png")
    }

    /** RTL keeps the chip attached to the translated bubble mid-swipe. */
    @Test
    fun midSwipeReactedIncomingRtl() {
        captureMidSwipe(mine = false, rtl = true, amoled = false, path = "message_swipe_reaction_rtl.png")
    }

    /** Renders one reacted bubble, holds the drag at a fixed offset, and captures the host. */
    private fun captureMidSwipe(
        mine: Boolean,
        rtl: Boolean,
        amoled: Boolean,
        path: String,
    ) {
        val surface = swipeTestSurface(context, reacted = true, mine = mine, media = false)
        composeRule.setContent {
            SwipeTestBubbleHost(surface = surface, rtl = rtl, amoled = amoled)
        }
        val host = composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG)
        host.performTouchInput {
            down(centerLeft)
            moveBy(Offset(DRAG_PX, 0f))
        }
        composeRule.waitForIdle()

        host.captureRoboImage("src/test/snapshots/$path")

        host.performTouchInput { cancel() }
        composeRule.waitForIdle()
    }

    private companion object {
        const val DRAG_PX = 90f
    }
}
