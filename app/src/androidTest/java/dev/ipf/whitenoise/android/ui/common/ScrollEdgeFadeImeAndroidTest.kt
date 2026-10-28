package dev.ipf.whitenoise.android.ui.common

import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real IME, viewport pixels and pinned actions, without an account or network operation. */
@PullRequestDeviceSmoke
@SdkSuppress(minSdkVersion = 30)
@RunWith(AndroidJUnit4::class)
class ScrollEdgeFadeImeAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var previousAnimationScale: String? = null
    private var imeAnimationRunning = false

    /** Enables real emulator motion for frame assertions and configures resizing around the keyboard. */
    @Before
    @Suppress("DEPRECATION")
    fun configureWindow() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val resolver = composeRule.activity.contentResolver
        previousAnimationScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        check(Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** Keep the runner's animation policy unchanged for subsequent emulator cases, even after an assertion failure. */
    @After
    fun restoreAnimationPolicy() {
        composeRule.mainClock.autoAdvance = true
        try {
            Settings.Global.putString(
                composeRule.activity.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                previousAnimationScale,
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun activityFormRemovesMaskForRealKeyboardAndRestoresItAfterHide() = exerciseMask(modal = false)

    @Test
    fun sheetFormUsesItsOwnKeyboardWindowWithoutMovingTheFooterIntoIme() = exerciseMask(modal = true)

    /** Checks activity/modal IME removal and first, middle and settled fade restoration with live actions. */
    @OptIn(ExperimentalMaterial3Api::class)
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
                    WhiteNoiseScaffold { padding ->
                        Form(scroll, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                            submissions++
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeGeometry() != null }
        composeRule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue / 2) } }
        composeRule.onNodeWithTag(FIELD).assertIsFocused()
        waitForFooterAboveIme()
        val position = composeRule.runOnIdle { scroll.value }
        assertTrue("viewport must overflow in both directions", scroll.canScrollBackward && scroll.canScrollForward)
        val keyboardEdges = edgeLevels()
        assertTrue("keyboard viewport must remain unmasked: $keyboardEdges", keyboardEdges.all { it > 0.95f })

        hideKeyboardAndCheckRetainedMask()
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

    /** Samples the retained mask on the first observed restored layout and during its old transition window. */
    private fun hideKeyboardAndCheckRetainedMask() {
        composeRule.mainClock.autoAdvance = false
        val window = composeRule.runOnUiThread {
            WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }.also {
                observeImeAnimation(it)
                ViewCompat.getWindowInsetsController(it)?.hide(WindowInsetsCompat.Type.ime())
            }
        }
        try {
            composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { hiddenImeSettled(window) }
        } finally {
            composeRule.runOnUiThread { ViewCompat.setWindowInsetsAnimationCallback(window, null) }
        }
        // The first Compose layout after the real IME closes must reuse the retained mask.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val firstRestored = edgeLevels()
        assertTrue(
            "the first restored frame must not restart either fade: $firstRestored",
            firstRestored.first() < 0.5f && firstRestored.last() < 0.5f,
        )
        composeRule.mainClock.advanceTimeBy(60)
        val middleRestored = edgeLevels()
        assertTrue(
            "the mid-transition frame retains both cues: $middleRestored",
            middleRestored.first() < 0.5f && middleRestored.last() < 0.5f,
        )
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    /** Target visibility changes before animated insets settle; keep the Compose clock paused through native motion. */
    private fun observeImeAnimation(window: View) {
        ViewCompat.setWindowInsetsAnimationCallback(
            window,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimationRunning = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat = insets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimationRunning = false
                }
            },
        )
    }

    /** The actual focused Activity or Dialog window must finish hiding, independently of Compose fade progress. */
    private fun hiddenImeSettled(window: View): Boolean =
        composeRule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(window) ?: return@runOnUiThread false
            !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0 &&
                !imeAnimationRunning
        }

    @Composable
    private fun Form(
        scroll: ScrollState,
        modifier: Modifier,
        onSubmit: () -> Unit,
    ) {
        Column(modifier.fillMaxWidth().imePadding().background(Color.Black)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(VIEWPORT)
                    .fadingVerticalScroll(scroll),
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
            if (!insets.isVisible(WindowInsetsCompat.Type.ime()) || height <= 0) return@runOnUiThread null
            // adjustResize can shrink an Activity's root; subtracting its IME again double-counts it.
            val manager = requireNotNull(window.context.getSystemService(WindowManager::class.java))
            val frame = manager.currentWindowMetrics.bounds
            frame.height() to height
        }

    /** Native IME visibility can precede the Compose layout receiving its final inset. */
    private fun waitForFooterAboveIme() {
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) {
            val (windowHeight, imeHeight) = imeGeometry() ?: return@waitUntil false
            val bounds = composeRule.onNodeWithTag(FOOTER).fetchSemanticsNode().boundsInWindow
            bounds.bottom <= windowHeight - imeHeight + 2
        }
        assertFooterAboveIme()
    }

    private fun assertFooterAboveIme() {
        composeRule.onNodeWithTag(FOOTER).assertIsDisplayed()
        val (windowHeight, imeHeight) = requireNotNull(imeGeometry())
        val bounds = composeRule.onNodeWithTag(FOOTER).fetchSemanticsNode().boundsInWindow
        assertTrue(
            "footer overlaps real IME: footer=$bounds frame=$windowHeight ime=$imeHeight",
            bounds.bottom <= windowHeight - imeHeight + 2,
        )
    }

    private companion object {
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val FIELD = "scroll.ime.field"
        const val VIEWPORT = "scroll.ime.viewport"
        const val FOOTER = "scroll.ime.footer"
    }
}
