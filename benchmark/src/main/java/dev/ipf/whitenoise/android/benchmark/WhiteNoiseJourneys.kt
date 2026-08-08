package dev.ipf.whitenoise.android.benchmark

import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.onElementOrNull
import androidx.test.uiautomator.textAsString

internal class WhiteNoiseJourneys {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun MacrobenchmarkScope.launchToChatList() {
        startActivityAndWait()
        waitForTag(PerformanceTags.NEW_MESSAGE, STARTUP_TIMEOUT_MS)
        device.waitForIdle()
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
        waitForEditableText().text = groupName
        device.pressBack()
        waitForTag(PerformanceTags.CREATE_GROUP).click()
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
            if (findTag(PerformanceTags.NEW_MESSAGE) != null) return
            device.pressBack()
            device.waitForIdle()
        }
        waitForTag(PerformanceTags.NEW_MESSAGE)
    }

    private fun findTag(tag: String): UiObject2? =
        device.onElementOrNull(timeoutMs = 0) {
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
                "Confirm the dev app is authenticated and the fixture is in the expected state."
        }

    private fun waitForText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 =
        checkNotNull(device.onElementOrNull(timeoutMs = timeoutMs) { textAsString() == text }) {
            "Timed out waiting for fixture row '$text'."
        }

    private fun waitForEditableText(): UiObject2 =
        checkNotNull(
            device.onElementOrNull(timeoutMs = DEFAULT_TIMEOUT_MS) {
                className?.toString() == EDIT_TEXT_CLASS && isEnabled
            },
        ) {
            "Timed out waiting for the group-name text field."
        }

    private fun AccessibilityNodeInfo.matchesPerformanceTag(tag: String): Boolean =
        contentDescription?.toString() == tag ||
            extras.getCharSequence(COMPOSE_TEST_TAG_KEY)?.toString() == tag

    private companion object {
        const val COMPOSE_TEST_TAG_KEY = "androidx.compose.ui.semantics.testTag"
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val NETWORK_STATE_TIMEOUT_MS = 45_000L
    }
}
