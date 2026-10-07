package dev.ipf.whitenoise.android.maestro

import android.app.UiAutomation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import java.io.File

/** Observe the real Android window without installing a Compose test clock alongside Maestro. */
internal suspend fun awaitMaestroFixtureWindow(directory: File) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val deadline = SystemClock.elapsedRealtime() + 30_000L
    try {
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.hasVisibleFixtureText("Help Improve White Noise") == true) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            } else if (
                root != null && root.packageName?.toString() == MaestroFixtureRunner.FIXTURE_PACKAGE &&
                root.hasVisibleFixtureText("Maestro group")
            ) {
                return
            }
            delay(100L)
        }
        error("Generated group did not appear in the active accessible app window")
    } finally {
        File(directory, "setup-tree.txt").writeText(accessibleFixtureWindow(automation))
    }
}

/** Require the exact synthetic label on a visible accessible node, never a native row alone. */
private fun AccessibilityNodeInfo.hasVisibleFixtureText(text: String): Boolean =
    findAccessibilityNodeInfosByText(text).any { it.isVisibleToUser && it.text?.toString() == text }

/** Capture bounded diagnostic nodes from this disposable emulator, including a wrong foreground window. */
private fun accessibleFixtureWindow(automation: UiAutomation): String =
    runCatching {
        val root = automation.rootInActiveWindow ?: return@runCatching "No active accessibility window"
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        buildString {
            var count = 0
            while (queue.isNotEmpty() && count++ < 256) {
                val node = queue.removeFirst()
                appendLine(
                    "${node.packageName} ${node.className} visible=${node.isVisibleToUser} " +
                        "id=${node.viewIdResourceName} text=${node.text} description=${node.contentDescription}",
                )
                repeat(minOf(node.childCount, 128)) { index -> node.getChild(index)?.let(queue::add) }
            }
        }
    }.getOrElse { "Accessibility diagnostic failed: $it" }
