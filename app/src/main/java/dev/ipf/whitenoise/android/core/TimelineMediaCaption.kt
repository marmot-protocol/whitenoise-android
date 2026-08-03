package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

/**
 * Bounded optimistic/bridge caption handoff for projected media rows whose
 * engine plaintext is blank (#1783).
 *
 * During an in-flight or reconciled send, Android may keep the user-authored
 * caption on the action record while the timeline projection still reports blank
 * plaintext. [handoffPlaintext] bridges that seam only for media rows and only
 * when projection plaintext is empty; engine/projector plaintext always wins.
 *
 * After a fresh timeline load, MarmotKit is expected to project persisted media
 * captions into timeline plaintext. That path needs no optimistic state and no
 * Android-side caption storage — consume authoritative projection plaintext.
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
        return MessageProjector.copyableText(actionRecord).orEmpty()
    }

    private fun isMediaTimelineRecord(record: TimelineMessageRecordFfi): Boolean =
        TimelineProjector.toAppMessageRecord(record).tags.any { it.values.firstOrNull() == "imeta" }
}
