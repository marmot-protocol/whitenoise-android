package dev.ipf.whitenoise.android.benchmark

import android.os.Trace
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.PowerCategory
import androidx.benchmark.macro.PowerCategoryDisplayLevel
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric

internal const val OPEN_MEMBERS_TRACE = "benchmark:open-group-members"
internal const val CREATE_GROUP_TRACE = "benchmark:create-group"
internal const val ACCEPT_INVITE_TRACE = "benchmark:accept-invite"
internal const val SCROLL_CONVERSATION_TRACE = "benchmark:scroll-conversation"
internal const val SCROLL_CHAT_LIST_TRACE = "benchmark:scroll-chat-list"
internal const val SECONDARY_ACCOUNT_NOTIFICATION_TRACE = "benchmark:secondary-account-notification"
internal const val OPEN_CONVERSATION_VISIBLE_TRACE = "benchmark:open-conversation-visible"
internal const val OPEN_CONVERSATION_SETTLED_TRACE = "benchmark:open-conversation-settled"
internal const val OPEN_CONVERSATION_SETTINGS_TRACE = "benchmark:open-conversation-settings"
internal const val WARM_RESUME_FIRST_USEFUL_FRAME_TRACE = "WhiteNoise.warmResume.firstUsefulFrame"
private const val NETWORK_RECOVERY_ATTEMPT_TRACE = "WhiteNoise.recovery.network-attempt"
private const val NETWORK_RECOVERY_CATCH_UP_TRACE = "WhiteNoise.recovery.catchUp.network-reconnect"
private const val PUSH_WAKE_LOCK_TRACE = "WhiteNoise.recovery.push-wake-lock"
private const val CONVERSATION_SETTINGS_APP_DISPATCH_TRACE =
    "WNConversationSettings:click_to_start_activity"

private val notificationRoutePhaseSections =
    listOf(
        "WhiteNoise.notificationRoute.total",
        "WhiteNoise.notificationRoute.accountActivation",
        "WhiteNoise.notificationRoute.targetProjection",
        "WhiteNoise.notificationRoute.controllerBind",
        "WhiteNoise.notificationRoute.targetTimeline",
        "WhiteNoise.notificationRoute.initialAnchor",
        "WhiteNoise.notificationRoute.firstConversationFrame",
    )

/**
 * Cold-start stages, mirroring `StartupStageTraceSection` in the app module.
 * Repeated here by convention for the same reason the notification-route
 * sections above are: the benchmark module does not depend on app sources.
 */
private val startupStageSections =
    listOf(
        "WhiteNoise.startup.client-construction",
        "WhiteNoise.startup.privacy-runtime-configuration",
        "WhiteNoise.startup.marmot-start",
        "WhiteNoise.startup.notification-platform-setup",
        "WhiteNoise.startup.notification-privacy-setup",
        "WhiteNoise.startup.account-refresh",
        "WhiteNoise.startup.account-activation",
        "WhiteNoise.startup.draft-reconciliation",
        "WhiteNoise.startup.external-signer-registration",
    )

/**
 * Startup timing and frame timing plus a slice per bootstrap stage, so a cold
 * start that regresses names the stage that moved instead of only the total.
 * `Mode.Sum` rather than `Mode.First`: a stage can run more than once in an
 * iteration (a retried bootstrap), and the total time spent in it is the
 * number worth comparing.
 */
@OptIn(ExperimentalMetricApi::class)
internal fun startupMetrics(): List<Metric> =
    listOf(StartupTimingMetric(), FrameTimingMetric()) +
        startupStageSections.map { sectionName ->
            TraceSectionMetric(
                sectionName = sectionName,
                mode = TraceSectionMetric.Mode.Sum,
                label = sectionName.substringAfterLast('.'),
                targetPackageOnly = true,
            )
        }

@OptIn(ExperimentalMetricApi::class)
internal fun journeyMetrics(sectionName: String): List<Metric> =
    listOf(
        FrameTimingMetric(),
        TraceSectionMetric(
            sectionName = sectionName,
            mode = TraceSectionMetric.Mode.First,
            label = "journeyDurationMs",
            targetPackageOnly = false,
        ),
    )

@OptIn(ExperimentalMetricApi::class)
internal fun secondaryAccountNotificationMetrics(): List<Metric> =
    journeyMetrics(SECONDARY_ACCOUNT_NOTIFICATION_TRACE) +
        notificationRoutePhaseSections.map { sectionName ->
            TraceSectionMetric(
                sectionName = sectionName,
                mode = TraceSectionMetric.Mode.First,
                label = sectionName.substringAfterLast('.'),
                targetPackageOnly = false,
            )
        }

