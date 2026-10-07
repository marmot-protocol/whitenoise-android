package dev.ipf.whitenoise.android.maestro

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import java.io.File

/** Observe the real Android window without installing a Compose test clock alongside Maestro. */
internal suspend fun awaitMaestroFixtureWindow(
    directory: File,
    requireConsent: Boolean = false,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val deadline = SystemClock.elapsedRealtime() + 30_000L
    var consentCloseRequested = false
    try {
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (
                root?.packageName?.toString() == MaestroFixtureRunner.FIXTURE_PACKAGE &&
                root.hasVisibleFixtureText("Help Improve White Noise")
            ) {
                if (requireConsent) return
                if (!consentCloseRequested) consentCloseRequested = root.closeDefaultOffConsent()
            } else if (
                !requireConsent &&
                root != null &&
                root.packageName?.toString() == MaestroFixtureRunner.FIXTURE_PACKAGE &&
                root.hasVisibleFixtureText("Maestro group")
            ) {
                return
            }
            delay(100L)
        }
        error("Generated group did not appear in the active accessible app window")
    } finally {
        File(directory, "setup-tree.txt").writeText(
            "Consent close requested=$consentCloseRequested\n" + accessibleFixtureWindow(automation),
        )
    }
}

/** Traverse virtual Compose nodes directly: provider text-search need not expose the same window tree. */
private fun AccessibilityNodeInfo.hasVisibleFixtureText(text: String): Boolean {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)
    var count = 0
    while (queue.isNotEmpty() && count++ < 256) {
        val node = queue.removeFirst()
        if (node.isVisibleToUser && node.text?.toString() == text) return true
        repeat(minOf(node.childCount, 128)) { index -> node.getChild(index)?.let(queue::add) }
    }
    return false
}

/** Use the sheet's explicit close action; injected Back may be consumed by the host Activity. */
private fun AccessibilityNodeInfo.closeDefaultOffConsent(): Boolean {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)
    var count = 0
    while (queue.isNotEmpty() && count++ < 256) {
        val node = queue.removeFirst()
        if (
            node.isVisibleToUser &&
            node.isEnabled &&
            node.isClickable
        ) {
            if (node.contentDescription?.toString() in listOf("Close", "Close sheet")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        repeat(minOf(node.childCount, 128)) { index -> node.getChild(index)?.let(queue::add) }
    }
    return false
}

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
                        "enabled=${node.isEnabled} clickable=${node.isClickable} actions=${node.actionList} " +
                        "id=${node.viewIdResourceName} text=${node.text} description=${node.contentDescription}",
                )
                repeat(minOf(node.childCount, 128)) { index -> node.getChild(index)?.let(queue::add) }
            }
        }
    }.getOrElse { "Accessibility diagnostic failed: $it" }
