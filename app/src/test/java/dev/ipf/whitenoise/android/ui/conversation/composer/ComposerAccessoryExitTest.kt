package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * A dismissed reply preview leaves as an inert picture that plays its entrance in reverse. The live
 * content is gone from the first closing frame, and reduced motion removes it at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w240dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerAccessoryExitTest {
    @get:Rule val composeRule = createComposeRule()
    private val present = mutableStateOf(true)
    private lateinit var exit: ComposerAccessoryExit

    @Test fun dismissedReplySinksAwayLight() = replyExit(dark = false)

    @Test fun dismissedReplySinksAwayDark() = replyExit(dark = true)

    @Test fun reducedMotionRemovesTheReplyAtOnce() {
        render(motion = false, dark = false)
        close()
        assertFalse(exit.exiting)
    }

    private fun replyExit(dark: Boolean) {
        render(motion = true, dark = dark)
        close()
        assertTrue(exit.exiting)
        composeRule.onNodeWithText("Replying to Ada").assertDoesNotExist()
        advanceFrames(COMPOSER_ACCESSORY_EXIT_MILLIS / 2L)
        capture("composer_reply_exit_mid_${if (dark) "dark" else "light"}")
        advanceFrames(SETTLE_MILLIS)
        assertFalse(exit.exiting)
    }

    private fun render(
        motion: Boolean,
        dark: Boolean,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val accessory =
                        rememberComposerAccessoryExit(
                            present = present.value,
                            motionEnabled = motion,
                        )
                    exit = accessory
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Column {
                            ReplySlot(accessory)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                            ) { Text("Message", modifier = Modifier.padding(10.dp)) }
                        }
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
        // Robolectric draws only on demand; draw once so the live accessory records its picture.
        composeRule.onRoot().captureToImage()
    }

    @androidx.compose.runtime.Composable
    private fun ReplySlot(accessory: ComposerAccessoryExit) {
        if (present.value) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier =
                    Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                        .height(48.dp)
                        .composerAccessoryExitSource(accessory),
            ) { Text("Replying to Ada", modifier = Modifier.padding(12.dp)) }
        } else if (accessory.exiting) {
            with(LocalDensity.current) {
                Box(
                    Modifier
                        .padding(8.dp)
                        .size(accessory.widthPx.toDp(), accessory.heightPx.toDp())
                        .composerAccessoryExitGhost(accessory),
                )
            }
        }
    }

    private fun close() {
        composeRule.runOnIdle { present.value = false }
        composeRule.waitForIdle()
        advanceFrames(FRAME_MILLIS)
    }

    private fun advanceFrames(millis: Long) {
        repeat((millis / FRAME_MILLIS).toInt().coerceAtLeast(1)) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}

private const val SETTLE_MILLIS = 600L
private const val FRAME_MILLIS = 16L
