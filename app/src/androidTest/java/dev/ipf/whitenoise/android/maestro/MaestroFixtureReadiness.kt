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
    requireNotificationPermission: Boolean = false,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val deadline = SystemClock.elapsedRealtime() + 30_000L
    var consentCloseRequested = false
    var nextConsentClose = 0L
    var notificationDenyRequested = false
    try {
        while (SystemClock.elapsedRealtime() < deadline) {
            val window = automation.rootInActiveWindow
            val notificationPrompt = window?.isMaestroNotificationPrompt() == true
            check(!(requireConsent && notificationPrompt)) { "Notification request preceded the sharing choice" }
            val root =
                window?.takeIf {
                    it.packageName?.toString() == MaestroFixtureRunner.FIXTURE_PACKAGE
                }
            val consentVisible = root?.hasVisibleFixtureText("Help Improve White Noise") == true
            val expectedWindow =
                when {
                    requireConsent -> consentVisible
                    requireNotificationPermission -> notificationPrompt
                    else -> !consentVisible && root?.hasVisibleFixtureText("Maestro group") == true
                }
            if (expectedWindow) return
            if (notificationPrompt) {
                notificationDenyRequested = window?.denyMaestroNotificationPrompt() == true || notificationDenyRequested
            }
            if (consentVisible && SystemClock.elapsedRealtime() >= nextConsentClose) {
                // An accepted accessibility action can precede the sheet's settled native receipt.
                consentCloseRequested = root?.closeDefaultOffConsent() == true || consentCloseRequested
                nextConsentClose = SystemClock.elapsedRealtime() + 500L
            }
            delay(100L)
        }
        error("Generated group did not appear in the active accessible app window")
    } finally {
        File(directory, "setup-tree.txt").writeText(
            "Consent close requested=$consentCloseRequested; notification deny requested=$notificationDenyRequested\n" +
                accessibleFixtureWindow(automation),
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

/** Match only this isolated app's notification prompt; never dismiss another application's permission request. */
private fun AccessibilityNodeInfo.isMaestroNotificationPrompt(): Boolean =
    packageName?.toString() in listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller") &&
        hasVisibleFixtureText("Allow Maestro Test Lab to send you notifications?")

/** General journeys start denied; dedicated permission journeys retain the prompt for Maestro to operate. */
private fun AccessibilityNodeInfo.denyMaestroNotificationPrompt(): Boolean {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)
    var count = 0
    while (queue.isNotEmpty() && count++ < 256) {
        val node = queue.removeFirst()
        if (node.isVisibleToUser && node.isEnabled && node.isClickable) {
            if (node.viewIdResourceName == "com.android.permissioncontroller:id/permission_deny_button") {
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
