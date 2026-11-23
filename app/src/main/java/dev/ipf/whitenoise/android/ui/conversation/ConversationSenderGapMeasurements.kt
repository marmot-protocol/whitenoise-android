package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/** Keeps the measured decoration for attached timeline rows out of message-reading eligibility. */
internal class ConversationSenderGapMeasurements {
    private data class Measurement(val owner: Any, val gapPx: Int)

    private val rows = mutableMapOf<Any, Measurement>()

    /** Reads the gap for the native lazy-item key after its layout publishes. */
    operator fun get(key: Any): Int = rows[key]?.gapPx ?: 0

    /** Called during measurement, before LazyColumn publishes the corresponding item geometry. */
    fun record(
        key: Any,
        owner: Any,
        gapPx: Int,
    ) {
        if (gapPx == 0) rows.remove(key) else rows[key] = Measurement(owner, gapPx)
    }

    /** A disposed composition cannot clear a replacement row with the same presentation key. */
    fun forget(
        key: Any,
        owner: Any,
    ) {
        if (rows[key]?.owner === owner) rows.remove(key)
    }
}

/** Measures the top decoration along with its padded row, including changes back to zero spacing. */
@Composable
internal fun Modifier.measureConversationSenderGap(
    measurements: ConversationSenderGapMeasurements?,
    key: String,
    gap: Dp,
): Modifier {
    if (measurements == null) return this
    val owner = remember(measurements, key) { Any() }
    DisposableEffect(measurements, key, owner) {
        onDispose { measurements.forget(key, owner) }
    }
    return layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        measurements.record(key, owner, gap.roundToPx().coerceAtMost(row.height))
        layout(row.width, row.height) { row.placeRelative(0, 0) }
    }
}
