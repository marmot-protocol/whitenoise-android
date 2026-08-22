package dev.ipf.whitenoise.android.benchmark

import android.os.Trace
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric

internal const val OPEN_MEMBERS_TRACE = "benchmark:open-group-members"
internal const val CREATE_GROUP_TRACE = "benchmark:create-group"
internal const val ACCEPT_INVITE_TRACE = "benchmark:accept-invite"
internal const val SCROLL_CONVERSATION_TRACE = "benchmark:scroll-conversation"
internal const val SECONDARY_ACCOUNT_NOTIFICATION_TRACE = "benchmark:secondary-account-notification"
internal const val OPEN_CONVERSATION_VISIBLE_TRACE = "benchmark:open-conversation-visible"
internal const val OPEN_CONVERSATION_SETTLED_TRACE = "benchmark:open-conversation-settled"

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
