package dev.ipf.whitenoise.android.benchmark

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.onElementOrNull
import androidx.test.uiautomator.textAsString

internal class WhiteNoiseJourneys {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Initializes the real fixture before Macrobenchmark resets compilation and starts tracing. */
    fun prepareAuthenticatedChatList() {
        val component = "${BenchmarkConfig.TARGET_PACKAGE}/dev.ipf.whitenoise.android.MainActivity"
        val launchOutput = device.executeShellCommand("am start -W -n $component")
        check(launchOutput.lineSequence().any { it.trim() == "Status: ok" }) {
            "Benchmark target preflight launch failed: $launchOutput"
        }
        waitForTag(PerformanceTags.NEW_MESSAGE, STARTUP_TIMEOUT_MS)
        check(device.pressHome()) { "Failed to return home after benchmark fixture preflight." }
        device.waitForIdle()
    }

    fun MacrobenchmarkScope.launchToChatList() {
        startActivityAndWait()
        waitForTag(PerformanceTags.NEW_MESSAGE, STARTUP_TIMEOUT_MS)
        device.waitForIdle()
    }

    /**
     * Launches the target for a startup measurement after [resumeToChatList]
     * has already validated the fixture outside the measured trace.
     *
     * Macrobenchmark's cold-start wrapper force-stops the target between setup
     * and measurement. Waiting for a test selector here would make that
     * selector part of the measured journey and can outlive the startup metric.
     */
    fun MacrobenchmarkScope.launchForStartupMeasurement() {
        startActivityAndWait()
    }

    /**
     * Resumes the existing task and unwinds any journey left by the preceding
     * iteration. Unlike [launchToChatList], this is setup work and is excluded
     * from the measured trace.
     */
    fun MacrobenchmarkScope.resumeToChatList() {
        startActivityAndWait()
        if (findTag(PerformanceTags.NEW_MESSAGE) == null) {
            returnToChatList()
        }
        device.waitForIdle()
    }

    fun openGroup(groupName: String) {
        waitForText(groupName).click()
        waitForTag(PerformanceTags.OPEN_GROUP_DETAILS)
    }

    fun openMembers(groupName: String) {
        openGroup(groupName)
        waitForTag(PerformanceTags.OPEN_GROUP_DETAILS).click()
        waitForTag(PerformanceTags.MEMBER_LIST, NETWORK_STATE_TIMEOUT_MS)
    }

    fun createGroup(
        prefix: String,
        suffix: Int,
    ): String {
        val groupName = "$prefix ${System.currentTimeMillis()}-$suffix"
        waitForTag(PerformanceTags.NEW_MESSAGE).click()
        waitForTag(PerformanceTags.NEW_GROUP).click()
        waitForTag(PerformanceTags.CONTACT_PICKER_NEXT).click()
        waitForTag(PerformanceTags.CREATE_GROUP)
        waitForEditableText().click()
        waitForFocusedEditableText().text = groupName
        waitForEditableText(groupName)
        dismissInputMethodIfVisible()
        waitForEnabledTag(PerformanceTags.CREATE_GROUP).click()
        waitForTag(PerformanceTags.OPEN_GROUP_DETAILS, NETWORK_STATE_TIMEOUT_MS)
        return groupName
    }

    fun acceptInvite(inviteName: String) {
        waitForText(inviteName).click()
        waitForTag(PerformanceTags.JOIN_INVITE).click()
        waitForTag(PerformanceTags.OPEN_GROUP_DETAILS, NETWORK_STATE_TIMEOUT_MS)
    }

    fun returnToChatList() {
        repeat(4) {
            if (findTag(PerformanceTags.NEW_MESSAGE, NAVIGATION_SETTLE_TIMEOUT_MS) != null) return
            // UiDevice returns false when no matching accessibility event is
            // observed, even when Android's predictive Back handled the key.
            // The bounded selector wait is the authoritative navigation check.
            device.pressBack()
            device.waitForIdle()
        }
        waitForTag(PerformanceTags.NEW_MESSAGE)
    }

