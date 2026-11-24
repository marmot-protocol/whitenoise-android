package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared dialog surface stays off the window edges and inside Material's widest dialog, whatever its content. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DialogWidthBoundsTest {
    @get:Rule val rule = createComposeRule()

    /** Content that fills its width stops 24 dp short of each edge of a 360 dp phone. */
    @Test
    fun fillingContentKeepsSideMarginsOnAPhone() = assertSurfaceWidth(fill = true, expected = 312.dp)

    /** On a 320 dp window the side margins win over Material's 280 dp minimum width. */
    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun marginsWinOverTheMinimumOnTheNarrowestWindow() = assertSurfaceWidth(fill = true, expected = 272.dp)

    /** A wide window still caps a filling dialog at Material's 560 dp maximum. */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun wideWindowsCapAtTheMaximum() = assertSurfaceWidth(fill = true, expected = 560.dp)

    /** A short dialog keeps Material's natural 280 dp width instead of stretching to the bound. */
    @Test
    fun shortContentKeepsTheNaturalWidth() = assertSurfaceWidth(fill = false, expected = 280.dp)

    /** Renders one shared dialog and compares its surface width with [expected]. */
    private fun assertSurfaceWidth(
        fill: Boolean,
        expected: Dp,
    ) {
        rule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseAlertDialog(
                    onDismissRequest = {},
                    modifier = Modifier.testTag(DIALOG_TAG),
                    title = { Text("Title") },
                    text = { if (fill) Box(Modifier.fillMaxWidth().height(24.dp)) else Text("Short") },
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                )
            }
        }
        val bounds = rule.onNodeWithTag(DIALOG_TAG).getBoundsInRoot()
        assertEquals(expected.value, (bounds.right - bounds.left).value, WIDTH_TOLERANCE_DP)
    }

    private companion object {
        const val DIALOG_TAG = "dialog-width-bounds"
        const val WIDTH_TOLERANCE_DP = 0.5f
    }
}
