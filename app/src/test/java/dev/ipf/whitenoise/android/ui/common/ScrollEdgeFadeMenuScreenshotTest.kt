package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

/** Real Material popup content and its actual scroll state, not a simulated column. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class ScrollEdgeFadeMenuScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val rowCount = mutableIntStateOf(30)
    private val expanded = mutableStateOf(true)
    private val chosen = mutableIntStateOf(-1)

    /** An overflowing native menu fades below its viewport and keeps its reached top opaque. */
    @Test
    fun longMenuKeepsStartReadable() {
        render()
        val node = rule.onNodeWithTag(MENU)
        node.captureRoboImage("src/test/snapshots/scroll_edges_native_menu_start.png")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.Cyan, pixels[8, 14])
        assertTrue(pixels[8, pixels.height - 14] != Color.Cyan)
    }

    /** Touch scrolling activates both continuation edges without fading the parent surface chrome. */
    @Test
    fun longMenuTouchScroll() {
        render()
        rule.onNodeWithTag(MENU).performTouchInput {
            swipeUp(startY = height * 0.75f, endY = height * 0.4f, durationMillis = 1000)
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(MENU)
        node.captureRoboImage("src/test/snapshots/scroll_edges_native_menu_middle.png")
        val pixels = node.captureToImage().toPixelMap()
        assertTrue(pixels[8, 14] != Color.Cyan)
        assertTrue(pixels[8, pixels.height - 14] != Color.Cyan)
    }

    /** A short menu remains opaque through its last item and keeps its dismissal/action semantics. */
    @Test
    fun shortMenuHasNoMask() {
        rowCount.intValue = 2
        render()
        val node = rule.onNodeWithTag(MENU)
        node.captureRoboImage("src/test/snapshots/scroll_edges_native_menu_fitting.png")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.Cyan, pixels[8, 14])
        assertEquals(Color.Cyan, pixels[8, pixels.height - 14])
        rule.onNodeWithText("Choice 1").performClick()
        rule.waitForIdle()
        assertEquals(1, chosen.intValue)
        rule.onNodeWithTag(MENU).assertDoesNotExist()
    }

    private fun render() {
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    WhiteNoiseDropdownMenu(
                        expanded = expanded.value,
                        onDismissRequest = { expanded.value = false },
                        items =
                            List(rowCount.intValue) {
                                WhiteNoiseMenuItem(
                                    label = "Choice $it",
                                    onClick = { chosen.intValue = it },
                                    modifier = Modifier.background(Color.Cyan),
                                )
                            },
                        modifier = Modifier.testTag(MENU),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private companion object {
        const val MENU = "scroll-edge-native-menu"
    }
}
