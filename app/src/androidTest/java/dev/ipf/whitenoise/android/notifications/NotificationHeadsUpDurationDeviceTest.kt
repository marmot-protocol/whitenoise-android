package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
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
 * Privacy-safe framework evidence companion for issue #2412.
 *
 * The opted-in API 30 emulator probe leaves its synthetic notification active
 * during a natural observation window. It separately records notification-card
 * removal and SystemUI heads-up diagnostics because the former does not prove
 * when the banner collapses. Missing reason-bearing SystemUI evidence makes the
 * run inconclusive rather than successful.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.R, maxSdkVersion = Build.VERSION_CODES.R)
class NotificationHeadsUpDurationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val listener = ComponentName(context, NotificationTimingListenerService::class.java)
    private var listenerProvisioned = false

    /** Provisions the diagnostic listener only after explicit API 30 emulator opt-in. */
    @Before
    fun provisionNotificationAccess() {
        assumeTrue(
            "Heads-up probing requires -e $ARG_ALLOW_HEADS_UP_PROBE true",
            arguments.getString(ARG_ALLOW_HEADS_UP_PROBE) == "true",
        )
        assumeTrue(
            "Heads-up probing is restricted to a disposable emulator",
            shell("getprop ro.kernel.qemu").trim() == "1",
        )
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        assertTrue("The task emulator screen must already be on", powerManager.isInteractive)
        assertFalse("The task emulator must already be unlocked", keyguardManager.isKeyguardLocked)
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(HOME_SETTLE_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        NotificationTimingDeviceEvents.listenerConnected = false
        shell("cmd notification allow_listener ${listener.flattenToString()}")
        listenerProvisioned = true
        NotificationListenerService.requestRebind(listener)
        assertTrue(
            "Debug notification listener did not connect; verify notification-listener access on the device",
            waitUntil(LISTENER_CONNECT_TIMEOUT_MS) { NotificationTimingDeviceEvents.listenerConnected },
        )
    }

    /** Drops synthetic events and revokes only access provisioned by this fixture. */
    @After
    fun revokeNotificationAccess() {
        NotificationTimingDeviceEvents.clear()
        if (listenerProvisioned) {
            shell("cmd notification disallow_listener ${listener.flattenToString()}")
            listenerProvisioned = false
        }
    }

    /**
     * Observes natural SystemUI lifetime before cancelling the still-active
     * shade card and verifying the cleanup callback is `REASON_APP_CANCEL`.
     */
    @Test
    fun coldAvatarEnrichmentLeavesCardActiveDuringNaturalHeadsUpObservation() {
        val update = update()
        val target = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        NotificationTimingDeviceEvents.arm(context.packageName, target.tag, target.id)
        val systemUiBaseline = discoverSystemUiLogSnapshot()
        val probe = HeadsUpProbeState()
        val presenter = presenter(probe)
        presenter.ensureChannels()

        try {
            val delivery = postAndEnrich(presenter, probe, update)
            assertDeliveryContract(probe, target, delivery)
            val observation =
                observeNaturalLifetime(
                    key = delivery.firstFrameworkPost.key,
                    target = target,
                    channelId = checkNotNull(probe.appPosts[0].notification.channelId),
                    prePostBaseline = systemUiBaseline,
                )
            val cleanup = cancelAfterObservation(target, observation.naturalRemoval)
            val report = HeadsUpProbeReport(probe, delivery, observation, cleanup)
            reportEvidence(report)
            assertObservationContract(report)
        } finally {
            NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        }
    }

    /** Creates a real presenter while retaining only timing and deferred-enrichment state. */
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
                            tag = tag,
                            id = id,
                            notification = notification,
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        )
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

    /** Posts the alerting card, executes its deferred avatar enrichment, and records both callbacks. */
    private fun postAndEnrich(
        presenter: LocalNotificationPresenter,
        probe: HeadsUpProbeState,
        update: NotificationUpdateFfi,
    ): FrameworkDelivery {
        val posted =
            runBlocking {
                presenter.show(
                    update = update,
                    previewTextOverride = "Notification timing probe",
                    senderAvatarUrl = "https://example.test/sender.png",
                    shortNpub = { "npub1timing" },
                )
            }
        val firstFrameworkPost = requireFrameworkPost()
        runBlocking { checkNotNull(probe.pendingEnrichment).invoke() }
        return FrameworkDelivery(
            posted = posted,
            firstFrameworkPost = firstFrameworkPost,
            secondFrameworkPost = requireFrameworkPost(),
        )
    }

    /** Verifies stable-card delivery while leaving silent-group behavior as measured evidence. */
    private fun assertDeliveryContract(
        probe: HeadsUpProbeState,
        target: NotificationDismissalKey,
        delivery: FrameworkDelivery,
    ) {
        assertTrue(delivery.posted)
        assertEquals(2, probe.appPosts.size)
        assertEquals(target.tag, probe.appPosts[0].tag)
        assertEquals(target.id, probe.appPosts[0].id)
        assertEquals(probe.appPosts[0].tag, probe.appPosts[1].tag)
        assertEquals(probe.appPosts[0].id, probe.appPosts[1].id)
        assertEquals(0, probe.appPosts[0].notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertTrue(probe.appPosts[1].notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(delivery.firstFrameworkPost.elapsedRealtimeNanos >= probe.appPosts[0].elapsedRealtimeNanos)
        assertTrue(delivery.secondFrameworkPost.elapsedRealtimeNanos >= probe.appPosts[1].elapsedRealtimeNanos)
        assertEquals(delivery.firstFrameworkPost.key, delivery.secondFrameworkPost.key)
    }

    /** Leaves the card untouched for the natural window, then samples framework and SystemUI state. */
    private fun observeNaturalLifetime(
        key: String,
        target: NotificationDismissalKey,
        channelId: String,
        prePostBaseline: SystemUiLogSnapshot,
    ): NaturalObservation {
        val initialHeadsUpDump = captureHeadsUpDump(key, prePostBaseline.statusBarDumpable)
        val observationBaseline = refreshSystemUiLogSnapshot(prePostBaseline)
        val observationWindowMs = observationWindowMillis()
        val naturalRemoval: NotificationTimingListenerRemoval?
        Trace.beginSection("WN heads-up natural observation")
        try {
            naturalRemoval = NotificationTimingDeviceEvents.awaitRemoval(observationWindowMs)
        } finally {
            Trace.endSection()
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channelImportance = notificationManager.getNotificationChannel(channelId)?.importance ?: -1
        val cardStillActive =
            notificationManager.activeNotifications.any {
                it.tag == target.tag && it.id == target.id && it.key == key
            }
        return NaturalObservation(
            observationWindowMs = observationWindowMs,
            naturalRemoval = naturalRemoval,
            cardStillActive = cardStillActive,
            systemUiEvidence =
                captureSystemUiEvidence(
                    key = key,
                    prePostBaseline = prePostBaseline,
                    observationBaseline = observationBaseline,
                    initialDumpLines = initialHeadsUpDump,
                    channelImportance = channelImportance,
                ),
            channelImportance = channelImportance,
        )
    }

    /** Cancels only after evidence capture and waits for the framework app-cancel removal reason. */
    private fun cancelAfterObservation(
        target: NotificationDismissalKey,
        naturalRemoval: NotificationTimingListenerRemoval?,
    ): CleanupObservation {
        val appCancelNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("WN heads-up test cleanup cancel")
        try {
            NotificationManagerCompat.from(context).cancel(target.tag, target.id)
        } finally {
            Trace.endSection()
        }
        val removal =
            if (naturalRemoval == null) {
                NotificationTimingDeviceEvents.awaitRemoval(LISTENER_EVENT_TIMEOUT_MS)
            } else {
                null
            }
        return CleanupObservation(appCancelNanos, removal)
    }

    /** Requires card retention and cleanup ownership before accepting or skipping SystemUI evidence. */
    private fun assertObservationContract(report: HeadsUpProbeReport) {
        assertNull(
            "A heads-up collapse must not remove the notification shade card",
            report.observation.naturalRemoval,
        )
        assertTrue(
            "The synthetic shade card disappeared during the observation window",
            report.observation.cardStillActive,
        )
        val cleanupRemoval =
            checkNotNull(report.cleanup.removal) {
                "Notification listener did not observe explicit test cleanup"
            }
        assertEquals(report.delivery.firstFrameworkPost.key, cleanupRemoval.key)
        assertEquals(NotificationListenerService.REASON_APP_CANCEL, cleanupRemoval.reason)
        assertTrue(cleanupRemoval.elapsedRealtimeNanos >= report.cleanup.appCancelNanos)
        val evidence = report.observation.systemUiEvidence
        assumeTrue(evidence.inconclusiveReason, evidence.isConclusive)
    }

    /** Waits for one exact-key framework post or fails with a bounded diagnostic. */
    private fun requireFrameworkPost(): NotificationTimingListenerPost =
        checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_EVENT_TIMEOUT_MS)) {
            "Notification listener did not observe the expected post/update"
        }

    /** Emits only synthetic identifiers, monotonic times, and exact-key SystemUI lines. */
    private fun reportEvidence(report: HeadsUpProbeReport) {
        val delivery = report.delivery
        val observation = report.observation
        val cleanup = report.cleanup
        val systemUiEvidence = observation.systemUiEvidence
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("app_notify_count", report.probe.appPosts.size)
                putLong("first_app_notify_elapsed_ns", report.probe.appPosts[0].elapsedRealtimeNanos)
                putLong("first_listener_post_elapsed_ns", delivery.firstFrameworkPost.elapsedRealtimeNanos)
                putLong("second_app_notify_elapsed_ns", report.probe.appPosts[1].elapsedRealtimeNanos)
                putLong("second_listener_post_elapsed_ns", delivery.secondFrameworkPost.elapsedRealtimeNanos)
                putBoolean(
                    "second_post_uses_silent_group",
                    report.probe
                        .appPosts[1]
                        .notification.group == NotificationCompat.GROUP_KEY_SILENT,
                )
                putString(
                    "second_post_group_key",
                    report.probe
                        .appPosts[1]
                        .notification.group
                        .orEmpty(),
                )
                putInt(
                    "second_post_group_alert_behavior",
                    report.probe
                        .appPosts[1]
                        .notification.groupAlertBehavior,
                )
                putLong("natural_observation_ms", observation.observationWindowMs)
                putInt("notification_channel_importance", observation.channelImportance)
                putBoolean("card_active_after_observation", observation.cardStillActive)
                putLong("natural_card_removal_elapsed_ns", observation.naturalRemoval?.elapsedRealtimeNanos ?: -1L)
                putInt("natural_card_removal_reason", observation.naturalRemoval?.reason ?: -1)
                putString("heads_up_initial_dump_lines", systemUiEvidence.initialDumpLines.asStatusText())
                putString("heads_up_final_dump_lines", systemUiEvidence.finalDumpLines.asStatusText())
                putString("heads_up_statusbar_dumpable", systemUiEvidence.statusBarDumpable.orEmpty())
                putString("heads_up_modern_log_dumpable", systemUiEvidence.modernLogDumpable.orEmpty())
                putString("heads_up_post_api30_event_lines", systemUiEvidence.postEventLines.asStatusText())
                putString("heads_up_natural_api30_event_lines", systemUiEvidence.naturalEventLines.asStatusText())
                putString("heads_up_post_modern_log_lines", systemUiEvidence.postModernLogLines.asStatusText())
                putString("heads_up_natural_modern_log_lines", systemUiEvidence.naturalModernLogLines.asStatusText())
                putBoolean("heads_up_evidence_conclusive", systemUiEvidence.isConclusive)
                putString("heads_up_evidence_note", systemUiEvidence.inconclusiveReason)
                putLong("app_cleanup_cancel_elapsed_ns", cleanup.appCancelNanos)
                putLong("cleanup_removal_elapsed_ns", cleanup.removal?.elapsedRealtimeNanos ?: -1L)
                putInt("cleanup_removal_reason", cleanup.removal?.reason ?: -1)
                putString(
                    "measurement_scope",
                    "Synthetic card lifecycle plus filtered SystemUI diagnostics; NLS removal is not heads-up collapse",
                )
            },
        )
    }

    /** Discovers only supported StatusBar and reason-capable HUN dump targets before posting. */
    private fun discoverSystemUiLogSnapshot(): SystemUiLogSnapshot {
        val registeredDumpables =
            shell("$SYSTEM_UI_DUMP_COMMAND --list")
                .lineSequence()
                .map(String::trim)
                .toSet()
        val statusBarDumpable = STATUS_BAR_DUMPABLES.firstOrNull { it in registeredDumpables }
        val modernLogDumpable =
            registeredDumpables.firstOrNull { dumpable ->
                dumpable == MODERN_HEADS_UP_LOG_NAME || dumpable.endsWith(".$MODERN_HEADS_UP_LOG_NAME")
            }
        return captureSystemUiLogSnapshot(statusBarDumpable, modernLogDumpable)
    }

    /** Refreshes the same discovered buffers without accepting a different runtime target. */
    private fun refreshSystemUiLogSnapshot(baseline: SystemUiLogSnapshot): SystemUiLogSnapshot =
        captureSystemUiLogSnapshot(baseline.statusBarDumpable, baseline.modernLogDumpable)

    /** Captures event/log buffers while retaining the pre-post dumpable selection. */
    private fun captureSystemUiLogSnapshot(
        statusBarDumpable: String?,
        modernLogDumpable: String?,
    ): SystemUiLogSnapshot =
        SystemUiLogSnapshot(
            statusBarDumpable = statusBarDumpable,
            modernLogDumpable = modernLogDumpable,
            api30EventLines = shell(API30_HEADS_UP_EVENT_COMMAND).lineSequence().toList(),
            modernLogLines =
                modernLogDumpable
                    ?.let { shell("$SYSTEM_UI_DUMP_COMMAND $it --tail 200").lineSequence().toList() }
                    .orEmpty(),
        )

    /** Extracts exact-key rows only from the nested HeadsUpManagerPhone StatusBar block. */
    private fun captureHeadsUpDump(
        key: String,
        statusBarDumpable: String?,
    ): List<String> {
        if (statusBarDumpable == null) return emptyList()
        val statusBarDump = shell("$SYSTEM_UI_DUMP_COMMAND $statusBarDumpable")
        return HeadsUpSystemUiDiagnostics.exactTargetLines(statusBarDump, key)
    }

    /** Correlates exact-key API 30 and newer SystemUI evidence without exporting unrelated entries. */
    private fun captureSystemUiEvidence(
        key: String,
        prePostBaseline: SystemUiLogSnapshot,
        observationBaseline: SystemUiLogSnapshot,
        initialDumpLines: List<String>,
        channelImportance: Int,
    ): SystemUiHeadsUpEvidence {
        val finalDumpLines = captureHeadsUpDump(key, prePostBaseline.statusBarDumpable)
        val finalSnapshot = refreshSystemUiLogSnapshot(prePostBaseline)
        val deltas = captureSystemUiLogDeltas(key, prePostBaseline, observationBaseline, finalSnapshot)
        val incompleteReasons =
            systemUiEvidenceGaps(deltas, prePostBaseline, initialDumpLines, finalDumpLines, channelImportance)
        val inconclusiveReason =
            if (incompleteReasons.isEmpty()) {
                "SystemUI evidence is conclusive"
            } else {
                incompleteReasons.joinToString(
                    separator = "; ",
                    prefix = "Inconclusive SystemUI evidence: ",
                )
            }
        return SystemUiHeadsUpEvidence(
            initialDumpLines = initialDumpLines,
            finalDumpLines = finalDumpLines,
            statusBarDumpable = prePostBaseline.statusBarDumpable,
            modernLogDumpable = prePostBaseline.modernLogDumpable,
            postEventLines = deltas.postEvent.lines,
            naturalEventLines = deltas.naturalEvent.lines,
            postModernLogLines = deltas.postModern.lines,
            naturalModernLogLines = deltas.naturalModern.lines,
            isConclusive = incompleteReasons.isEmpty(),
            inconclusiveReason = inconclusiveReason,
        )
    }

    /** Separates initial-post activity from transitions occurring during the untouched window. */
    private fun captureSystemUiLogDeltas(
        key: String,
        prePost: SystemUiLogSnapshot,
        observation: SystemUiLogSnapshot,
        final: SystemUiLogSnapshot,
    ): SystemUiLogDeltas =
        SystemUiLogDeltas(
            postEvent = exactKeyDelta(prePost.api30EventLines, observation.api30EventLines, key),
            naturalEvent = exactKeyDelta(observation.api30EventLines, final.api30EventLines, key),
            postModern = exactKeyDelta(prePost.modernLogLines, observation.modernLogLines, key),
            naturalModern = exactKeyDelta(observation.modernLogLines, final.modernLogLines, key),
        )

    /** Returns every condition that prevents a supported visible-lifetime conclusion. */
    private fun systemUiEvidenceGaps(
        deltas: SystemUiLogDeltas,
        baseline: SystemUiLogSnapshot,
        initialDumpLines: List<String>,
        finalDumpLines: List<String>,
        channelImportance: Int,
    ): List<String> {
        val baselinesStable = deltas.all().all { it.baselineStable }
        val sawInitialShow =
            deltas.postEvent.lines.any(::isApi30HeadsUpVisible) ||
                deltas.postModern.lines.any(::isHeadsUpShow)
        val sawNaturalHide =
            deltas.naturalEvent.lines.any(::isApi30HeadsUpHidden) ||
                deltas.naturalModern.lines.any(::isHeadsUpRemoval)
        val sawReasonedNaturalHide =
            deltas.naturalModern.lines.any { line ->
                isHeadsUpRemoval(line) && line.contains("reason", ignoreCase = true)
            }
        return buildList {
            if (!baselinesStable) add("SystemUI buffer baseline rotated")
            if (baseline.statusBarDumpable == null) add("no supported StatusBar dumpable was registered")
            if (baseline.modernLogDumpable == null) add("no supported reason-bearing HUN log buffer was registered")
            if (channelImportance < NotificationManager.IMPORTANCE_HIGH) {
                add("notification channel importance $channelImportance is below HIGH")
            }
            if (initialDumpLines.isEmpty()) add("initial HeadsUpManagerPhone dump did not contain the target key")
            if (!sawInitialShow) add("no exact-key heads-up show transition was captured")
            if (!sawNaturalHide) add("no exact-key natural heads-up removal transition was captured")
            if (!sawReasonedNaturalHide) add("no exact-key reason-bearing heads-up removal was available")
            if (finalDumpLines.isNotEmpty()) add("target remained in the final HeadsUpManagerPhone dump")
        }
    }

    /** Keeps only lines appended after a stable baseline and matching the exact synthetic key. */
    private fun exactKeyDelta(
        before: List<String>,
        after: List<String>,
        key: String,
    ): FilteredLogDelta {
        val delta = HeadsUpSystemUiDiagnostics.exactTargetDelta(before, after, key)
        return FilteredLogDelta(
            lines = delta.lines,
            baselineStable = delta.baselineStable,
        )
    }

    /** Recognizes the API 30 event-log transition whose visible field is false. */
    private fun isApi30HeadsUpHidden(line: String): Boolean = API30_HIDDEN_EVENT_REGEX.containsMatchIn(line)

    /** Recognizes the API 30 event-log transition whose visible field is true. */
    private fun isApi30HeadsUpVisible(line: String): Boolean = API30_VISIBLE_EVENT_REGEX.containsMatchIn(line)

    /** Recognizes an add/show entry from newer SystemUI log buffers. */
    private fun isHeadsUpShow(line: String): Boolean {
        val normalized = line.lowercase()
        return normalized.contains("show") || normalized.contains("add")
    }

    /** Recognizes a reason-capable removal/hide entry from newer SystemUI log buffers. */
    private fun isHeadsUpRemoval(line: String): Boolean {
        val normalized = line.lowercase()
        return normalized.contains("remove") || normalized.contains("hide")
    }

    /** Reads a bounded natural observation window from instrumentation arguments. */
    private fun observationWindowMillis(): Long =
        arguments
            .getString(ARG_OBSERVATION_WINDOW_MS)
            ?.toLongOrNull()
            ?.coerceIn(MIN_OBSERVATION_WINDOW_MS, MAX_OBSERVATION_WINDOW_MS)
            ?: DEFAULT_OBSERVATION_WINDOW_MS

    /** Builds a synthetic group message whose identifiers are safe to export as diagnostics. */
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
            sender = user("heads-up-sender", "Heads-up sender"),
            receiver = user("heads-up-receiver", "Heads-up receiver"),
            previewText = "Notification timing probe",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = System.currentTimeMillis(),
            isFromSelf = false,
        )
    }

    /** Builds one synthetic notification identity without a real profile URL. */
    private fun user(
        accountIdHex: String,
        displayName: String,
    ): NotificationUserFfi =
        NotificationUserFfi(
            accountIdHex = accountIdHex,
            displayName = displayName,
            pictureUrl = null,
        )

    /** Executes a bounded diagnostic shell command and returns its UTF-8 output. */
    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).use { output ->
                output.readBytes().toString(Charsets.UTF_8)
            }

    /** Polls monotonic time without requiring Compose or Activity synchronization. */
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

    /** Bounds instrumentation status payloads while retaining exact synthetic-key lines. */
    private fun List<String>.asStatusText(): String = joinToString("\n").take(MAX_STATUS_TEXT_CHARS)

    private companion object {
        const val ARG_ALLOW_HEADS_UP_PROBE = "allowHeadsUpProbe"
        const val ARG_OBSERVATION_WINDOW_MS = "headsUpObservationMs"
        const val LISTENER_CONNECT_TIMEOUT_MS = 5_000L
        const val LISTENER_EVENT_TIMEOUT_MS = 5_000L
        const val HOME_SETTLE_MS = 500L
        const val DEFAULT_OBSERVATION_WINDOW_MS = 8_000L
        const val MIN_OBSERVATION_WINDOW_MS = 5_000L
        const val MAX_OBSERVATION_WINDOW_MS = 15_000L
        const val MAX_STATUS_TEXT_CHARS = 4_000
        const val SYSTEM_UI_DUMP_COMMAND =
            "dumpsys activity service com.android.systemui/.SystemUIService"
        const val API30_HEADS_UP_EVENT_COMMAND =
            "logcat -b events -d -v epoch -s sysui_heads_up_status"
        const val MODERN_HEADS_UP_LOG_NAME = "NotifHeadsUpLog"
        val STATUS_BAR_DUMPABLES =
            listOf(
                "com.google.android.systemui.statusbar.phone.StatusBarGoogle",
                "com.android.systemui.statusbar.phone.StatusBar",
                "StatusBarGoogle",
                "StatusBar",
            )
        val API30_HIDDEN_EVENT_REGEX = Regex(""",\s*0\s*]""")
        val API30_VISIBLE_EVENT_REGEX = Regex(""",\s*1\s*]""")
    }
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

