package dev.ipf.whitenoise.android.benchmark

import android.os.Trace
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric

internal const val OPEN_MEMBERS_TRACE = "benchmark:open-group-members"
internal const val CREATE_GROUP_TRACE = "benchmark:create-group"
internal const val ACCEPT_INVITE_TRACE = "benchmark:accept-invite"

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
