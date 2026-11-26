package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A round press highlight must not trim the tap area: a tap in a retry control's corner, inside its square box but
 * outside the drawn circle, still retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class VideoRetryCornerTapTest {
    @get:Rule val composeRule = createComposeRule()

    private val retryLabel =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.video_failed)

    /** A tap 20 dp diagonally from the centre of the viewer's 48 dp Retry button reaches it. */
    @Test
    fun viewerRetryTakesACornerTap() {
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                VideoViewerUnavailable(
                    cacheInvalidating = false,
                    playbackInvalidated = true,
                    loadFailed = false,
                    onPlaybackRetry = { retries++ },
                    onLoadRetry = {},
                )
            }
        }
        tapCorner(insetDp = 4f)
        assertEquals(1, retries)
    }

    /**
     * A mouse click in the corner of the disc's 28 dp retry glyph reaches it. Touch hit-testing widens any target
     * under 48 dp, which would hide a clipped corner, so this uses a pointer that gets no widening.
     */
    @Test
    fun discRetryGlyphTakesACornerClick() {
        var retries = 0
        composeRule.setContent { WhiteNoiseTheme { VideoRetryGlyph(onRetry = { retries++ }) } }
        val inset = cornerInset(insetDp = 2f)
        composeRule.onNodeWithContentDescription(retryLabel).performMouseInput { click(Offset(inset, inset)) }
        composeRule.waitForIdle()
        assertEquals(1, retries)
    }

    /** Taps [insetDp] in from the top-left corner of the retry control, which lies outside its inscribed circle. */
    private fun tapCorner(insetDp: Float) {
        val inset = cornerInset(insetDp)
        composeRule.onNodeWithContentDescription(retryLabel).performTouchInput { click(Offset(inset, inset)) }
        composeRule.waitForIdle()
    }

    /** [insetDp] in pixels at the test's density. */
    private fun cornerInset(insetDp: Float): Float = with(composeRule.density) { insetDp.dp.toPx() }
}
