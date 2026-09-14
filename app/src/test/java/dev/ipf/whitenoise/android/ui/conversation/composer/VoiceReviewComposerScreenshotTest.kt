package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Review geometry and real command callbacks, with verified 200% typography in the adaptive case. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class VoiceReviewComposerScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Voice review light preserves all four actions. */
    @Test
    fun voiceReviewLightPreservesAllFourActions() {
        val actions = mutableListOf<String>()
        render(actions = actions)
        capture("voice_review_light")
        listOf(
            R.string.voice_message_play,
            R.string.discard,
            R.string.voice_record_again,
            R.string.send,
        ).forEach { rule.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed().performClick() }
        assertEquals(listOf("play", "discard", "recordAgain", "send"), actions)
    }

    /** Voice review narrow large rtl amoled keeps playback and send readable. */
    @Test
    fun voiceReviewNarrowLargeRtlAmoledKeepsPlaybackAndSendReadable() {
        val actions = mutableListOf<String>()
        render(dark = true, fontScale = 2f, rtl = true, width = 240, actions = actions)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("0:07").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        // WhiteNoiseTheme scales typography itself; the Android density remains unchanged.
        assertEquals(
            28f,
            layouts
                .single()
                .layoutInput.style.fontSize.value,
            0.001f,
        )
        val durationLayout = layouts.single()
        assertEquals("duration must stay on one readable line", 1, durationLayout.lineCount)
        assertTrue(
            "duration overflow: size=${durationLayout.size}, constraints=${durationLayout.layoutInput.constraints}, " +
                "density=${durationLayout.layoutInput.density}, style=${durationLayout.layoutInput.style}, " +
                "paragraph=${durationLayout.multiParagraph.width}x${durationLayout.multiParagraph.height}",
            !durationLayout.hasVisualOverflow,
        )
        val root = rule.onNodeWithTag("voice-review-frame").fetchSemanticsNode().boundsInRoot
        listOf(R.string.voice_message_play, R.string.discard, R.string.voice_record_again, R.string.send).forEach {
            val bounds =
                rule
                    .onNodeWithContentDescription(context.getString(it))
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
            assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
            assertTrue(bounds.width >= 48f && bounds.height >= 48f)
            rule.onNodeWithContentDescription(context.getString(it)).performClick()
        }
        assertEquals(listOf("play", "discard", "recordAgain", "send"), actions)
        capture("voice_review_large_rtl_amoled")
    }

    /** A landscape/IME remainder keeps all four distinct 48dp targets without overlapping rows. */
    @Test
    fun compactHeightReviewKeepsFourSeparateActions() {
        render(width = 240, height = 64)
        val bounds =
            listOf(
                R.string.voice_message_play,
                R.string.discard,
                R.string.voice_record_again,
                R.string.send,
            ).map {
                rule
                    .onNodeWithContentDescription(context.getString(it))
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        assertTrue(bounds.all { it.height == 48f && it.width == 48f })
        capture("voice_review_compact_height")
    }

    /** Preparation disables replay, rerecord and duplicate sends while keeping explicit discard usable. */
    @Test
    fun pendingQueueKeepsReviewVisibleAndDiscardAvailable() {
        render(sending = true)
        listOf(R.string.voice_message_play, R.string.voice_record_again, R.string.send).forEach {
            rule.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed().assertIsNotEnabled()
        }
        rule.onNodeWithContentDescription(context.getString(R.string.discard)).assertIsDisplayed().assertIsEnabled()
    }

    /** Uses the real theme scale, not an ambient density that a popup or theme can replace. */
    private fun render(
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        width: Int = 360,
        actions: MutableList<String> = mutableListOf(),
        height: Int = 96,
        sending: Boolean = false,
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = fontScale) {
                    Surface(
                        Modifier.width(width.dp).height(height.dp).testTag("voice-review-frame"),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        VoiceReviewComposerContent(
                            durationMs = 10_000L,
                            positionMs = 3_000,
                            playing = false,
                            sending = sending,
                            waveform = floatArrayOf(0.2f, 0.5f, 0.9f, 0.6f, 0.3f, 0.7f, 0.4f),
                            onPlay = { actions += "play" },
                            onDiscard = { actions += "discard" },
                            onRecordAgain = { actions += "recordAgain" },
                            onSend = { actions += "send" },
                        )
                    }
                }
            }
        }
    }

    /** Records the surface whose action bounds and typography are asserted above. */
    private fun capture(name: String) {
        rule.onNodeWithTag("voice-review-frame").captureRoboImage("src/test/snapshots/$name.png")
    }
}
