package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Rendered AMOLED group edges: the outer outline stays white while the divider two rows share is dimmer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SettingsRowSeamRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The top edge of the first row paints the full outline, the pixel row it shares with the second is dimmed. */
    @Test
    fun amoledSeamBetweenRowsIsDimmerThanTheGroupOutline() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                SettingsGroup(modifier = Modifier.testTag("group")) {
                    row("first") { context ->
                        SettingsLink(context, "First", onClick = {}, modifier = Modifier.testTag("first"))
                    }
                    row("second") { context -> SettingsLink(context, "Second", onClick = {}) }
                }
            }
        }
        val group = composeRule.onNodeWithTag("group")
        val groupBounds = group.fetchSemanticsNode().boundsInRoot
        val firstBounds = composeRule.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot
        val pixels = group.captureToImage().toPixelMap()
        val x = pixels.width / 2
        val outlineY = (firstBounds.top - groupBounds.top).roundToInt()
        val seamY = (firstBounds.bottom - groupBounds.top).roundToInt() - 1
        val outline = pixels[x, outlineY]
        val seam = pixels[x, seamY]
        val fill = pixels[x, (outlineY + seamY) / 2]

        assertEquals(Color.White, outline)
        assertEquals(Color.Black, fill)
        assertTrue("seam $seam should be dimmer than the outline $outline", seam.red < outline.red)
        assertTrue("seam $seam should still be visible against the fill", seam.red > fill.red)
    }
}
