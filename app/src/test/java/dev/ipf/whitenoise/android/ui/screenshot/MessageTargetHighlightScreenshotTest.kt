package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.messages.BubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_TARGET_HIGHLIGHT_FADE_MILLIS
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageTargetHighlightScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fullReplyTargetHighlightLight() {
        render(darkTheme = false, amoled = false, customBorder = null, highlighted = true)
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/reply_target_highlight_full_light.png")
    }

    @Test
    fun replyTargetHighlightMidFadeOnCustomAmoledBubble() {
        composeRule.mainClock.autoAdvance = false
        val highlighted = mutableStateOf(true)
        render(darkTheme = true, amoled = true, customBorder = 0xFF9C6ADE, highlightedState = highlighted)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { highlighted.value = false }
        composeRule.mainClock.advanceTimeBy(MESSAGE_TARGET_HIGHLIGHT_FADE_MILLIS / 2L)

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/reply_target_highlight_mid_fade_amoled.png")
    }

    @Test
    fun settledReplyTargetUsesOrdinaryDarkBubbleChrome() {
        render(darkTheme = true, amoled = false, customBorder = null, highlighted = false)

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/reply_target_highlight_settled_dark.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
        customBorder: Long?,
        highlighted: Boolean = false,
        highlightedState: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(highlighted),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(modifier = Modifier.testTag(ROOT_TAG)) {
                    Column(Modifier.padding(24.dp)) {
                        MessageBubbleFrame(
                            presentation =
                                BubblePresentation(
                                    backgroundArgb =
                                        when {
                                            amoled -> 0xFF000000
                                            darkTheme -> 0xFF242124
                                            else -> 0xFFF0ECF4
                                        },
                                    contentArgb = if (darkTheme) 0xFFFFFFFF else 0xFF1D1B20,
                                    mentionAccentArgb = 0xFF9C6ADE,
                                    borderOverrideArgb = customBorder,
                                ),
                            highlighted = highlightedState.value,
                            mine = false,
                            mentionedSelf = false,
                            mentionedYouLabel = "Mentioned you",
                            modifier = Modifier.testTag(BUBBLE_TAG),
                        ) {
                            Text("Original reply target")
                            Box(Modifier.size(width = 180.dp, height = 8.dp))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ROOT_TAG = "reply-target-highlight-root"
        const val BUBBLE_TAG = "reply-target-highlight-bubble"
    }
}
