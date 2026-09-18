package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device evidence for #1579: as seen by a NotificationListenerService, a catch-up cohort gets one
 * sound and vibration opportunity, later cards of the cohort arrive with the alert-once flag, and a
 * live message after catch-up settles alerts normally again.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class NotificationCatchUpAlertDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val listener = ComponentName(context, NotificationTimingListenerService::class.java)
    private var listenerProvisioned = false
    private val catchUpWindow = NotificationCatchUpWindow(tailMs = CATCH_UP_TAIL_MS)
    private val target = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID_HEX)

    /** Provisions emulator notification access while preserving permission state on physical devices. */
    @Before
    fun provisionNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
                shell("getprop ro.kernel.qemu").trim() == "1"
            ) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            assertEquals(
                "Grant notification permission on the selected test device before running; the test preserves it",
                PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (!notificationManager.isNotificationListenerAccessGranted(listener)) {
            NotificationTimingDeviceEvents.listenerConnected = false
            listenerProvisioned = true
            shell("cmd notification allow_listener ${listener.flattenToString()}")
        }
        NotificationListenerService.requestRebind(listener)
        assertTrue(
            "Debug notification listener did not connect; verify notification-listener access on the device",
            waitUntil(LISTENER_CONNECT_TIMEOUT_MS) { NotificationTimingDeviceEvents.listenerConnected },
        )
    }

    /** Removes the fixture's card and only the listener grant this fixture added. */
    @After
    fun cleanUp() {
        NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        NotificationTimingDeviceEvents.clear()
        if (listenerProvisioned) {
            shell("cmd notification disallow_listener ${listener.flattenToString()}")
            listenerProvisioned = false
        }
    }

    /** Three backlog cards then one live card: the listener sees alert-once on exactly the cohort's followers. */
    @Test
    fun catchUpCohortAlertsOnceAndLiveMessageAlertsAgain() {
        val presenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { },
                // A short burst window keeps the live card outside the burst that the cohort's ring opened.
                alertBudget = NotificationAlertBudget(catchUpWindow, burstWindowMs = BURST_WINDOW_MS),
            )
        presenter.ensureChannels()
        NotificationTimingDeviceEvents.arm(context.packageName, target.tag, target.id)

        catchUpWindow.open()
        val cohortFlags = (1..3).map { index -> postAndObserve(presenter, "backlog-$index") }
        catchUpWindow.close()
        SystemClock.sleep(CATCH_UP_TAIL_MS + TAIL_SETTLE_MS)
        val liveFlag = postAndObserve(presenter, "live")

        assertEquals(
            "one alert opportunity for the cohort, followers alert-once, then the live card alerts again",
            listOf(false, true, true, false),
            cohortFlags + liveFlag,
        )
    }

    /** Posts one card, returns its alert-once flag as the listener saw it, and drains any silent rewrite. */
    private fun postAndObserve(
        presenter: LocalNotificationPresenter,
        messageIdHex: String,
    ): Boolean {
        val posted = runBlocking { presenter.show(update = update(messageIdHex), shortNpub = { "npub1cohort" }) }
        assertTrue("the card for $messageIdHex must be written", posted)
        val first = NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS)
        assertNotNull("listener did not report the card for $messageIdHex", first)
        // A later identity or avatar rewrite of the same card is always silent and is not the first post.
        var extra = NotificationTimingDeviceEvents.awaitPost(NO_ADDITIONAL_POST_WINDOW_MS)
        while (extra != null) {
            assertTrue("a rewrite must never ring again", extra.onlyAlertOnce)
            extra = NotificationTimingDeviceEvents.awaitPost(NO_ADDITIONAL_POST_WINDOW_MS)
        }
        assertNull(extra)
        assertFalse(first!!.key.isEmpty())
        return first.onlyAlertOnce
    }

    /** One synthetic group message for the fixture conversation, keyed by [messageIdHex]. */
    private fun update(messageIdHex: String): NotificationUpdateFfi =
        NotificationUpdateFfi(
            notificationKey = "catch-up-device-$messageIdHex",
            conversationKey = "catch-up-conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = ACCOUNT_REF,
            accountIdHex = ACCOUNT_REF,
            groupIdHex = GROUP_ID_HEX,
            groupName = "Catch-up group",
            isDm = false,
            isMention = false,
            messageIdHex = messageIdHex,
            sender = NotificationUserFfi(accountIdHex = "catch-up-sender", displayName = "Sender", pictureUrl = null),
            receiver = NotificationUserFfi(accountIdHex = ACCOUNT_REF, displayName = "Receiver", pictureUrl = null),
            previewText = "Catch-up probe $messageIdHex",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = System.currentTimeMillis(),
            isFromSelf = false,
        )

    /** Runs a bounded fixture shell command and returns its UTF-8 output. */
    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { output -> output.readBytes().toString(Charsets.UTF_8) }

    /** Polls a device-owned readiness condition up to a fixed deadline. */
    private fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        const val ACCOUNT_REF = "catch-up-account"
        const val GROUP_ID_HEX = "catch-up-group"
        const val CATCH_UP_TAIL_MS = 300L
        const val BURST_WINDOW_MS = 200L
        const val TAIL_SETTLE_MS = 100L
        const val LISTENER_CONNECT_TIMEOUT_MS = 15_000L
        const val LISTENER_POST_TIMEOUT_MS = 5_000L
        const val NO_ADDITIONAL_POST_WINDOW_MS = 500L
    }
}