private data class FrameworkDelivery(
    val posted: Boolean,
    val firstFrameworkPost: NotificationTimingListenerPost,
    val secondFrameworkPost: NotificationTimingListenerPost,
)

private data class NaturalObservation(
    val observationWindowMs: Long,
    val naturalRemoval: NotificationTimingListenerRemoval?,
    val cardStillActive: Boolean,
    val systemUiEvidence: SystemUiHeadsUpEvidence,
    val channelImportance: Int,
)

private data class CleanupObservation(
    val appCancelNanos: Long,
    val removal: NotificationTimingListenerRemoval?,
)

private data class HeadsUpProbeReport(
    val probe: HeadsUpProbeState,
    val delivery: FrameworkDelivery,
    val observation: NaturalObservation,
    val cleanup: CleanupObservation,
)

private data class SystemUiLogSnapshot(
    val statusBarDumpable: String?,
    val modernLogDumpable: String?,
    val api30EventLines: List<String>,
    val modernLogLines: List<String>,
)

private data class FilteredLogDelta(
    val lines: List<String>,
    val baselineStable: Boolean,
)

private data class SystemUiLogDeltas(
    val postEvent: FilteredLogDelta,
    val naturalEvent: FilteredLogDelta,
    val postModern: FilteredLogDelta,
    val naturalModern: FilteredLogDelta,
) {
    /** Returns all phases for one baseline-stability audit. */
    fun all(): List<FilteredLogDelta> = listOf(postEvent, naturalEvent, postModern, naturalModern)
}

private data class SystemUiHeadsUpEvidence(
    val initialDumpLines: List<String>,
    val finalDumpLines: List<String>,
    val statusBarDumpable: String?,
    val modernLogDumpable: String?,
    val postEventLines: List<String>,
    val naturalEventLines: List<String>,
    val postModernLogLines: List<String>,
    val naturalModernLogLines: List<String>,
    val isConclusive: Boolean,
    val inconclusiveReason: String,
)
