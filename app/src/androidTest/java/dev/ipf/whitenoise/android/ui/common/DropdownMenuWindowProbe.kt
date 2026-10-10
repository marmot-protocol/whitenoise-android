package dev.ipf.whitenoise.android.ui.common

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Reads actual exported nodes and screen pixels, independent of Compose node captures and clocks. */
internal object DropdownMenuWindowProbe {
    private const val WINDOW_TIMEOUT_MS = 10_000L
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    fun visibleText(label: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return null
    }

    fun waitForText(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        do {
            val node = visibleText(label)
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

    fun clickText(label: String) {
        assertTrue(
            "actual Android click rejected for $label",
            clickable(waitForText(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
    }

    fun assertFirstRowPainted(density: Float) {
        val bounds = Rect().also(clickable(waitForText("Choice 0"))::getBoundsInScreen)
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
        assertEquals("actual first action at $bounds must be painted cyan", Color.Cyan.toArgb(), pixel)
    }
}
