package dev.ipf.whitenoise.android.ui.common

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Natural window frames and Android accessibility, without a Compose test clock or idling resource. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ClocklessDropdownMenuAndroidTest {
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test
    fun fittingAppMenuPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = true)

    @Test
    fun fittingMaterialControlPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = false)

    private fun exerciseMenu(appMenu: Boolean) {
        val expanded = mutableStateOf(false)
        val selected = mutableStateOf(-1)
        val density = renderMenu(appMenu, expanded, selected)
        clickText("Open menu")
        val first = clickable(waitForText("Choice 0"))
        val bounds = Rect().also(first::getBoundsInScreen)
        assertPainted(bounds, density)
        clickText("Choice 2")
        waitForText("Open menu")
        scenario.scenario.onActivity { assertEquals(2, selected.value) }
        assertTrue(
            "selection must dismiss the actual popup",
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Choice 2").orEmpty().isEmpty(),
        )
    }

    private fun renderMenu(
        appMenu: Boolean,
        expanded: MutableState<Boolean>,
        selected: MutableState<Int>,
    ): Float {
        var density = 0f
        scenario.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            density = activity.resources.displayMetrics.density
            activity.setContent {
                WhiteNoiseTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Button(onClick = { expanded.value = true }) { Text("Open menu") }
                        if (appMenu) {
                            WhiteNoiseDropdownMenu(
                                expanded = expanded.value,
                                onDismissRequest = { expanded.value = false },
                                items =
                                    List(3) { index ->
                                        WhiteNoiseMenuItem(
                                            "Choice $index",
                                            onClick = { selected.value = index },
                                            modifier = Modifier.background(Color.Cyan),
                                        )
                                    },
                            )
                        } else {
                            DropdownMenu(expanded.value, onDismissRequest = { expanded.value = false }) {
                                repeat(3) { index ->
                                    DropdownMenuItem(
                                        text = { Text("Choice $index") },
                                        onClick = {
                                            selected.value = index
                                            expanded.value = false
                                        },
                                        modifier = Modifier.background(Color.Cyan),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return density
    }

    private fun waitForText(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        do {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            if (node != null) return node
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Actual Android window did not expose $label")
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) current = checkNotNull(current.parent) { "No actual clickable ancestor" }
        return current
    }

    private fun clickText(label: String) {
        assertTrue(
            "actual Android click rejected for $label",
            clickable(waitForText(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
    }

    private fun assertPainted(bounds: Rect, density: Float) {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        var pixel = 0
        do {
            val image = checkNotNull(automation.takeScreenshot()) { "Actual window screenshot unavailable" }
            try {
                pixel = image.getPixel(bounds.left + (8 * density).toInt(), bounds.centerY())
                if (pixel == Color.Cyan.toArgb()) return
            } finally {
                image.recycle()
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("actual first action must be painted cyan", Color.Cyan.toArgb(), pixel)
    }

    private companion object {
        const val WINDOW_TIMEOUT_MS = 10_000L
    }
}
