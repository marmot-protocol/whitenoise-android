@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleBottomAlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReactionSummary
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel regression for issue #2145 reaction-host enter motion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageReactionTransitionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reactionHostEnterLight() =
        captureReactionEnter(
            name = "message_reaction_enter_light",
            dark = false,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
        )

    @Test
    fun reactionHostEnterDarkLargeRtl() =
        captureReactionEnter(
            name = "message_reaction_enter_dark_large_rtl",
            dark = true,
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
        )

    private fun captureReactionEnter(
        name: String,
        dark: Boolean,
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        var tallies by mutableStateOf<List<ReactionTally>>(emptyList())

        composeRule.setContent {
            val density = LocalDensity.current
            WhiteNoiseTheme(darkTheme = dark) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    ReactionTransitionGallery(tallies = tallies)
                }
            }
        }
        composeRule.waitForIdle()
        val avatarTop = nodeTop("AL")
        val bubbleTextTop = nodeTop("Can you review the file?")
        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/${name}_before.png")

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            tallies = listOf(ReactionTally(emoji = "👍", count = 2, mine = true))
        }
        composeRule.runOnIdle { }

        val heightBefore = galleryHeight()
        repeat(ENTERING_FRAME_COUNT) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
        }
        val heightEntering = galleryHeight()
        assertTrue(
            "frame $ENTERING_FRAME_COUNT should land mid-enter: before=$heightBefore entering=$heightEntering",
            heightEntering > heightBefore + HEIGHT_TOLERANCE,
        )
        assertAnchoring(avatarTop = avatarTop, bubbleTextTop = bubbleTextTop)
        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/${name}_entering.png")

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.runOnIdle { }
        val heightAfter = galleryHeight()
        assertTrue(heightAfter > heightEntering + HEIGHT_TOLERANCE)
        assertAnchoring(avatarTop = avatarTop, bubbleTextTop = bubbleTextTop)
        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/${name}_after.png")

        composeRule.runOnUiThread { tallies = emptyList() }
        composeRule.runOnIdle { }
        repeat(ENTERING_FRAME_COUNT) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
        }
        assertAnchoring(avatarTop = avatarTop, bubbleTextTop = bubbleTextTop)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.runOnIdle { }
        assertAnchoring(avatarTop = avatarTop, bubbleTextTop = bubbleTextTop)
    }

    private fun galleryHeight(): Float =
        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .height

    private fun nodeTop(text: String): Float =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    private fun assertAnchoring(
        avatarTop: Float,
        bubbleTextTop: Float,
    ) {
        assertEquals(avatarTop, nodeTop("AL"), POSITION_TOLERANCE)
        assertEquals(bubbleTextTop, nodeTop("Can you review the file?"), POSITION_TOLERANCE)
    }

    @Composable
    private fun ReactionTransitionGallery(tallies: List<ReactionTally>) {
        val showReactionSummary = tallies.isNotEmpty()
        val visibilityState = remember { MutableTransitionState(showReactionSummary) }
        visibilityState.targetState = showReactionSummary
        val reactionHostPresent = visibilityState.currentState || visibilityState.targetState
        Surface(modifier = Modifier.width(360.dp).testTag(GALLERY_TAG)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Incoming", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MessageSenderAvatarSlot(
                        showSenderAvatar = true,
                        title = "Alex",
                        seed = "alex",
                        pictureUrl = null,
                        enabled = true,
                        alignToBubbleBottom = reactionHostPresent,
                        onClick = {},
                    )
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = 220.dp)
                                .then(
                                    if (reactionHostPresent) {
                                        Modifier.alignBy(MessageBubbleBottomAlignmentLine)
                                    } else {
                                        Modifier
                                    },
                                ),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        ReactionBubble(text = "Can you review the file?", mine = false)
                        MessageReactionSummary(
                            tallies = tallies,
                            mine = false,
                            visibilityState = visibilityState,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReactionBubble(
        text: String,
        mine: Boolean,
    ) {
        MessageBubbleFrame(
            presentation = messageBubblePresentation(deleted = false, mine = mine),
            highlighted = false,
            mine = mine,
            mentionedSelf = false,
            mentionedYouLabel = "Mentioned you",
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    private companion object {
        const val GALLERY_TAG = "message-reaction-transition-gallery"
        const val HEIGHT_TOLERANCE = 0.5f
        const val POSITION_TOLERANCE = 0.5f
        const val ENTERING_FRAME_COUNT = 8
    }
}
