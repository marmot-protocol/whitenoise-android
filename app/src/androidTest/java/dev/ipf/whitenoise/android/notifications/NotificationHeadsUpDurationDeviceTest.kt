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
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
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
 * Records a synthetic first post and one same-key write for external heads-up frame review.
 *
 * Arms: `initial_only` makes no second write, `enrich_same_key` releases deferred avatar enrichment,
 * `second_message` adds a message to the live card inside the burst window, `late_correction` replaces the
 * message text silently, and `invite_refresh` rewrites an invite's sender identity. Every second write must
 * reach the platform as an update that keeps the first card's group alert behavior and rank.
 *
 * The default target is a disposable API 30 emulator. Physical API 37 runs require
 * a separate explicit opt-in and the isolated local preview package. Listener
 * callbacks establish card lifecycle, never rendered banner duration.
 */
@ManualDeviceFixture
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
            "Select one of $CONTROLLED_ENRICHMENT_MODES",
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

    /** Records the same initial hold and observation window in every independently selected arm. */
    @Test
    fun controlledEnrichmentTimelineForExternalCapture() {
        val mode = checkNotNull(arguments.getString(ARG_CONTROLLED_ENRICHMENT))
        val update = if (mode == MODE_INVITE_REFRESH) inviteUpdate() else update()
        val target = LocalNotificationFormatter.notificationDismissalKey(update)
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
                if (mode == MODE_INITIAL_ONLY) {
                    null
                } else {
                    performSameKeyWrite(mode, presenter, probe, update)
                    requireFrameworkPost().also { assertDeliveryContract(probe, target, firstPost, it) }
                }
            val observationMs = observationWindowMillis()
            val naturalRemoval = NotificationTimingDeviceEvents.awaitRemoval(observationMs)
            assertEquals(if (mode == MODE_INITIAL_ONLY) 1 else 2, probe.appPosts.size)
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

    /** Performs the one same-key write the selected arm exercises, after the initial banner has been held. */
    private fun performSameKeyWrite(
        mode: String,
        presenter: LocalNotificationPresenter,
        probe: HeadsUpProbeState,
        update: NotificationUpdateFfi,
    ) {
        when (mode) {
            MODE_ENRICH_SAME_KEY -> runBlocking { probe.postProbe.enrichment.releaseAll() }
            MODE_SECOND_MESSAGE ->
                show(presenter, update.copy(messageIdHex = "${update.messageIdHex}-second"), "Second probe message")
            MODE_LATE_CORRECTION ->
                show(presenter, update, "Corrected probe message", silentUpdate = true, replaceCurrentMessage = true)
            MODE_INVITE_REFRESH ->
                show(presenter, update, previewText = null, senderName = "Resolved probe sender", silentUpdate = true)
            else -> error("Unknown heads-up arm $mode")
        }
    }

    /** Shows one synthetic update and requires that a card was written. */
    private fun show(
        presenter: LocalNotificationPresenter,
        update: NotificationUpdateFfi,
        previewText: String?,
        senderName: String? = null,
        silentUpdate: Boolean = false,
        replaceCurrentMessage: Boolean = false,
    ) {
        assertTrue(
            runBlocking {
                presenter.show(
                    update = update,
                    previewTextOverride = previewText,
                    senderNameOverride = senderName,
                    silentUpdate = silentUpdate,
                    replaceCurrentMessage = replaceCurrentMessage,
                    shortNpub = { "npub1timing" },
                )
            },
        )
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
                    probe.appPosts +=
                        AppNotificationPost(
                            tag,
                            id,
                            notification,
                            SystemClock.elapsedRealtimeNanos(),
                            probe.postProbe.record(tag, id, notification),
                        )
                    manager.notify(tag, id, notification)
                } finally {
                    Trace.endSection()
                }
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = { url -> if (url == null) null else avatar },
            enrichmentLauncher = probe.postProbe.enrichmentLauncher,
        )
    }

    /**
     * Requires one stable card that suppresses repeat alerts without leaving its alerting group or rank,
     * since SystemUI withdraws a showing banner when an update stops being heads-up eligible.
     */
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
        val (initial, update) = probe.appPosts.map { it.recorded }
        assertFalse("The first post must not be silenced", initial.silent)
        assertFalse("A write over the live card must not be silenced", update.silent)
        assertEquals(initial.groupAlertBehavior, update.groupAlertBehavior)
        assertEquals(initial.sortKey, update.sortKey)
        assertEquals(initial.group, update.group)
        assertEquals(initial.channelId, update.channelId)
        assertTrue(update.carriesNoPerPostSoundOrVibration)
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
        return notificationUpdate(
            notificationKey = "heads-up-device-test-$runToken",
            conversationKey = "heads-up-conversation-$runToken",
            accountRef = "heads-up-account",
            groupIdHex = runToken,
            groupName = "Heads-up group",
            messageIdHex = runToken,
            sender = notificationUser("heads-up-sender", "Heads-up sender"),
            receiver = notificationUser("heads-up-receiver", "Heads-up receiver"),
            previewText = "Notification timing probe",
            timestampMs = System.currentTimeMillis(),
        )
    }

    /** Builds an isolated synthetic group invite, keyed by the invite so its card has its own opaque tag. */
    private fun inviteUpdate(): NotificationUpdateFfi {
        val runToken = SystemClock.elapsedRealtimeNanos().toString(radix = 16)
        return groupInviteUpdate(
            accountRef = "heads-up-account",
            groupIdHex = runToken,
            notificationKey = "heads-up-invite-$runToken",
            sender = notificationUser("heads-up-sender", null),
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
        const val MODE_INITIAL_ONLY = "initial_only"
        const val MODE_ENRICH_SAME_KEY = "enrich_same_key"
        const val MODE_SECOND_MESSAGE = "second_message"
        const val MODE_LATE_CORRECTION = "late_correction"
        const val MODE_INVITE_REFRESH = "invite_refresh"
        val CONTROLLED_ENRICHMENT_MODES =
            setOf(
                MODE_INITIAL_ONLY,
                MODE_ENRICH_SAME_KEY,
                MODE_SECOND_MESSAGE,
                MODE_LATE_CORRECTION,
                MODE_INVITE_REFRESH,
            )

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
        putInt("first_post_group_alert_behavior", appPosts.first().recorded.groupAlertBehavior)
        putInt("second_post_group_alert_behavior", appPosts.getOrNull(1)?.recorded?.groupAlertBehavior ?: -1)
        putString("first_post_sort_key", appPosts.first().recorded.sortKey)
        putString("second_post_sort_key", appPosts.getOrNull(1)?.recorded?.sortKey)
        putLong("controlled_initial_hold_ms", 1_500L)
        putLong("controlled_observation_ms", observationMs)
        putLong("app_cleanup_cancel_elapsed_ns", cleanup.appCancelNanos)
        putLong("cleanup_removal_elapsed_ns", cleanup.removal.elapsedRealtimeNanos)
        putInt("cleanup_removal_reason", cleanup.removal.reason)
        putString("measurement_scope", "Controlled app timeline; visible duration requires external frame review")
    }

/** One observed app write with its monotonic time and the flags the platform will see. */
private data class AppNotificationPost(
    val tag: String,
    val id: Int,
    val notification: Notification,
    val elapsedRealtimeNanos: Long,
    val recorded: RecordedPost,
)

/** Observed app writes plus the shared probe that records their alert flags and gates enrichment. */
private class HeadsUpProbeState {
    val appPosts = mutableListOf<AppNotificationPost>()
    val postProbe = PostProbe(deliver = false)
}

private data class CleanupObservation(
    val appCancelNanos: Long,
    val removal: NotificationTimingListenerRemoval,
)
