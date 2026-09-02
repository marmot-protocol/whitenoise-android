package dev.ipf.whitenoise.android.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-backed lifecycle contract for issue #2198.
 *
 * These tests use a deterministic recognizer generation while exercising the real Android
 * foreground service, notification, task, and permission boundaries. Provider-specific microphone
 * indicators, endpointing, offline behavior, and process death remain physical-matrix evidence
 * because killing this target process would also kill the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class ConversationDictationBackgroundSessionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val defaultHostResolver = ConversationDictationForegroundService.hostResolver
    private var scenario: ActivityScenario<MainActivity>? = null
    private var harness: Harness? = null

    /** Opens a visible target Activity before any microphone foreground-service start. */
    @Before
    fun prepareVisibleApp() {
        scenario =
            ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        scenario?.onActivity { }
    }

    /** Restores process-wide service seams without clearing or uninstalling the target app. */
    @After
    fun releaseSession() {
        harness?.let { installed ->
            instrumentation.runOnMainSync { installed.controller.cancel() }
        }
        context.stopService(Intent(context, ConversationDictationForegroundService::class.java))
        ConversationDictationForegroundService.hostResolver = defaultHostResolver
        scenario?.close()
        notificationManager.cancel(ConversationDictationForegroundService.NOTIFICATION_ID)
    }

    /**
     * Verifies Home keeps one metadata-free notification and its Stop action delivers only to the
     * immutable origin when the app returns.
     */
    @Test
    fun homeNotificationStopAndReturnDeliverOnlyToTheOrigin() {
        assumeConfiguredPermissions(notificationRequired = true)
        val installed = installHarness(backgroundAllowed = { true })
        start(installed)
        val notification = awaitDictationNotification()

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(context.getString(R.string.dictation_notification_title), notificationTitle(notification))
        assertFalse(notification.extras.toString().contains(ORIGIN_ACCOUNT))
        assertFalse(notification.extras.toString().contains(ORIGIN_GROUP))
        assertEquals(2, notification.actions.size)
        assertEquals(context.getString(R.string.dictation_notification_stop), notification.actions[0].title)

        pressHome()
        instrumentation.runOnMainSync { installed.controller.onAppBackgrounded() }
        assertTrue(installed.controller.hasDurableSession)
        assertEquals(1, activeDictationNotificationCount())

        notification.actions[0].actionIntent.send()
        awaitCondition { installed.controller.state is ConversationDictationState.Processing }
        instrumentation.runOnMainSync { installed.platform.listener.onResult("device transcript") }
        returnToApp()

        assertEquals("Origin device transcript", installed.drafts.getValue(originKey()).text)
        assertEquals("Other", installed.drafts.getValue(otherKey()).text)
        assertTrue(installed.controller.state is ConversationDictationState.Idle)
        awaitCondition { activeDictationNotificationCount() == 0 }
    }

    /** Verifies recents removal does not retarget capture and service loss cannot resurrect it. */
    @Test
    fun recentsRemovalSurvivesUntilServiceLossThenFailsClosed() {
        assumeConfiguredPermissions(notificationRequired = true)
        val installed = installHarness(backgroundAllowed = { true })
        start(installed)
        awaitDictationNotification()

        scenario?.onActivity { activity -> activity.finishAndRemoveTask() }
        instrumentation.waitForIdleSync()
        assertTrue(installed.controller.hasPendingSession)
        assertTrue(installed.controller.hasDurableSession)
        assertTrue(installed.controller.isOwnedBy(ORIGIN_ACCOUNT, ORIGIN_GROUP))

        context.stopService(Intent(context, ConversationDictationForegroundService::class.java))

        awaitCondition { installed.controller.state is ConversationDictationState.Idle }
        assertFalse(installed.controller.hasDurableSession)
        assertEquals("Origin", installed.drafts.getValue(originKey()).text)
    }

    /** Verifies Android 13+ notification denial is disclosed and makes the session foreground-only. */
    @Test
    fun deniedNotificationPermissionStopsInsteadOfHidingBackgroundControls() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue(
            "Requires the API 33+ denied-notification matrix state",
            !permissionGranted(Manifest.permission.POST_NOTIFICATIONS),
        )
        assumeTrue(
            "Requires RECORD_AUDIO to exercise microphone FGS startup",
            permissionGranted(Manifest.permission.RECORD_AUDIO),
        )
        var limitationNotices = 0
        val installed =
            installHarness(
                backgroundAllowed = {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                },
                onBackgroundUnavailable = { limitationNotices += 1 },
            )

        start(installed)
        assertEquals(1, limitationNotices)
        pressHome()
        instrumentation.runOnMainSync { installed.controller.onAppBackgrounded() }

        assertTrue(installed.controller.state is ConversationDictationState.Idle)
        assertFalse(installed.controller.hasDurableSession)
        assertEquals("Origin", installed.drafts.getValue(originKey()).text)
    }

    /** Installs a deterministic recognizer behind the real foreground-service host seam. */
    private fun installHarness(
        backgroundAllowed: () -> Boolean,
        onBackgroundUnavailable: () -> Unit = {},
    ): Harness =
        Harness(
            context = context,
            backgroundAllowed = backgroundAllowed,
            onBackgroundUnavailable = onBackgroundUnavailable,
        ).also { installed ->
            harness = installed
            ConversationDictationForegroundService.hostResolver = { installed }
        }

    /** Starts capture synchronously while the target Activity is still resumed. */
    private fun start(installed: Harness) {
        instrumentation.runOnMainSync {
            assertTrue(
                installed.controller.requestStart(
                    ORIGIN_ACCOUNT,
                    ORIGIN_GROUP,
                    installed.drafts.getValue(originKey()),
                ),
            )
        }
        awaitCondition { installed.controller.hasDurableSession }
    }

    /** Returns the one active dictation notification posted by the target package. */
    private fun awaitDictationNotification(): Notification {
        var notification: Notification? = null
        awaitCondition {
            notification =
                notificationManager.activeNotifications
                    .map { it.notification }
                    .firstOrNull { notificationTitle(it) == context.getString(R.string.dictation_notification_title) }
            notification != null
        }
        return checkNotNull(notification)
    }

    /** Counts only the privacy-safe dictation foreground notification. */
    private fun activeDictationNotificationCount(): Int =
        notificationManager.activeNotifications.count {
            notificationTitle(it.notification) == context.getString(R.string.dictation_notification_title)
        }

    /** Reads the public notification title without inspecting any private app state. */
    private fun notificationTitle(notification: Notification): String =
        notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

    /** Moves the app to the launcher using the same platform input path as a user Home press. */
    private fun pressHome() {
        shell("input keyevent KEYCODE_HOME")
        instrumentation.waitForIdleSync()
    }

    /** Executes a non-mutating device command and drains its output before returning. */
    private fun shell(command: String) {
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { output -> output.readBytes() }
    }

    /** Reopens the existing target task without constructing a second dictation session. */
    private fun returnToApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        instrumentation.waitForIdleSync()
    }

    /** Requires the preconfigured physical/emulator matrix grants without mutating user settings. */
    private fun assumeConfiguredPermissions(notificationRequired: Boolean) {
        assumeTrue(
            "Requires RECORD_AUDIO to exercise microphone FGS startup",
            permissionGranted(Manifest.permission.RECORD_AUDIO),
        )
        if (notificationRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assumeTrue(
                "Requires the API 33+ granted-notification matrix state",
                permissionGranted(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    /** Reads one runtime grant without changing the personal-device permission state. */
    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Waits for asynchronous service and notification transitions with a bounded device timeout. */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(POLL_MILLIS)
        }
        assertTrue("Timed out waiting for device-backed dictation state", condition())
    }

    /** Process-level service host with two drafts and one deterministic recognition generation. */
    private class Harness(
        context: Context,
        backgroundAllowed: () -> Boolean,
        onBackgroundUnavailable: () -> Unit,
    ) : ConversationDictationServiceHost {
        val platform = FakePlatform()
        val drafts =
            mutableMapOf(
                originKey() to TextFieldValue("Origin", TextRange(6)),
                otherKey() to TextFieldValue("Other", TextRange(5)),
            )
        override val conversationDictation: ConversationDictationController
            get() = controller
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { account, group ->
                    ConversationDictationDraftSnapshot(drafts.getValue(account to group), 0L)
                },
                writeDraft = { account, group, _, value ->
                    drafts[account to group] = value
                    true
                },
                startDurableSession = { ConversationDictationForegroundService.start(context) },
                stopDurableSession = { ConversationDictationForegroundService.stop(context) },
                canContinueInBackground = backgroundAllowed,
                onBackgroundContinuationUnavailable = onBackgroundUnavailable,
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
            )
    }

    /** Deterministic provider seam; Android service/notification ownership remains real. */
    private class FakePlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        /** The matrix precondition already verified the target app's microphone grant. */
        override fun hasRecordAudioPermission(): Boolean = true

        /** The deterministic generation replaces provider discovery in this lifecycle suite. */
        override fun recognitionAvailable(): Boolean = true

        /** Captures the callback while leaving real service and notification ownership intact. */
        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        const val ORIGIN_ACCOUNT = "device-origin-account"
        const val ORIGIN_GROUP = "device-origin-group"
        const val OTHER_ACCOUNT = "device-other-account"
        const val OTHER_GROUP = "device-other-group"
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L

        /** Returns the immutable destination owned by every test session. */
        fun originKey(): Pair<String, String> = ORIGIN_ACCOUNT to ORIGIN_GROUP

        /** Returns the non-origin draft that must never receive dictation output. */
        fun otherKey(): Pair<String, String> = OTHER_ACCOUNT to OTHER_GROUP
    }
}
