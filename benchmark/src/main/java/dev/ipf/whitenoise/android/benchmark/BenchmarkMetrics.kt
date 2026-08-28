package dev.ipf.whitenoise.android.benchmark

import android.os.Trace
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
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
internal const val WARM_RESUME_FIRST_USEFUL_FRAME_TRACE = "WhiteNoise.warmResume.firstUsefulFrame"

private val notificationRoutePhaseSections =
    listOf(
        "WhiteNoise.notificationRoute.total",
        "WhiteNoise.notificationRoute.accountActivation",
        "WhiteNoise.notificationRoute.groupDetails",
        "WhiteNoise.notificationRoute.controllerBind",
        "WhiteNoise.notificationRoute.targetTimeline",
        "WhiteNoise.notificationRoute.initialAnchor",
        "WhiteNoise.notificationRoute.firstConversationFrame",
    )

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
