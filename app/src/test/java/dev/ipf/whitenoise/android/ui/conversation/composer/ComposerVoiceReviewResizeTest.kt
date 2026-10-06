package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A manually collapsed composer uses the real voice review's compact row without clipping actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerVoiceReviewResizeTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var review: VoiceRecordingReview
    private var played = 0
    private var sent = 0
    private val playback =
        object : VoiceReviewPlayback {
            override suspend fun play(clip: VoiceReviewClip): Boolean {
                played++
                return true
            }

            override fun pause(key: String) = Unit

            override fun stop(key: String) = Unit

            override fun isPlaying(key: String) = false
        }

    @Test
    fun collapsedEmptyComposerKeepsAllVoiceReviewActionsUsable() {
        render()
        rule.waitForIdle()
        rule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            // Land between the empty automatic row and the manual single-line floor.
            swipe(center, center - Offset(0f, 30f), durationMillis = 320)
        }
        rule.waitForIdle()
        assertEquals(96f, rule.onNodeWithTag("voice-review-composer").fetchSemanticsNode().boundsInRoot.height, 1f)
        try {
            offerTake(review)
            val panel = rule.onNodeWithTag("conversation.voice.review").fetchSemanticsNode().boundsInRoot
            assertEquals("review must exercise the compact layout below 96dp", 84f, panel.height, 1f)
            val actionBounds =
                listOf(R.string.voice_message_play, R.string.discard, R.string.voice_record_again, R.string.send).map {
                    val bounds =
                        rule
                            .onNodeWithContentDescription(context.getString(it))
                            .assertIsDisplayed()
                            .fetchSemanticsNode()
                            .boundsInRoot
                    assertEquals(48f, bounds.height, 1f)
                    assertEquals(48f, bounds.width, 1f)
                    assertTrue(bounds.top >= panel.top && bounds.bottom <= panel.bottom)
                    assertTrue(bounds.left >= panel.left && bounds.right <= panel.right)
                    bounds
                }
            actionBounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
            assertEquals("opening review never sends", 0, sent)
            click(R.string.voice_message_play)
            assertEquals(1, played)
            click(R.string.discard)
            assertNull(review.clip)
            offerTake(review)
            click(R.string.voice_record_again)
            assertNull(review.clip)
            assertEquals("discard and record-again never send", 0, sent)
            offerTake(review)
            click(R.string.send)
            assertEquals(1, sent)
            assertNull(review.clip)
        } finally {
            rule.runOnIdle { review.release() }
        }
    }

    /** Uses the actual composer, captured voice owner and adaptive review controls. */
    private fun render() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val current =
                remember {
                    VoiceRecordingReview(
                        scope = scope,
                        ownerIsCurrent = { true },
                        send = { _, duration, owns, accepted ->
                            assertEquals(2_000L, duration)
                            assertTrue(owns())
                            sent++
                            accepted(true)
                        },
                        playback = playback,
                    ).also { review = it }
                }
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).height(600.dp)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> error("voice review must use its captured clip sender") },
                            onPickFromGallery = {},
                            onPickDocument = {},
                            voiceReview = current,
                            modifier = Modifier.testTag("voice-review-composer"),
                        )
                    }
                }
            }
        }
    }

    /** Opens native review without a microphone, player or network operation. */
    private fun offerTake(review: VoiceRecordingReview) {
        val file = files.newFile().apply { writeBytes(byteArrayOf(1)) }
        rule.runOnIdle { assertTrue(review.offer(file, 2_000L)) }
        rule.waitForIdle()
    }

    private fun click(label: Int) {
        rule.onNodeWithContentDescription(context.getString(label)).performClick()
        rule.waitForIdle()
    }
}
