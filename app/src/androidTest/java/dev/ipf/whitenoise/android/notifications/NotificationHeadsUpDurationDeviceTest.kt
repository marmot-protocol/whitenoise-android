package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.os.Trace
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records a synthetic initial-only/avatar-enrichment pair for external heads-up frame review.
 *
 * The default target is a disposable API 30 emulator. Physical API 37 runs require
 * a separate explicit opt-in and the isolated local preview package. Listener
 * callbacks establish card lifecycle, never rendered banner duration.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.R, maxSdkVersion = 37)
class NotificationHeadsUpDurationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val listener = ComponentName(context, NotificationTimingListenerService::class.java)
    private var listenerProvisioned = false

    /** Checks every target and permission precondition before moving Home or provisioning listener access. */
    @Before
    fun provisionNotificationAccess() {
        assumeTrue(
            "Heads-up probing requires -e $ARG_ALLOW_HEADS_UP_PROBE true",
            arguments.getString(ARG_ALLOW_HEADS_UP_PROBE) == "true",
        )
        assumeTrue(
            "Select initial_only or enrich_same_key",
            arguments.getString(ARG_CONTROLLED_ENRICHMENT) in CONTROLLED_ENRICHMENT_MODES,
        )
        requireSupportedTarget()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        assertTrue("The selected test device screen must already be on", powerManager.isInteractive)
        assertFalse("The selected test device must already be unlocked", keyguardManager.isKeyguardLocked)
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(HOME_SETTLE_MS)

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

    /** Rejects physical production/dev installs and leaves runtime permission ownership to the external driver. */
    private fun requireSupportedTarget() {
        if (shell("getprop ro.kernel.qemu").trim() == "1") {
            assumeTrue("The disposable emulator must run API 30", Build.VERSION.SDK_INT == Build.VERSION_CODES.R)
            return
        }
        assumeTrue(
            "Physical probing requires -e $ARG_ALLOW_PHYSICAL_HEADS_UP_PROBE true",
            arguments.getString(ARG_ALLOW_PHYSICAL_HEADS_UP_PROBE) == "true",
        )
        assumeTrue("Physical probing requires API 37", Build.VERSION.SDK_INT == 37)
        assumeTrue("Physical probing requires the isolated local preview", context.packageName == ISOLATED_PACKAGE)
        assertEquals(
            "Grant notification permission to the isolated preview before running; the driver owns restoration",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    /** Drops synthetic events and revokes only listener access provisioned by this fixture. */
    @After
    fun revokeNotificationAccess() {
        NotificationTimingDeviceEvents.clear()
        if (listenerProvisioned) {
            shell("cmd notification disallow_listener ${listener.flattenToString()}")
            listenerProvisioned = false
        }
    }

    /** Records the same initial hold and observation window in both independently selected modes. */
    @Test
    fun controlledEnrichmentTimelineForExternalCapture() {
        val mode = checkNotNull(arguments.getString(ARG_CONTROLLED_ENRICHMENT))
        val update = update()
        val target = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        NotificationTimingDeviceEvents.arm(context.packageName, target.tag, target.id)
        val probe = HeadsUpProbeState()
        val presenter = presenter(probe)
        presenter.ensureChannels()
        try {
            val firstPost = postControlledInitial(presenter, update)
            instrumentation.sendStatus(0, controlledPhaseStatus("posted", firstPost.key))
            val initialAppPost = probe.appPosts.single()
            assertEquals(target.tag, initialAppPost.tag)
            assertEquals(target.id, initialAppPost.id)
            assertEquals(0, initialAppPost.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            Thread.sleep(CONTROLLED_ENRICHMENT_DELAY_MS)
            instrumentation.sendStatus(0, controlledPhaseStatus("intervention", firstPost.key))
            val secondPost =
                if (mode == "enrich_same_key") {
                    runBlocking { checkNotNull(probe.pendingEnrichment).invoke() }
                    requireFrameworkPost().also { assertDeliveryContract(probe, target, firstPost, it) }
                } else {
                    null
                }
            val observationMs = observationWindowMillis()
            val naturalRemoval = NotificationTimingDeviceEvents.awaitRemoval(observationMs)
            assertEquals(if (mode == "enrich_same_key") 2 else 1, probe.appPosts.size)
            assertTrue(
                context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.key == firstPost.key
                },
            )
            assertNull("The shade card must survive heads-up removal", naturalRemoval)
            val cleanup = cancelAfterObservation(target)
            assertEquals(firstPost.key, cleanup.removal.key)
            assertEquals(NotificationListenerService.REASON_APP_CANCEL, cleanup.removal.reason)
            assertTrue(cleanup.removal.elapsedRealtimeNanos >= cleanup.appCancelNanos)
            instrumentation.sendStatus(
                0,
                probe.controlledEvidenceStatus(mode, firstPost, secondPost, cleanup, observationMs),
            )
            assumeTrue(
                "Inconclusive until external capture confirms the initial banner and controlled transition",
                false,
            )
        } finally {
            NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        }
    }

    /** Posts identical synthetic content while keeping optional enrichment explicitly gated. */
    private fun postControlledInitial(
        presenter: LocalNotificationPresenter,
        update: NotificationUpdateFfi,
    ): NotificationTimingListenerPost {
        assertTrue(
            runBlocking {
                presenter.show(
                    update = update,
                    previewTextOverride = "Notification timing probe",
                    senderAvatarUrl = "https://example.test/sender.png",
                    shortNpub = { "npub1timing" },
                )
            },
        )
        return requireFrameworkPost()
    }

    /** Creates a real presenter with deterministic synthetic avatars and observable notify boundaries. */
    private fun presenter(probe: HeadsUpProbeState): LocalNotificationPresenter {
        val avatar = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return LocalNotificationPresenter(
            context = context,
            shortcutPublisher = { },
            notificationPoster = { manager, tag, id, notification ->
                Trace.beginSection("WN heads-up app notify")
                try {
                    probe.appPosts += AppNotificationPost(tag, id, notification, SystemClock.elapsedRealtimeNanos())
                    manager.notify(tag, id, notification)
                } finally {
                    Trace.endSection()
                }
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = { url -> if (url == null) null else avatar },
            enrichmentLauncher = { probe.pendingEnrichment = it },
        )
    }

    /** Requires one stable card and suppresses repeat alerts while preserving callback ordering. */
    private fun assertDeliveryContract(
        probe: HeadsUpProbeState,
        target: NotificationDismissalKey,
        firstPost: NotificationTimingListenerPost,
        secondPost: NotificationTimingListenerPost,
    ) {
        assertEquals(2, probe.appPosts.size)
        assertEquals(target.tag, probe.appPosts[0].tag)
        assertEquals(target.id, probe.appPosts[0].id)
        assertEquals(probe.appPosts[0].tag, probe.appPosts[1].tag)
        assertEquals(probe.appPosts[0].id, probe.appPosts[1].id)
        assertEquals(0, probe.appPosts[0].notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertTrue(probe.appPosts[1].notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(firstPost.elapsedRealtimeNanos >= probe.appPosts[0].elapsedRealtimeNanos)
        assertTrue(secondPost.elapsedRealtimeNanos >= probe.appPosts[1].elapsedRealtimeNanos)
        assertEquals(firstPost.key, secondPost.key)
    }

    /** Cancels only the synthetic card after observation and requires its framework cleanup callback. */
    private fun cancelAfterObservation(target: NotificationDismissalKey): CleanupObservation {
        val appCancelNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("WN heads-up test cleanup cancel")
        try {
            NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        } finally {
            Trace.endSection()
        }
        return CleanupObservation(
            appCancelNanos,
            checkNotNull(NotificationTimingDeviceEvents.awaitRemoval(LISTENER_EVENT_TIMEOUT_MS)),
        )
    }

    /** Waits for one exact-key framework post without interpreting the callback as visible pixels. */
    private fun requireFrameworkPost(): NotificationTimingListenerPost =
        checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_EVENT_TIMEOUT_MS)) {
            "Notification listener did not observe the expected post/update"
        }

    /** Bounds the untouched observation interval independently of listener connection readiness. */
    private fun observationWindowMillis(): Long =
        arguments
            .getString(ARG_OBSERVATION_WINDOW_MS)
            ?.toLongOrNull()
            ?.coerceIn(5_000L, 15_000L) ?: 8_000L

    /** Builds an isolated synthetic group whose identifiers are safe to export as diagnostics. */
    private fun update(): NotificationUpdateFfi {
        val runToken = SystemClock.elapsedRealtimeNanos().toString(radix = 16)
        return NotificationUpdateFfi(
            notificationKey = "heads-up-device-test-$runToken",
            conversationKey = "heads-up-conversation-$runToken",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "heads-up-account",
            accountIdHex = "heads-up-account",
            groupIdHex = runToken,
            groupName = "Heads-up group",
            isDm = false,
            isMention = false,
            messageIdHex = runToken,
            sender = NotificationUserFfi("heads-up-sender", "Heads-up sender", null),
            receiver = NotificationUserFfi("heads-up-receiver", "Heads-up receiver", null),
            previewText = "Notification timing probe",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = System.currentTimeMillis(),
            isFromSelf = false,
        )
    }

    /** Executes a scoped test shell command and closes its returned descriptor. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private companion object {
        const val ARG_ALLOW_HEADS_UP_PROBE = "allowHeadsUpProbe"
        const val ARG_ALLOW_PHYSICAL_HEADS_UP_PROBE = "allowPhysicalHeadsUpProbe"
        const val ARG_OBSERVATION_WINDOW_MS = "headsUpObservationMs"
        const val ARG_CONTROLLED_ENRICHMENT = "headsUpControlledEnrichment"
        const val ISOLATED_PACKAGE = "dev.ipf.whitenoise.android.preview.prlocal"
        val CONTROLLED_ENRICHMENT_MODES = setOf("initial_only", "enrich_same_key")

        /** Allows Android 11's ten-second rebind delay after instrumentation restarts a bound listener. */
        const val LISTENER_CONNECT_TIMEOUT_MS = 15_000L

        const val LISTENER_EVENT_TIMEOUT_MS = 5_000L
        const val CONTROLLED_ENRICHMENT_DELAY_MS = 1_500L
        const val HOME_SETTLE_MS = 500L
    }
}

/** Polls monotonic time without Activity or Compose synchronization. */
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

/** Emits a synthetic identity and monotonic phase boundary for external frame alignment. */
private fun controlledPhaseStatus(
    phase: String,
    key: String,
): Bundle =
    Bundle().apply {
        putString("controlled_phase", phase)
        putString("synthetic_framework_key", key)
        putLong("controlled_phase_elapsed_ns", SystemClock.elapsedRealtimeNanos())
    }

/** Reports observed card lifecycle after cleanup without claiming a visible duration or audible alert count. */
private fun HeadsUpProbeState.controlledEvidenceStatus(
    mode: String,
    firstPost: NotificationTimingListenerPost,
    secondPost: NotificationTimingListenerPost?,
    cleanup: CleanupObservation,
    observationMs: Long,
): Bundle =
    Bundle().apply {
        putString("controlled_mode", mode)
        putInt("app_notify_count", appPosts.size)
        putLong("first_app_notify_elapsed_ns", appPosts.first().elapsedRealtimeNanos)
        putLong("second_app_notify_elapsed_ns", appPosts.getOrNull(1)?.elapsedRealtimeNanos ?: -1L)
        putLong("first_listener_post_elapsed_ns", firstPost.elapsedRealtimeNanos)
        putLong("second_listener_post_elapsed_ns", secondPost?.elapsedRealtimeNanos ?: -1L)
        putBoolean(
            "second_post_uses_silent_group",
            appPosts.getOrNull(1)?.notification?.group == NotificationCompat.GROUP_KEY_SILENT,
        )
        putLong("controlled_initial_hold_ms", 1_500L)
        putLong("controlled_observation_ms", observationMs)
        putLong("app_cleanup_cancel_elapsed_ns", cleanup.appCancelNanos)
        putLong("cleanup_removal_elapsed_ns", cleanup.removal.elapsedRealtimeNanos)
        putInt("cleanup_removal_reason", cleanup.removal.reason)
        putString("measurement_scope", "Controlled app timeline; visible duration requires external frame review")
    }

private data class AppNotificationPost(
    val tag: String,
    val id: Int,
    val notification: Notification,
    val elapsedRealtimeNanos: Long,
)

private class HeadsUpProbeState {
    val appPosts = mutableListOf<AppNotificationPost>()
    var pendingEnrichment: (suspend () -> Unit)? = null
}

private data class CleanupObservation(
    val appCancelNanos: Long,
    val removal: NotificationTimingListenerRemoval,
)
