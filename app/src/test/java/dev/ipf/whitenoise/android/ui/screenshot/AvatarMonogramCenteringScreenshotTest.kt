package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max

/** Measures actual fallback glyph ink, rather than the centered text layout box. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AvatarMonogramCenteringScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    /** Fallback monogram glyph ink is centered with the system font. */
    @Test
    fun system() = render(AppFont.System)

    /** Fallback monogram glyph ink is centered with the Manrope font. */
    @Test
    fun manrope() = render(AppFont.Manrope)

    /** Fallback monogram glyph ink is centered with the Outfit font. */
    @Test
    fun outfit() = render(AppFont.Outfit)

    /** Fallback monogram glyph ink is centered with the Urbanist font. */
    @Test
    fun urbanist() = render(AppFont.Urbanist)

    /** Fallback monogram glyph ink is centered with the Figtree font. */
    @Test
    fun figtree() = render(AppFont.Figtree)

    /**
     * Renders S, H and O monograms at three diameters and two font scales in [font], asserting ink centering
     * and snapshotting each scale.
     */
    private fun render(font: AppFont) {
        val scale = mutableFloatStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, appFont = font, fontScale = scale.floatValue) {
                    Column(Modifier.background(Color.Black).testTag("monograms")) {
                        for (diameter in listOf(24, 40, 120)) {
                            Row {
                                for (glyph in listOf("S", "H", "O")) {
                                    Box(Modifier.size(diameter.dp).testTag("$diameter-$glyph")) {
                                        Avatar(title = glyph, seed = "synthetic-$glyph", size = diameter.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val failures = mutableListOf<String>()
        for (value in listOf(1f, 1.5f)) {
            rule.runOnIdle { scale.floatValue = value }
            rule.waitForIdle()
            for (diameter in listOf(24, 40, 120)) {
                for (glyph in listOf("S", "H", "O")) failures += inkCenterErrors(diameter, glyph)
            }
            val scaleName = if (value == 1f) "normal" else "large"
            rule.onNodeWithTag("monograms").captureRoboImage(
                "src/test/snapshots/avatar_monogram_${font.name.lowercase()}_$scaleName.png",
            )
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Returns failures when a glyph has no white ink or its ink box is off the avatar center by more than
     * 2.5 percent of the diameter.
     */
    private fun inkCenterErrors(
        diameter: Int,
        glyph: String,
    ): List<String> {
        val pixels = rule.onNodeWithTag("$diameter-$glyph").captureToImage().toPixelMap()
        // This fixture has opaque flat palette pixels and a white glyph. The safe circle sample
        // is above all glyph ink; normalized majority coverage avoids palette-dependent white thresholds.
        val background = pixels[pixels.width / 2, pixels.height / 8]
        val ink = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (whiteCoverage(color, background) > 0.5f) ink += x to y
            }
        }
        if (ink.isEmpty()) return listOf("$diameter-$glyph has no white glyph ink")
        val centerX = (ink.minOf { it.first } + ink.maxOf { it.first } + 1) / 2f
        val centerY = (ink.minOf { it.second } + ink.maxOf { it.second } + 1) / 2f
        val tolerance = max(1f, diameter * 0.025f)
        return buildList {
            if (abs(centerX - pixels.width / 2f) > tolerance) add("$diameter-$glyph horizontal ink center=$centerX")
            if (abs(centerY - pixels.height / 2f) > tolerance) add("$diameter-$glyph vertical ink center=$centerY")
        }
    }

    /** Projects [color] onto the background-to-white axis, returning 0 at the background and 1 at pure white. */
    private fun whiteCoverage(
        color: Color,
        background: Color,
    ): Float {
        val red = 1f - background.red
        val green = 1f - background.green
        val blue = 1f - background.blue
        val projected =
            (color.red - background.red) * red + (color.green - background.green) * green +
                (color.blue - background.blue) * blue
        return projected / (red * red + green * green + blue * blue)
    }
}
