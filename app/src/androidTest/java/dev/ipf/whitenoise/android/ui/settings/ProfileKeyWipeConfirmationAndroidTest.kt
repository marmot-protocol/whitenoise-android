package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Real platform dialog coverage with local counters only; never starts account state or wipes data. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ProfileKeyWipeConfirmationAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightKeywordGateAndSeparateActions() = exerciseDialog(largeRtl = false)

    @Test
    fun amoled200PercentRtlKeywordGateAndSeparateActions() = exerciseDialog(largeRtl = true)

    private fun exerciseDialog(largeRtl: Boolean) {
        val input = mutableStateOf("")
        var confirmed = 0
        var dismissed = 0
        val context = composeRule.activity
        val cancel = context.getString(R.string.cancel)
        val keyword = context.getString(R.string.sign_out_and_wipe_confirm_keyword)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    ProfileKeyWipeConfirmationDialog(
                        input = input.value,
                        onInputChange = { input.value = it },
                        onConfirm = { confirmed++ },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        val confirm = composeRule.onNodeWithTag("profile_keys.wipe_confirm")
        val field = composeRule.onNodeWithText(context.getString(R.string.sign_out_and_wipe_confirm_field_label))
        val instruction =
            composeRule.onNodeWithText(context.getString(R.string.sign_out_and_wipe_confirm_instruction, keyword))
        confirm.assertIsNotEnabled()
        instruction.performScrollTo().assertIsDisplayed()
        assertInVisibleWindow(instruction)
        field.performScrollTo().assertIsDisplayed()
        assertInVisibleWindow(field)
        assertActionsInWindow(cancel)
        saveScreenshot(if (largeRtl) "amoled_200_percent_rtl_empty" else "light_normal_empty")
        field.performClick()
        awaitKeyboard()
        field.performScrollTo()
        field.performTextInput(keyword + " wrong")
        confirm.assertIsNotEnabled()
        assertActionsInWindow(cancel)
        tapOnScreen(composeRule.onNodeWithText(cancel))
        composeRule.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, confirmed)
        }
        // Inert callbacks deliberately leave the same fixture open for the independent confirm action.
        field.performScrollTo().performClick()
        awaitKeyboard()
        field.performTextClearance()
        field.performTextInput("  " + keyword.lowercase(Locale.ROOT) + "  ")
        confirm.assertIsEnabled()
        field.performScrollTo()
        assertInVisibleWindow(field)
        assertActionsInWindow(cancel)
        saveScreenshot(if (largeRtl) "amoled_200_percent_rtl_enabled" else "light_normal_enabled")
        tapOnScreen(confirm)
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    private fun dialogWindow() =
        WindowInspector.getGlobalWindowViews().single { view ->
            view !== composeRule.activity.window.decorView && view.isShown && view.hasWindowFocus()
        }

    /** Require the actual IME and unchanged dialog/inset geometry throughout its settling interval. */
    private fun awaitKeyboard() {
        awaitSettledGeometry(requireIme = true)
    }

    private fun awaitSettledGeometry(requireIme: Boolean = false) {
        var previous = ""
        var unchangedSince = SystemClock.uptimeMillis()
        composeRule.waitUntil(10_000) {
            composeRule.runOnUiThread {
                val window = dialogWindow()
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(window))
                val geometry = windowGeometry()
                if (geometry != previous) {
                    previous = geometry
                    unchangedSince = SystemClock.uptimeMillis()
                }
                (!requireIme || insets.isVisible(WindowInsetsCompat.Type.ime())) &&
                    SystemClock.uptimeMillis() - unchangedSince >= 500
            }
        }
        composeRule.waitForIdle()
    }

    private fun windowGeometry(): String {
        val window = dialogWindow()
        val screen = IntArray(2)
        val local = IntArray(2)
        window.getLocationOnScreen(screen)
        window.getLocationInWindow(local)
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(window))
        val visible = Rect()
        window.getWindowVisibleDisplayFrame(visible)
        val params = window.layoutParams as WindowManager.LayoutParams
        return "screen=${screen.toList()} window=${local.toList()} size=${window.width}x${window.height} " +
            "visible=$visible ime=${insets.getInsets(WindowInsetsCompat.Type.ime())} " +
            "bars=${insets.getInsets(WindowInsetsCompat.Type.systemBars())} " +
            "softInputMode=${params.softInputMode} flags=${params.flags}"
    }

    /** Semantics clicks bypass hit testing; inject through the platform at the verified screen center. */
    private fun tapOnScreen(node: SemanticsNodeInteraction) {
        awaitSettledGeometry(requireIme = true)
        assertInVisibleWindow(node)
        val bounds = screenBounds(node)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event =
                MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Physical touch injection rejected", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        composeRule.waitForIdle()
    }

    private fun screenBounds(node: SemanticsNodeInteraction): RectF {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        return composeRule.runOnUiThread {
            val window = dialogWindow()
            val screen = IntArray(2)
            val local = IntArray(2)
            window.getLocationOnScreen(screen)
            window.getLocationInWindow(local)
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom).apply {
                offset((screen[0] - local[0]).toFloat(), (screen[1] - local[1]).toFloat())
            }
        }
    }

    private fun assertActionsInWindow(cancel: String) {
        assertInVisibleWindow(composeRule.onNodeWithTag("profile_keys.wipe_confirm"))
        assertInVisibleWindow(composeRule.onNodeWithText(cancel))
    }

    /** Compare full semantics bounds with screen coordinates, excluding system bars and the real IME. */
    private fun assertInVisibleWindow(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val fullBounds = node.getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        assertTrue(
            "Node is clipped: $bounds",
            bounds.width + 1 >= (fullBounds.right - fullBounds.left).value * density,
        )
        assertTrue(
            "Node is clipped: $bounds",
            bounds.height + 1 >= (fullBounds.bottom - fullBounds.top).value * density,
        )
        val screen = screenBounds(node)
        composeRule.runOnUiThread {
            val window = dialogWindow()
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(window))
            val metrics =
                window.context
                    .getSystemService(WindowManager::class.java)
                    .currentWindowMetrics.bounds
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val visible =
                Rect(metrics).apply {
                    left += bars.left
                    top += bars.top
                    right -= bars.right
                    bottom -= maxOf(bars.bottom, ime.bottom)
                }
            assertTrue(
                "Node outside unobscured screen: $screen available=$visible ${windowGeometry()}",
                screen.left >= visible.left - 1 &&
                    screen.top >= visible.top - 1 &&
                    screen.right <= visible.right + 1 &&
                    screen.bottom <= visible.bottom + 1,
            )
        }
    }

    /** AGP pulls additionalTestOutputDir into the workflow's instrumented reports artifact. */
    private fun saveScreenshot(name: String) {
        awaitSettledGeometry()
        assertActionsInWindow(composeRule.activity.getString(R.string.cancel))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = output ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).path
        val directory = File(root, "profile-key-wipe-confirmation")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap =
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Actual window screenshot unavailable" }
        try {
            val geometry = composeRule.runOnUiThread { windowGeometry() }
            val nodes =
                listOf(
                    "field" to
                        composeRule.onNodeWithText(
                            composeRule.activity.getString(R.string.sign_out_and_wipe_confirm_field_label),
                        ),
                    "confirm" to composeRule.onNodeWithTag("profile_keys.wipe_confirm"),
                    "cancel" to composeRule.onNodeWithText(composeRule.activity.getString(R.string.cancel)),
                ).joinToString("\n") { (label, node) ->
                    val semantics = node.fetchSemanticsNode()
                    "$label root=${semantics.boundsInRoot} window=${semantics.boundsInWindow} screen=${screenBounds(node)}"
                }
            File(directory, "$name-geometry.txt").writeText(
                "screenshot=${bitmap.width}x${bitmap.height} $geometry\n$nodes",
            )
            File(directory, "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
