package dev.ipf.whitenoise.android.benchmark

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.onElementOrNull
import androidx.test.uiautomator.textAsString

internal data class NotificationRouteSample(
    val durationMs: Long,
    val transcriptVisible: Boolean,
    val expectedConversationVisible: Boolean,
) {
    val succeeded: Boolean
        get() = transcriptVisible && expectedConversationVisible
}

internal data class ConversationSettingsLaunchSample(
    val appDispatchDurationMs: Long,
    val firstSettingsFrameDurationMs: Long,
)

internal enum class BenchmarkUsefulSurface {
    ChatList,
    Conversation,
}

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

    /** Launches an explicit text share and waits for the picker useful surface. */
    fun MacrobenchmarkScope.launchSharePickerForStartupMeasurement() {
        startActivityAndWait(
            Intent(Intent.ACTION_SEND)
                .setComponent(
                    ComponentName(
                        BenchmarkConfig.TARGET_PACKAGE,
                        "dev.ipf.whitenoise.android.MainActivity",
                    ),
                ).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Inbound share startup benchmark")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitForVisibleTag(PerformanceTags.SHARE_PICKER, STARTUP_TIMEOUT_MS)
    }

    /**
     * Resumes the existing task and unwinds any journey left by the preceding
     * iteration. Unlike [launchToChatList], this is setup work and is excluded
     * from the measured trace.
     */
    fun MacrobenchmarkScope.resumeToChatList() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        startActivityAndWait()
        checkNotNull(
            device.onElementOrNull(timeoutMs = STARTUP_TIMEOUT_MS) {
                matchesPerformanceTag(PerformanceTags.MAIN_SHELL_ROUTE_SETTLED) ||
                    matchesPerformanceTag(PerformanceTags.CONVERSATION_ROUTE_SETTLED)
            },
        ) {
            "Timed out waiting for the resumed White Noise route to settle. " +
                "Foreground package: ${device.currentPackageName ?: "unknown"}."
        }
        if (findVisibleTag(PerformanceTags.NEW_MESSAGE) == null) {
            returnToChatList()
        }
        device.waitForIdle()
    }

    /** Resumes without changing the user's useful route and returns its type. */
    fun MacrobenchmarkScope.resumeToUsefulSurface(): BenchmarkUsefulSurface {
        startActivityAndWait()
        checkNotNull(findTagWithPrefix(PerformanceTags.ACTIVITY_INSTANCE_PREFIX)) {
            "Timed out waiting for the White Noise root surface."
        }
        return waitForUsefulSurface().also { device.waitForIdle() }
    }

    /** Recreates only MainActivity while retaining the target process and its ViewModels. */
    fun MacrobenchmarkScope.recreateActivityAndWait(expectedSurface: BenchmarkUsefulSurface) {
        val previousActivityMarker =
            checkNotNull(findTagWithPrefix(PerformanceTags.ACTIVITY_INSTANCE_PREFIX)) {
                "Missing Activity-instance marker before benchmark recreation."
            }.resourceName
        val component = "${BenchmarkConfig.TARGET_PACKAGE}/dev.ipf.whitenoise.android.MainActivity"
        val output =
            device.executeShellCommand(
                "am start -W -n $component --ez $BENCHMARK_RECREATE_ACTIVITY_EXTRA true",
            )
        check(output.lineSequence().any { it.trim() == "Status: ok" }) {
            "Benchmark Activity recreation request failed: $output"
        }
        checkNotNull(
            device.onElementOrNull(timeoutMs = STARTUP_TIMEOUT_MS) {
                matchesPerformanceTagPrefix(PerformanceTags.ACTIVITY_INSTANCE_PREFIX) &&
                    viewIdResourceName != previousActivityMarker
            },
        ) {
            "Timed out waiting for the recreated Activity to replace $previousActivityMarker."
        }
        waitForUsefulSurface(expectedSurface)
    }

    /** Brings back the existing task after `killProcess` without force-stopping or clearing it. */
    fun MacrobenchmarkScope.launchRestoredTaskAndWait(expectedSurface: BenchmarkUsefulSurface) {
        val component = "${BenchmarkConfig.TARGET_PACKAGE}/dev.ipf.whitenoise.android.MainActivity"
        val output = device.executeShellCommand("am start -W -n $component")
        check(output.lineSequence().any { it.trim() == "Status: ok" }) {
            "Benchmark process-restoration launch failed: $output"
        }
        checkNotNull(findTagWithPrefix(PerformanceTags.ACTIVITY_INSTANCE_PREFIX)) {
            "Timed out waiting for the restored White Noise root surface."
        }
        waitForUsefulSurface(expectedSurface)
    }

    /** Opens the named fixture and verifies that its conversation title bar is ready. */
    fun openGroup(groupName: String) {
        clickTextUntilTagPresent(
            text = groupName,
            destinationTag = PerformanceTags.OPEN_GROUP_DETAILS,
            timeoutMs = NETWORK_STATE_TIMEOUT_MS,
        )
        waitForVisibleTag(PerformanceTags.OPEN_GROUP_DETAILS)
    }

    fun openMembers(groupName: String) {
        openGroup(groupName)
        waitForTag(PerformanceTags.OPEN_GROUP_DETAILS).click()
        waitForTag(PerformanceTags.MEMBER_LIST, NETWORK_STATE_TIMEOUT_MS)
    }

    /** Navigates to a group notification screen and waits until prewarming has completed. */
    fun openGroupNotificationSettings(groupName: String) {
        openGroup(groupName)
        clickVisibleTagUntilPresent(
            triggerTag = PerformanceTags.OPEN_GROUP_DETAILS,
            destinationTag = PerformanceTags.GROUP_NOTIFICATION_SETTINGS,
            timeoutMs = NETWORK_STATE_TIMEOUT_MS,
        )
        clickVisibleTagUntilPresent(
            triggerTag = PerformanceTags.GROUP_NOTIFICATION_SETTINGS,
            destinationTag = PerformanceTags.GROUP_MESSAGE_NOTIFICATION_SETTINGS,
            timeoutMs = NETWORK_STATE_TIMEOUT_MS,
            scrollToTrigger = true,
        )
        waitForVisibleTag(PerformanceTags.GROUP_MESSAGE_NOTIFICATION_SETTINGS, NETWORK_STATE_TIMEOUT_MS)
        device.waitForIdle()
    }

    /** Opens the exact prepared group-message channel and records Android's first visible frame. */
    fun openPreparedGroupMessageNotificationSettings(): ConversationSettingsLaunchSample {
        val settingsButton = waitForTag(PerformanceTags.GROUP_MESSAGE_NOTIFICATION_SETTINGS)
        val clickedAtMs = SystemClock.elapsedRealtime()
        Trace.beginSection(OPEN_CONVERSATION_SETTINGS_TRACE)
        try {
            settingsButton.click()
            checkNotNull(
                device.onElementOrNull(timeoutMs = SETTINGS_TRANSITION_TIMEOUT_MS) {
                    packageName?.toString() == ANDROID_SETTINGS_PACKAGE && isVisibleOnDisplay()
                },
            ) {
                "Timed out waiting for the first visible Android Settings frame. " +
                    "Foreground package: ${device.currentPackageName ?: "unknown"}."
            }
        } finally {
            Trace.endSection()
        }
        val firstFrameDurationMs = SystemClock.elapsedRealtime() - clickedAtMs
        val launchRecord = latestConversationSettingsLaunchRecord()
        check(launchRecord.preparationSucceeded) {
            "The measured tap did not reuse a successfully prepared conversation target."
        }
        Log.i(
            CONVERSATION_SETTINGS_LOG_TAG,
            "operation_id=${launchRecord.operationId} stage=first_settings_frame " +
                "duration_ms=$firstFrameDurationMs outcome=ok",
        )
        return ConversationSettingsLaunchSample(
            appDispatchDurationMs = launchRecord.durationMs,
            firstSettingsFrameDurationMs = firstFrameDurationMs,
        )
    }

    fun scrollConversation() {
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        repeat(CONVERSATION_SCROLL_PASSES) {
            check(device.swipe(x, bottom, x, top, CONVERSATION_SCROLL_STEPS)) {
                "Failed to scroll toward older conversation messages."
            }
        }
        repeat(CONVERSATION_SCROLL_PASSES) {
            check(device.swipe(x, top, x, bottom, CONVERSATION_SCROLL_STEPS)) {
                "Failed to scroll back toward the newest conversation messages."
            }
        }
        device.waitForIdle()
    }

    /**
     * Scrolls the chat list down and back. Every pass republishes the visible
     * row window, so this exercises row composition, avatar cache hits, and
     * the folder-chip derivation that runs beside them.
     */
    fun scrollChatList() {
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        repeat(CHAT_LIST_SCROLL_PASSES) {
            check(device.swipe(x, bottom, x, top, CHAT_LIST_SCROLL_STEPS)) {
                "Failed to scroll toward older chats."
            }
        }
        repeat(CHAT_LIST_SCROLL_PASSES) {
            check(device.swipe(x, top, x, bottom, CHAT_LIST_SCROLL_STEPS)) {
                "Failed to scroll back toward the most recent chats."
            }
        }
        device.waitForIdle()
    }

    fun openConversationVisible(groupName: String) {
        waitForText(groupName).click()
        waitForVisibleTag(PerformanceTags.CONVERSATION_TRANSCRIPT_VISIBLE)
    }

    /** Waits for the nonvisual marker proving the destination route rendered after settling. */
    fun waitForConversationRouteSettled() {
        waitForTag(PerformanceTags.CONVERSATION_ROUTE_SETTLED)
    }

    /** Waits for the nonvisual marker proving the outgoing conversation controller was released. */
    fun waitForConversationControllerReleased() {
        waitForTag(PerformanceTags.CONVERSATION_CONTROLLER_RELEASED)
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

    /** Restores the same non-target account before every notification sample. */
    fun activateNotificationSourceAccount(accountRef: String) {
        val otherAccountAvatarTag = "other-account-avatar-$accountRef"
        val avatar = findVisibleTag(otherAccountAvatarTag, NAVIGATION_SETTLE_TIMEOUT_MS)
        if (avatar != null) {
            avatar.click()
            waitForVisibleTagAbsent(otherAccountAvatarTag, NOTIFICATION_ROUTE_TIMEOUT_MS)
        }
        waitForVisibleTag(PerformanceTags.NEW_MESSAGE, NOTIFICATION_ROUTE_TIMEOUT_MS)
        waitForTag(PerformanceTags.MAIN_SHELL_ROUTE_SETTLED, NOTIFICATION_ROUTE_TIMEOUT_MS)
        device.waitForIdle()
    }

    /**
     * Opens one fresh inactive-account notification and measures the first
     * readable transcript, while independently validating the conversation.
     */
    fun openSecondaryAccountNotification(
        notificationText: String,
        expectedConversationTitle: String,
    ): NotificationRouteSample {
        device.openNotification()
        val notification = waitForText(notificationText)
        val intentDeliveryApproximationMs = SystemClock.elapsedRealtime()
        notification.click()
        val diagnosticDeadlineMs = intentDeliveryApproximationMs + NOTIFICATION_ROUTE_DIAGNOSTIC_TIMEOUT_MS
        val transcript =
            findVisibleTag(
                PerformanceTags.CONVERSATION_TRANSCRIPT_VISIBLE,
                (diagnosticDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
            )
        val durationMs = SystemClock.elapsedRealtime() - intentDeliveryApproximationMs
        val expectedConversation =
            if (transcript == null) {
                null
            } else {
                device.onElementOrNull(
                    timeoutMs = (diagnosticDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                ) {
                    textAsString() == expectedConversationTitle && isVisibleOnDisplay()
                }
            }
        return NotificationRouteSample(
            durationMs = durationMs,
            transcriptVisible = transcript != null,
            expectedConversationVisible = expectedConversation != null,
        )
    }

    /** Unwinds only White Noise routes until the authenticated chat list is visible. */
    fun returnToChatList() {
        repeat(MAX_CHAT_LIST_UNWIND_STEPS) {
            if (
                findVisibleTag(PerformanceTags.NEW_MESSAGE, NAVIGATION_SETTLE_TIMEOUT_MS) != null &&
                findTag(PerformanceTags.MAIN_SHELL_ROUTE_SETTLED, NAVIGATION_SETTLE_TIMEOUT_MS) != null
            ) {
                device.waitForIdle()
                return
            }
            if (device.currentPackageName != BenchmarkConfig.TARGET_PACKAGE) {
                val component = "${BenchmarkConfig.TARGET_PACKAGE}/dev.ipf.whitenoise.android.MainActivity"
                val output = device.executeShellCommand("am start -W -n $component")
                check(output.lineSequence().any { it.trim() == "Status: ok" }) {
                    "Failed to resume White Noise while resetting the benchmark journey: $output"
                }
                checkNotNull(findTagWithPrefix(PerformanceTags.ACTIVITY_INSTANCE_PREFIX)) {
                    "White Noise did not expose its Activity marker after journey reset."
                }
                device.waitForIdle()
                return@repeat
            }
            // UiDevice returns false when no matching accessibility event is
            // observed, even when Android's predictive Back handled the key.
            // The bounded selector wait is the authoritative navigation check.
            device.pressBack()
            device.waitForIdle()
        }
        waitForVisibleTag(PerformanceTags.NEW_MESSAGE)
        waitForTag(PerformanceTags.MAIN_SHELL_ROUTE_SETTLED)
        device.waitForIdle()
    }

    private fun findTag(
        tag: String,
        timeoutMs: Long = 0,
    ): UiObject2? =
        device.onElementOrNull(timeoutMs = timeoutMs) {
            matchesPerformanceTag(tag)
        }

    private fun findVisibleTag(
        tag: String,
        timeoutMs: Long = 0,
    ): UiObject2? =
        device.onElementOrNull(timeoutMs = timeoutMs) {
            matchesPerformanceTag(tag) && isVisibleOnDisplay()
        }

    private fun findTagWithPrefix(prefix: String): UiObject2? =
        device.onElementOrNull(timeoutMs = DEFAULT_TIMEOUT_MS) {
            matchesPerformanceTagPrefix(prefix)
        }

    fun waitForUsefulSurface(expected: BenchmarkUsefulSurface? = null): BenchmarkUsefulSurface {
        val resolved =
            expected ?: run {
                val deadline = SystemClock.uptimeMillis() + STARTUP_TIMEOUT_MS
                var detected: BenchmarkUsefulSurface? = null
                while (detected == null && SystemClock.uptimeMillis() < deadline) {
                    detected =
                        when {
                            findVisibleTag(PerformanceTags.CONVERSATION_TRANSCRIPT_VISIBLE) != null ->
                                BenchmarkUsefulSurface.Conversation
                            findVisibleTag(PerformanceTags.NEW_MESSAGE) != null ->
                                BenchmarkUsefulSurface.ChatList
                            else -> null
                        }
                    if (detected == null) SystemClock.sleep(SELECTOR_POLL_INTERVAL_MS)
                }
                checkNotNull(detected) {
                    "Timed out waiting for a useful chat-list or conversation surface. " +
                        "Available performance tags: ${availablePerformanceTags()}."
                }
            }
        when (resolved) {
            BenchmarkUsefulSurface.ChatList -> {
                waitForVisibleTag(PerformanceTags.NEW_MESSAGE, STARTUP_TIMEOUT_MS)
            }
            BenchmarkUsefulSurface.Conversation -> {
                waitForVisibleTag(PerformanceTags.CONVERSATION_TRANSCRIPT_VISIBLE, STARTUP_TIMEOUT_MS)
            }
        }
        return resolved
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

    /** Waits for a White Noise selector while recovering setup-only foreign-app interruptions. */
    private fun waitForVisibleTag(
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            resumeTargetIfInterrupted()
            findVisibleTag(tag, SELECTOR_POLL_INTERVAL_MS)?.let { return it }
        }
        error(
            "Timed out waiting for visible test tag '$tag'. " +
                "Available performance tags: ${availablePerformanceTags()}.",
        )
    }

    /** Restores White Noise when another local device session steals benchmark foreground. */
    private fun resumeTargetIfInterrupted() {
        if (device.currentPackageName == BenchmarkConfig.TARGET_PACKAGE) return
        val component = "${BenchmarkConfig.TARGET_PACKAGE}/dev.ipf.whitenoise.android.MainActivity"
        val output = device.executeShellCommand("am start -W -n $component")
        check(output.lineSequence().any { it.trim() == "Status: ok" }) {
            "Failed to restore White Noise benchmark foreground: $output"
        }
        device.waitForIdle()
    }

    /** Scrolls the current Compose surface until the requested benchmark control is actually on-screen. */
    private fun scrollToVisibleTag(
        tag: String,
        timeoutMs: Long,
    ): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val x = device.displayWidth / 2
        val startY = device.displayHeight * 3 / 4
        val endY = device.displayHeight / 4
        while (SystemClock.uptimeMillis() < deadline) {
            findVisibleTag(tag)?.let { return it }
            check(device.swipe(x, startY, x, endY, SETTINGS_SCROLL_STEPS)) {
                "Failed to scroll toward visible test tag '$tag'."
            }
            device.waitForIdle()
        }
        error(
            "Timed out scrolling to visible test tag '$tag'. " +
                "Available performance tags: ${availablePerformanceTags()}.",
        )
    }

    /** Re-resolves a Compose control until its click has produced the expected destination surface. */
    private fun clickVisibleTagUntilPresent(
        triggerTag: String,
        destinationTag: String,
        timeoutMs: Long,
        scrollToTrigger: Boolean = false,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStaleNode: StaleObjectException? = null
        while (SystemClock.uptimeMillis() < deadline) {
            resumeTargetIfInterrupted()
            if (findTag(destinationTag) != null) return
            val remainingMs = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            try {
                val trigger =
                    if (scrollToTrigger) {
                        scrollToVisibleTag(triggerTag, remainingMs)
                    } else {
                        waitForVisibleTag(triggerTag, remainingMs.coerceAtMost(DEFAULT_TIMEOUT_MS))
                    }
                trigger.click()
            } catch (error: StaleObjectException) {
                lastStaleNode = error
                SystemClock.sleep(SELECTOR_POLL_INTERVAL_MS)
                continue
            } catch (error: IllegalStateException) {
                if (findTag(destinationTag) != null) return
                if (device.currentPackageName != BenchmarkConfig.TARGET_PACKAGE) continue
                throw error
            }
            val settleDeadline = (SystemClock.uptimeMillis() + NAVIGATION_SETTLE_TIMEOUT_MS).coerceAtMost(deadline)
            while (SystemClock.uptimeMillis() < settleDeadline) {
                if (findTag(destinationTag) != null) return
                SystemClock.sleep(SELECTOR_POLL_INTERVAL_MS)
            }
        }
        throw IllegalStateException(
            "Timed out navigating from test tag '$triggerTag' to '$destinationTag'.",
            lastStaleNode,
        )
    }

    /** Re-resolves a fixture row until its click has opened the expected conversation surface. */
    private fun clickTextUntilTagPresent(
        text: String,
        destinationTag: String,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStaleNode: StaleObjectException? = null
        while (SystemClock.uptimeMillis() < deadline) {
            resumeTargetIfInterrupted()
            if (findTag(destinationTag) != null) return
            val remainingMs = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            try {
                waitForText(text, remainingMs.coerceAtMost(DEFAULT_TIMEOUT_MS)).click()
            } catch (error: StaleObjectException) {
                lastStaleNode = error
                SystemClock.sleep(SELECTOR_POLL_INTERVAL_MS)
                continue
            } catch (error: IllegalStateException) {
                if (findTag(destinationTag) != null) return
                if (device.currentPackageName != BenchmarkConfig.TARGET_PACKAGE) continue
                throw error
            }
            val settleDeadline = (SystemClock.uptimeMillis() + NAVIGATION_SETTLE_TIMEOUT_MS).coerceAtMost(deadline)
            while (SystemClock.uptimeMillis() < settleDeadline) {
                if (findTag(destinationTag) != null) return
                SystemClock.sleep(SELECTOR_POLL_INTERVAL_MS)
            }
        }
        throw IllegalStateException(
            "Timed out opening fixture row '$text' to '$destinationTag'.",
            lastStaleNode,
        )
    }

    private fun waitForVisibleTagAbsent(
        tag: String,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (findVisibleTag(tag) != null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(SELECTOR_POLL_INTERVAL_MS)
        }
        check(findVisibleTag(tag) == null) { "Timed out waiting for visible test tag '$tag' to disappear." }
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

    /** Correlates the newest dispatch with its successful opaque preparation operation. */
    private fun latestConversationSettingsLaunchRecord(): ConversationSettingsLaunchRecord {
        val output =
            device.executeShellCommand(
                "logcat -d -v brief -s $CONVERSATION_SETTINGS_LOG_TAG:I '*:S'",
            )
        val match =
            APP_DISPATCH_LOG_REGEX
                .findAll(output)
                .lastOrNull()
                ?: error("Missing privacy-safe conversation Settings dispatch timing in logcat.")
        val operationId = match.groupValues[1].toLong()
        val latestPreparation =
            output
                .substring(0, match.range.first)
                .lineSequence()
                .lastOrNull { line ->
                    "operation_id=$operationId " in line && "stage=prepare_total " in line
                }
        val latestPreferredReturn =
            output
                .lineSequence()
                .lastOrNull { line ->
                    "operation_id=$operationId " in line && "stage=start_activity_return " in line
                }
        return ConversationSettingsLaunchRecord(
            operationId = operationId,
            durationMs = match.groupValues[2].toLong(),
            preparationSucceeded =
                latestPreparation?.contains("outcome=ok") == true &&
                    latestPreferredReturn?.contains("outcome=ok") == true,
        )
    }

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

    private fun AccessibilityNodeInfo.matchesPerformanceTagPrefix(prefix: String): Boolean =
        viewIdResourceName?.startsWith(prefix) == true ||
            contentDescription?.toString()?.startsWith(prefix) == true ||
            extras.getCharSequence(COMPOSE_TEST_TAG_KEY)?.toString()?.startsWith(prefix) == true

    private fun AccessibilityNodeInfo.isVisibleOnDisplay(): Boolean {
        if (!isVisibleToUser) return false
        val nodeBounds = Rect()
        getBoundsInScreen(nodeBounds)
        if (nodeBounds.isEmpty) return false
        val displayBounds = Rect(0, 0, device.displayWidth, device.displayHeight)
        return Rect.intersects(nodeBounds, displayBounds)
    }

    private companion object {
        const val COMPOSE_TEST_TAG_KEY = "androidx.compose.ui.semantics.testTag"
        const val BENCHMARK_RECREATE_ACTIVITY_EXTRA =
            "dev.ipf.whitenoise.android.extra.BENCHMARK_RECREATE_ACTIVITY"
        const val PERFORMANCE_TAG_PREFIX = "performance."
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val NETWORK_STATE_TIMEOUT_MS = 45_000L
        const val NOTIFICATION_ROUTE_TIMEOUT_MS = 10_000L
        const val NOTIFICATION_ROUTE_DIAGNOSTIC_TIMEOUT_MS = 15_000L
        const val SETTINGS_SCROLL_STEPS = 12
        const val MAX_CHAT_LIST_UNWIND_STEPS = 6
        const val NAVIGATION_SETTLE_TIMEOUT_MS = 2_000L
        const val INPUT_METHOD_POLL_INTERVAL_MS = 100L
        const val SETTINGS_TRANSITION_TIMEOUT_MS = 5_000L
        const val SELECTOR_POLL_INTERVAL_MS = 50L
        const val CONVERSATION_SCROLL_PASSES = 4
        const val CONVERSATION_SCROLL_STEPS = 20
        const val CHAT_LIST_SCROLL_PASSES = 4
        const val CHAT_LIST_SCROLL_STEPS = 20
        const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
        const val CONVERSATION_SETTINGS_LOG_TAG = "ConversationSettings"
        val APP_DISPATCH_LOG_REGEX =
            Regex("operation_id=(\\d+) stage=start_activity duration_ms=(\\d+) outcome=ok")
    }
}

private data class ConversationSettingsLaunchRecord(
    val operationId: Long,
    val durationMs: Long,
    val preparationSucceeded: Boolean,
)
