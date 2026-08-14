package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Trace
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Device evidence for issue #1995.
 *
 * The measured interval ends at NotificationListenerService delivery. It does
 * not prove when a physical vibration began or when SystemUI rendered pixels;
 * correlate the emitted trace sections with the platform/external capture in
 * docs/notification-haptic-visual-timing.md.
 */
@RunWith(AndroidJUnit4::class)
class NotificationHapticVisualTimingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val listener = ComponentName(context, NotificationTimingListenerService::class.java)

    @Before
    fun provisionNotificationAccess() {
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("cmd notification allow_listener ${listener.flattenToString()}")
        NotificationListenerService.requestRebind(listener)
        assertTrue(
            "Debug notification listener did not connect; verify notification-listener access on the device",
            waitUntil(LISTENER_CONNECT_TIMEOUT_MS) { NotificationTimingDeviceEvents.listenerConnected },
        )
    }

    @After
    fun revokeNotificationAccess() {
        NotificationTimingDeviceEvents.clear()
        shell("cmd notification disallow_listener ${listener.flattenToString()}")
    }

    @Test
    fun recordsNotifyAndListenerPostForExternalHapticVisualCorrelation() {
        val update = update()
        val expected = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        NotificationTimingDeviceEvents.arm(context.packageName, expected.tag)
        val probe = TimingProbe()
        val presenter = timingPresenter(probe)
        presenter.ensureChannels()

        val preparationStartedNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("WN notification preparation")
        val posted =
            try {
                runBlocking {
                    presenter.show(
                        update = update,
                        shortNpub = { "npub1timing" },
                    )
                }
            } finally {
                Trace.endSection()
            }
        val listenerPost =
            checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS)) {
                "Notification listener did not observe the timing probe"
            }

        try {
            assertTrue(posted)
            assertEquals(expected.tag, listenerPost.tag)
            assertEquals(expected.id, listenerPost.id)
            assertTrue(probe.notifyElapsedRealtimeNanos >= preparationStartedNanos)
            assertTrue(listenerPost.elapsedRealtimeNanos >= probe.notifyElapsedRealtimeNanos)
            assertTrue(
                "notify-to-listener delivery exceeded the evidence ceiling",
                listenerPost.elapsedRealtimeNanos - probe.notifyElapsedRealtimeNanos <=
                    TimeUnit.MILLISECONDS.toNanos(MAX_NOTIFY_TO_LISTENER_MS),
            )
            assertNotNull(probe.postedNotification)
            reportTiming(preparationStartedNanos, probe.notifyElapsedRealtimeNanos, listenerPost)
        } finally {
            NotificationManagerCompat.from(context).cancel(expected.tag, expected.id)
        }
    }

    private fun timingPresenter(probe: TimingProbe): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            shortcutPublisher = { },
            notificationPoster = { manager, tag, id, notification ->
                Trace.beginSection("WN notification notify")
                try {
                    probe.notifyElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    probe.postedNotification = notification
                    manager.notify(tag, id, notification)
                } finally {
                    Trace.endSection()
                }
            },
        )

    private fun reportTiming(
        preparationStartedNanos: Long,
        notifyElapsedRealtimeNanos: Long,
        listenerPost: NotificationTimingListenerPost,
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putLong(
                    "notification_preparation_ms",
                    TimeUnit.NANOSECONDS.toMillis(notifyElapsedRealtimeNanos - preparationStartedNanos),
                )
                putLong(
                    "notify_to_listener_post_ms",
                    TimeUnit.NANOSECONDS.toMillis(
                        listenerPost.elapsedRealtimeNanos - notifyElapsedRealtimeNanos,
                    ),
                )
                putString(
                    "measurement_scope",
                    "App preparation and framework-listener delivery only; not physical haptic or rendered pixels",
                )
            },
        )
    }

    private data class TimingProbe(
        var notifyElapsedRealtimeNanos: Long = Long.MIN_VALUE,
        var postedNotification: Notification? = null,
    )

    private fun update(): NotificationUpdateFfi =
        NotificationUpdateFfi(
            notificationKey = "timing-device-test",
            conversationKey = "timing-conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "timing-account",
            accountIdHex = "timing-account",
            groupIdHex = "timing-group",
            groupName = "Timing group",
            isDm = false,
            isMention = false,
            messageIdHex = "timing-message",
            sender = user("timing-sender", "Timing sender"),
            receiver = user("timing-receiver", "Timing receiver"),
            previewText = "Notification timing probe",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = System.currentTimeMillis(),
            isFromSelf = false,
        )

    private fun user(
        accountIdHex: String,
        displayName: String,
    ): NotificationUserFfi =
        NotificationUserFfi(
            accountIdHex = accountIdHex,
            displayName = displayName,
            pictureUrl = null,
        )

    private fun shell(command: String) {
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).use { output ->
                output.readBytes()
            }
    }

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
        const val LISTENER_CONNECT_TIMEOUT_MS = 5_000L
        const val LISTENER_POST_TIMEOUT_MS = 5_000L
        const val MAX_NOTIFY_TO_LISTENER_MS = 2_000L
    }
}
