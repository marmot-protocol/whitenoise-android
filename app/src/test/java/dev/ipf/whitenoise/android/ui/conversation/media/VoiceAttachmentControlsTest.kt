package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class VoiceAttachmentControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The speed pill names the current multiplier and advances it on tap while the clip is active. */
    @Test
    fun speedPillCyclesSpeedWhileClipIsActive() {
        var cycles = 0
        render(isPlaying = true, playbackSpeed = 1.5f, onCycleSpeed = { cycles++ })

        composeRule.onNodeWithContentDescription("Playback speed 1.5×").assertIsDisplayed().performClick()

        assertEquals(1, cycles)
    }

    /** A clip that is neither playing nor paused shows no speed action at all. */
    @Test
    fun speedPillIsHiddenWithoutActivePlayback() {
        render(isPlaying = false, playbackSpeed = null)

        composeRule.onAllNodesWithContentDescription("Playback speed", substring = true).assertCountEquals(0)
    }

    /** Tapping the track seeks to the tapped fraction of the clip. */
    @Test
    fun trackTapSeeksToTheTappedFraction() {
        val fractions = mutableListOf<Float>()
        render(isPlaying = true, playbackSpeed = 1f, onSeek = { fractions += it })

        composeRule.onNodeWithTag(VOICE_SEEK_TRACK_TAG).performTouchInput {
            down(Offset(width * 0.75f, centerY))
            up()
        }

        assertTrue("expected a seek near 0.75, got $fractions", fractions.isNotEmpty())
        assertEquals(0.75f, fractions.last(), 0.05f)
    }

    /** A horizontal drag scrubs continuously and finishes at the release fraction. */
    @Test
    fun trackDragScrubsToTheReleasePoint() {
        val fractions = mutableListOf<Float>()
        render(isPlaying = true, playbackSpeed = 1f, onSeek = { fractions += it })

        composeRule.onNodeWithTag(VOICE_SEEK_TRACK_TAG).performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
            moveTo(Offset(width * 0.9f, centerY))
            up()
        }

        assertTrue("expected several scrub samples, got $fractions", fractions.size >= 3)
        assertTrue(fractions.zipWithNext().all { (previous, next) -> next >= previous })
        assertEquals(0.9f, fractions.last(), 0.05f)
    }

    /** The label snaps a raw multiplier onto the three presets the runtime cycles through. */
    @Test
    fun voiceSpeedLabelSnapsToPresets() {
        assertEquals("1×", voiceSpeedLabel(1f))
        assertEquals("1×", voiceSpeedLabel(1.2f))
        assertEquals("1.5×", voiceSpeedLabel(1.5f))
        assertEquals("2×", voiceSpeedLabel(2f))
    }

    /** Composes the production voice row with the given playback fixture. */
    private fun render(
        isPlaying: Boolean,
        playbackSpeed: Float?,
        onSeek: ((Float) -> Unit)? = null,
        onCycleSpeed: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                VoiceAttachmentContent(
                    loading = false,
                    failed = false,
                    startDownload = true,
                    localFileAvailable = true,
                    isPlaying = isPlaying,
                    isPaused = false,
                    activePositionMs = 3_000,
                    activeDurationMs = 12_000,
                    totalDurationMs = 12_000,
                    progressFraction = 0.25f,
                    outgoing = false,
                    onLongPress = {},
                    onActionClick = {},
                    playbackSpeed = playbackSpeed,
                    onSeek = onSeek,
                    onCycleSpeed = onCycleSpeed,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
