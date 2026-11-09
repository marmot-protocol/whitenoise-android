package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
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
 * A reply chosen while the composer is open fades and rises into place; a reply restored with the
 * draft is already settled on the first frame, and reduced motion never animates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w240dp-h120dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerAccessoryEntranceTest {
    @get:Rule val composeRule = createComposeRule()
    private val replyKey = mutableStateOf<String?>(null)
    private var progress: () -> Float = { -1f }

    @Test fun restoredReplyIsSettledOnTheFirstFrame() {
        replyKey.value = "restored"
        render(motion = true)
        assertEquals(1f, progress(), 0f)
    }

    @Test fun reducedMotionShowsANewReplyAtOnce() {
        render(motion = false)
        select("chosen")
        assertEquals(1f, progress(), 0f)
    }

    @Test fun replyChosenWhileOpenRisesIntoPlaceLight() {
        render(motion = true)
        select("chosen")
        assertTrue("start progress ${progress()}", progress() in 0f..0.2f)
        advanceFrames(COMPOSER_ACCESSORY_ENTRANCE_MILLIS / 2L)
        val mid = progress()
        assertTrue("mid progress $mid", mid in 0.2f..0.95f)
        capture("composer_reply_entrance_mid_light")
        advanceFrames(SETTLE_MILLIS)
        assertEquals(1f, progress(), 0f)
        capture("composer_reply_entrance_settled_light")
    }

    @Test fun replyChosenWhileOpenRisesIntoPlaceDark() {
        render(motion = true, dark = true)
        select("chosen")
        advanceFrames(COMPOSER_ACCESSORY_ENTRANCE_MILLIS / 2L)
        capture("composer_reply_entrance_mid_dark")
        advanceFrames(SETTLE_MILLIS)
        capture("composer_reply_entrance_settled_dark")
    }

    private fun render(
        motion: Boolean,
        dark: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val gate = rememberComposerAccessoryEntranceGate()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        replyKey.value?.let { key ->
                            val entrance = rememberComposerAccessoryEntrance(key, gate, motion)
                            progress = entrance
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier =
                                    Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .composerAccessoryEntrance(entrance),
                            ) {
                                Text("Replying to $key", modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
    }

    private fun select(key: String) {
        composeRule.runOnIdle { replyKey.value = key }
        composeRule.waitForIdle()
        // One frame composes the new reply; the entrance clock starts on the following frame.
        advanceFrames(FRAME_MILLIS)
        composeRule.onNodeWithText("Replying to $key").assertExists()
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
