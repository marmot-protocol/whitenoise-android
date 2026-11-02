package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector

private const val AGENT_STREAM_PREVIEW_MAX_CHARS = 16 * 1024

internal fun appendCappedAgentStreamPreview(
    text: StringBuilder,
    chunk: String,
    maxChars: Int = AGENT_STREAM_PREVIEW_MAX_CHARS,
) {
    if (maxChars <= 0) {
        text.clear()
        return
    }
    if (chunk.length >= maxChars) {
        text.clear()
        text.append(chunk.takeLast(maxChars))
        return
    }
    text.append(chunk)
    if (text.length > maxChars) {
        text.delete(0, text.length - maxChars)
    }
}

internal data class StreamFinalDisplayPosition(
    val recordedAt: ULong,
    val timelineOrder: ULong,
    val afterMessageId: String? = null,
)

internal fun anchoredStreamDisplayPosition(
    position: StreamFinalDisplayPosition,
    parentRecordedAt: ULong,
    parentTimelineOrder: ULong,
): StreamFinalDisplayPosition {
    val remainingOrder = ULong.MAX_VALUE - parentTimelineOrder
    val resolvedOrder =
        if (position.timelineOrder > remainingOrder) ULong.MAX_VALUE else parentTimelineOrder + position.timelineOrder
    return position.copy(recordedAt = parentRecordedAt, timelineOrder = resolvedOrder)
}

internal fun resolvedDurableStreamDisplayPosition(
    candidate: StreamFinalDisplayPosition,
    parentRecordedAt: ULong,
    parentTimelineOrder: ULong,
): StreamFinalDisplayPosition? =
    candidate
        .takeIf { it.recordedAt <= parentRecordedAt }
        ?.let {
            anchoredStreamDisplayPosition(
                position = it,
                parentRecordedAt = parentRecordedAt,
                parentTimelineOrder = parentTimelineOrder,
            )
        }

/**
 * Derive reload-stable ordering candidates for stream starts and finals from
 * their durable relationship chain:
 * final `stream-start` -> kind-1200 start `parent` -> prompt.
 *
 * The page is indexed before any record is projected, so skewed stream records
 * may precede their prompt in the engine's raw timestamp order without making
 * this result iteration-order dependent. The controller compares each child's
 * raw timestamp with its parent's effective local position before applying a
 * candidate. We intentionally do not infer a prompt when either durable link
 * is absent: nearest-message and receive-time guesses break historical sync
 * ordering.
 */
internal fun durableStreamDisplayPositions(
    // Supply the complete page so durable links resolve independently of record order.
    records: List<TimelineMessageRecordFfi>,
): Map<String, StreamFinalDisplayPosition> {
    val recordsById = records.associateBy(TimelineMessageRecordFfi::messageIdHex)
    val positions = linkedMapOf<String, StreamFinalDisplayPosition>()
    val starts = mutableMapOf<String, TimelineMessageRecordFfi>()

    for (startRecord in records) {
        if (startRecord.kind != 1200uL || timelineTagValue(startRecord, "stream") == null) continue
        val parentId = timelineTagValue(startRecord, "parent") ?: continue
        val parentRecord = recordsById[parentId] ?: continue
        if (parentRecord.kind != 9uL || parentRecord.groupIdHex != startRecord.groupIdHex) continue
        starts[startRecord.messageIdHex] = startRecord
        positions[startRecord.messageIdHex] =
            StreamFinalDisplayPosition(
                recordedAt = startRecord.timelineAt,
                timelineOrder = 1uL,
                afterMessageId = parentRecord.messageIdHex,
            )
    }

    for (finalRecord in records) {
        if (finalRecord.kind != 9uL) continue
        val streamId = timelineTagValue(finalRecord, "stream") ?: continue
        val startId = timelineTagValue(finalRecord, "stream-start") ?: continue
        val startRecord = starts[startId] ?: continue
        if (
            startRecord.groupIdHex != finalRecord.groupIdHex ||
            startRecord.sender != finalRecord.sender ||
            timelineTagValue(startRecord, "stream") != streamId
        ) {
            continue
        }
        positions[finalRecord.messageIdHex] =
            StreamFinalDisplayPosition(
                recordedAt = finalRecord.timelineAt,
                timelineOrder = 1uL,
                afterMessageId = startRecord.messageIdHex,
            )
    }
    return positions
}

private fun timelineTagValue(
    record: TimelineMessageRecordFfi,
    name: String,
): String? =
    record.tags
        .firstOrNull { it.values.firstOrNull() == name }
        ?.values
        ?.getOrNull(1)

/**
 * A stream final replaces the already displayed preview bubble. Keep that
 * bubble's display position instead of adopting the bridge-authored final
 * timestamp, which may be earlier because the bridge and phone clocks differ.
 */
internal fun streamFinalDisplayPosition(
    finalRecord: AppMessageRecordFfi,
    displayedStream: TimelineMessage?,
): StreamFinalDisplayPosition? {
    if (!MessageProjector.isStreamFinal(finalRecord)) return null
    val streamId = MessageProjector.streamId(finalRecord) ?: return null
    val displayed = displayedStream?.takeIf { it.id == "stream:$streamId" } ?: return null
    return StreamFinalDisplayPosition(
        recordedAt = displayed.record.recordedAt,
        timelineOrder = displayed.timelineOrder,
    )
}
