package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

/**
 * Bounded optimistic/bridge caption handoff for projected media rows whose
 * engine plaintext is blank (#1783).
 */
internal object TimelineMediaCaption {
    fun handoffPlaintext(
        projected: TimelineMessageRecordFfi,
        actionRecord: AppMessageRecordFfi,
    ): String? =
        when {
            projected.plaintext.isNotBlank() -> null
            !isMediaTimelineRecord(projected) -> null
            else -> MessageProjector.copyableText(actionRecord)
        }

    fun effectivePlaintext(
        projected: TimelineMessageRecordFfi?,
        actionRecord: AppMessageRecordFfi,
    ): String {
        val fromProjection = projected?.plaintext.orEmpty()
        if (fromProjection.isNotBlank()) return fromProjection
        return actionRecord.plaintext
    }

    private fun isMediaTimelineRecord(record: TimelineMessageRecordFfi): Boolean =
        TimelineProjector.toAppMessageRecord(record).tags.any { it.values.firstOrNull() == "imeta" }
}
