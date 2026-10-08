package dev.ipf.whitenoise.android.ui.common

import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real IME, viewport pixels and pinned actions, without an account or network operation. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ScrollEdgeFadeImeAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    @Suppress("DEPRECATION")
    fun configureWindow() {
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test
    fun activityFormRemovesMaskForRealKeyboardAndRestoresItAfterHide() = exerciseMask(modal = false)

    @Test
    fun sheetFormUsesItsOwnKeyboardWindowWithoutMovingTheFooterIntoIme() = exerciseMask(modal = true)

    private fun exerciseMask(modal: Boolean) {
        lateinit var scroll: ScrollState
        var submissions = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                scroll = rememberScrollState()
                if (modal) {
                    WhiteNoiseModalBottomSheet(onDismissRequest = {}) {
                        Form(scroll, Modifier.heightIn(max = 500.dp)) { submissions++ }
                    }
                } else {
                    Form(scroll, Modifier.fillMaxSize()) { submissions++ }
                }
            }
        }
        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeGeometry() != null }
        composeRule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue / 2) } }
        composeRule.onNodeWithTag(FIELD).assertIsFocused()
        assertFooterAboveIme()
        val position = composeRule.runOnIdle { scroll.value }
        assertTrue("viewport must overflow in both directions", scroll.canScrollBackward && scroll.canScrollForward)
        val keyboardEdges = edgeLevels()
        assertTrue("keyboard viewport must remain unmasked: $keyboardEdges", keyboardEdges.all { it > 0.95f })

        composeRule.runOnUiThread {
            val window = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            ViewCompat.getWindowInsetsController(window)?.hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeGeometry() == null }
        composeRule.waitForIdle()
        assertEquals(
            "keyboard transition must preserve scroll ownership",
            position,
            composeRule.runOnIdle { scroll.value },
        )
        val restored = edgeLevels()
        assertTrue("both continuation cues must return: $restored", restored.first() < 0.5f && restored.last() < 0.5f)
        assertTrue("the viewport middle must stay readable", restored[1] > 0.95f)
        composeRule.onNodeWithTag(FOOTER).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, submissions) }
    }

    @Composable
    private fun Form(
        scroll: ScrollState,
        modifier: Modifier,
        onSubmit: () -> Unit,
    ) {
        Column(modifier.fillMaxWidth().imePadding().background(Color.Black)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().testTag(VIEWPORT).fadingVerticalScroll(scroll),
            ) {
                WhiteNoiseTextField(state = rememberTextFieldState(), modifier = Modifier.fillMaxWidth().testTag(FIELD))
                repeat(40) { Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Green)) }
            }
            Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().testTag(FOOTER)) { Text("Save") }
        }
    }

    /** Solid green rows make the fade observable independently of text metrics and theme. */
    private fun edgeLevels(): List<Float> {
        val pixels = composeRule.onNodeWithTag(VIEWPORT).captureToImage().toPixelMap()
        val x = pixels.width / 2
        return listOf(2, pixels.height / 2, pixels.height - 3).map { pixels[x, it].green }
    }

    private fun imeGeometry(): Pair<Int, Int>? =
        composeRule.runOnUiThread {
            val window =
                WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                    ?: return@runOnUiThread null
            val insets = ViewCompat.getRootWindowInsets(window) ?: return@runOnUiThread null
            val height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (insets.isVisible(WindowInsetsCompat.Type.ime()) && height > 0) window.height to height else null
        }

    private fun assertFooterAboveIme() {
        composeRule.onNodeWithTag(FOOTER).assertIsDisplayed()
        val (windowHeight, imeHeight) = requireNotNull(imeGeometry())
        val bounds = composeRule.onNodeWithTag(FOOTER).fetchSemanticsNode().boundsInWindow
        assertTrue("footer overlaps real IME: $bounds", bounds.bottom <= windowHeight - imeHeight + 2)
    }

    private companion object {
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val FIELD = "scroll.ime.field"
        const val VIEWPORT = "scroll.ime.viewport"
        const val FOOTER = "scroll.ime.footer"
    }
}