    private fun findTag(
        tag: String,
        timeoutMs: Long = 0,
    ): UiObject2? =
        device.onElementOrNull(timeoutMs = timeoutMs) {
            matchesPerformanceTag(tag)
        }

    private fun waitForTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 =
        checkNotNull(
            device.onElementOrNull(timeoutMs = timeoutMs) {
                matchesPerformanceTag(tag)
            },
        ) {
            "Timed out waiting for test tag '$tag'. " +
                "Available performance tags: ${availablePerformanceTags()}. " +
                "Confirm the dev app is authenticated and the fixture is in the expected state."
        }

    private fun waitForEnabledTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 =
        checkNotNull(
            device.onElementOrNull(timeoutMs = timeoutMs) {
                matchesPerformanceTag(tag) && isEnabled && isClickable
            },
        ) {
            "Timed out waiting for enabled test tag '$tag'. " +
                "Foreground package: ${device.currentPackageName ?: "unknown"}. " +
                "Available performance tags: ${availablePerformanceTags()}."
        }

    private fun waitForText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 =
        checkNotNull(device.onElementOrNull(timeoutMs = timeoutMs) { textAsString() == text }) {
            "Timed out waiting for fixture row '$text'."
        }

    private fun waitForEditableText(expectedText: String? = null): UiObject2 =
        checkNotNull(
            device.onElementOrNull(timeoutMs = DEFAULT_TIMEOUT_MS) {
                className?.toString() == EDIT_TEXT_CLASS &&
                    isEnabled &&
                    (expectedText == null || textAsString() == expectedText)
            },
        ) {
            if (expectedText == null) {
                "Timed out waiting for the group-name text field."
            } else {
                "Timed out waiting for the group-name field to contain the entered text."
            }
        }

    private fun waitForFocusedEditableText(): UiObject2 =
        checkNotNull(
            device.onElementOrNull(timeoutMs = DEFAULT_TIMEOUT_MS) {
                className?.toString() == EDIT_TEXT_CLASS && isEnabled && isFocused
            },
        ) {
            "Timed out waiting for the group-name text field to receive focus."
        }

    private fun dismissInputMethodIfVisible() {
        if (!isInputMethodVisible()) return
        check(device.pressBack()) { "Failed to dismiss the input method." }

        val deadline = SystemClock.uptimeMillis() + DEFAULT_TIMEOUT_MS
        while (isInputMethodVisible() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(INPUT_METHOD_POLL_INTERVAL_MS)
        }
        check(!isInputMethodVisible()) { "Timed out waiting for the input method to close." }
    }

    private fun isInputMethodVisible(): Boolean =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .windows
            .any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun availablePerformanceTags(): String {
        val tags = linkedSetOf<String>()
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .windows
            .mapNotNull(AccessibilityWindowInfo::getRoot)
            .forEach { root -> collectPerformanceTags(root, tags) }
        return tags.sorted().joinToString().ifEmpty { "none" }
    }

    private fun collectPerformanceTags(
        node: AccessibilityNodeInfo,
        destination: MutableSet<String>,
    ) {
        sequenceOf(
            node.viewIdResourceName,
            node.contentDescription?.toString(),
            node.extras.getCharSequence(COMPOSE_TEST_TAG_KEY)?.toString(),
        ).filterNotNull()
            .filter { it.startsWith(PERFORMANCE_TAG_PREFIX) }
            .forEach(destination::add)
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child -> collectPerformanceTags(child, destination) }
        }
    }

    private fun AccessibilityNodeInfo.matchesPerformanceTag(tag: String): Boolean =
        viewIdResourceName == tag ||
            contentDescription?.toString() == tag ||
            extras.getCharSequence(COMPOSE_TEST_TAG_KEY)?.toString() == tag

    private companion object {
        const val COMPOSE_TEST_TAG_KEY = "androidx.compose.ui.semantics.testTag"
        const val PERFORMANCE_TAG_PREFIX = "performance."
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val NETWORK_STATE_TIMEOUT_MS = 45_000L
        const val NAVIGATION_SETTLE_TIMEOUT_MS = 2_000L
        const val INPUT_METHOD_POLL_INTERVAL_MS = 100L
    }
}