@OptIn(ExperimentalMetricApi::class)
internal fun openConversationMetrics(): List<Metric> =
    listOf(
        FrameTimingMetric(),
        TraceSectionMetric(
            sectionName = OPEN_CONVERSATION_VISIBLE_TRACE,
            mode = TraceSectionMetric.Mode.First,
            label = "firstTranscriptVisibleMs",
            targetPackageOnly = false,
        ),
        TraceSectionMetric(
            sectionName = OPEN_CONVERSATION_SETTLED_TRACE,
            mode = TraceSectionMetric.Mode.First,
            label = "routeSettledMs",
            targetPackageOnly = false,
        ),
    )

/** App dispatch latency and the separately bounded Android Settings transition. */
@OptIn(ExperimentalMetricApi::class)
internal fun conversationSettingsMetrics(): List<Metric> =
    listOf(
        FrameTimingMetric(),
        TraceSectionMetric(
            sectionName = CONVERSATION_SETTINGS_APP_DISPATCH_TRACE,
            mode = TraceSectionMetric.Mode.First,
            label = "clickToStartActivityMs",
            targetPackageOnly = true,
        ),
        TraceSectionMetric(
            sectionName = OPEN_CONVERSATION_SETTINGS_TRACE,
            mode = TraceSectionMetric.Mode.First,
            label = "clickToFirstSettingsFrameMs",
            targetPackageOnly = false,
        ),
    )

/**
 * Frame timing and journey duration plus the process memory the journey
 * leaves behind. Scrolling a long list is where White Noise's decoded-image
 * caches and attachment buffers grow, and no existing benchmark records that,
 * so a regression in those budgets is invisible to frame timing alone.
 * `Mode.Max` reports the peak during the measured window rather than the value
 * that happens to survive to the end of it.
 */
@OptIn(ExperimentalMetricApi::class)
internal fun scrollMetrics(sectionName: String): List<Metric> =
    journeyMetrics(sectionName) +
        MemoryUsageMetric(
            mode = MemoryUsageMetric.Mode.Max,
            subMetrics =
                listOf(
                    MemoryUsageMetric.SubMetric.HeapSize,
                    MemoryUsageMetric.SubMetric.RssAnon,
                    MemoryUsageMetric.SubMetric.Gpu,
                ),
        )

@OptIn(ExperimentalMetricApi::class)
internal fun warmResumeMetrics(): List<Metric> =
    listOf(
        StartupTimingMetric(),
        FrameTimingMetric(),
        TraceSectionMetric(
            sectionName = WARM_RESUME_FIRST_USEFUL_FRAME_TRACE,
            mode = TraceSectionMetric.Mode.First,
            label = "firstUsefulFrameMs",
            targetPackageOnly = true,
        ),
    )

/**
 * Resource signals for a validated offline-to-online recovery episode.
 *
 * Power categories are system-wide hardware energy, while memory and trace
 * sections are target-process measurements. Results therefore form a
 * same-device regression baseline rather than app-exclusive absolute usage.
 */
@OptIn(ExperimentalMetricApi::class)
internal fun recoveryMetrics(): List<Metric> =
    listOf(
        FrameTimingMetric(),
        MemoryUsageMetric(
            mode = MemoryUsageMetric.Mode.Max,
            subMetrics =
                listOf(
                    MemoryUsageMetric.SubMetric.HeapSize,
                    MemoryUsageMetric.SubMetric.RssAnon,
                ),
        ),
        PowerMetric(
            type =
                PowerMetric.Type.Energy(
                    mapOf(
                        PowerCategory.CPU to PowerCategoryDisplayLevel.TOTAL,
                        PowerCategory.NETWORK to PowerCategoryDisplayLevel.TOTAL,
                        PowerCategory.MEMORY to PowerCategoryDisplayLevel.TOTAL,
                    ),
                ),
        ),
        TraceSectionMetric(
            sectionName = NETWORK_RECOVERY_ATTEMPT_TRACE,
            mode = TraceSectionMetric.Mode.Count,
            label = "networkRecoveryAttemptCount",
            targetPackageOnly = true,
        ),
        TraceSectionMetric(
            sectionName = NETWORK_RECOVERY_ATTEMPT_TRACE,
            mode = TraceSectionMetric.Mode.Sum,
            label = "networkRecoveryAttemptDurationMs",
            targetPackageOnly = true,
        ),
        TraceSectionMetric(
            sectionName = NETWORK_RECOVERY_CATCH_UP_TRACE,
            mode = TraceSectionMetric.Mode.Sum,
            label = "networkRecoveryCatchUpDurationMs",
            targetPackageOnly = true,
        ),
        TraceSectionMetric(
            sectionName = PUSH_WAKE_LOCK_TRACE,
            mode = TraceSectionMetric.Mode.Sum,
            label = "pushWakeLockDurationMs",
            targetPackageOnly = true,
        ),
    )

internal inline fun tracedJourney(
    sectionName: String,
    block: () -> Unit,
) {
    Trace.beginSection(sectionName)
    try {
        block()
    } finally {
        Trace.endSection()
    }
}
