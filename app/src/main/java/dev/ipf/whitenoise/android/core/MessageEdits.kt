package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi

/**
 * One published kind-1009 edit version for a target message: the
 * replacement plaintext plus the wall-clock time the edit was emitted.
 * Versions are ordered oldest-first in [EditState.versions], so the latest
 * is the last element.
 */
data class EditVersion(
    val messageIdHex: String,
    val text: String,
    val recordedAt: ULong,
)

/**
 * Resolved edit history for a single target message id.
 *
 * [latestText] is the most recent version's text, what the bubble should
 * render in place of the original plaintext. [versions] carries every
 * accepted edit (already authorship-filtered) in chronological order, so
 * an "(edited · N)" affordance can open a history modal listing each
 * revision with its timestamp.
 */
data class EditState(
    val latestText: String,
    val count: Int,
    val versions: List<EditVersion>,
)

/**
 * Aggregate kind-1009 edit events across a timeline slice into a
 * `targetMessageId → EditState` map.
 *
 * Authorship is enforced here: an edit is only honoured when its
 * authenticated author matches the target event's author. The runtime
 * gives us the inner event's `sender`, which (for accepted records) is
 * the verified signer; a mismatched edit is silently dropped — same
 * policy the desktop client applies.
 *
 * Records whose target isn't in [records] (yet) are skipped; the next
 * recompute will pick them up once the target arrives.
 */
fun aggregateEdits(records: List<AppMessageRecordFfi>): Map<String, EditState> {
    if (records.isEmpty()) return emptyMap()
    val byId = HashMap<String, AppMessageRecordFfi>(records.size)
    for (r in records) {
        if (r.messageIdHex.isNotBlank()) byId[r.messageIdHex] = r
    }
    val versionsByTarget = LinkedHashMap<String, MutableList<EditVersion>>()
    for (record in records) {
        if (!MessageProjector.isEdit(record)) continue
        val target = MessageProjector.editTargetMessageId(record) ?: continue
        val originalSender = byId[target]?.sender ?: continue
        if (!sendersMatch(record.sender, originalSender)) continue
        if (record.plaintext.isBlank()) continue
        versionsByTarget
            .getOrPut(target) { mutableListOf() }
            .add(EditVersion(messageIdHex = record.messageIdHex, text = record.plaintext, recordedAt = record.recordedAt))
    }
    val result = LinkedHashMap<String, EditState>(versionsByTarget.size)
    for ((target, raw) in versionsByTarget) {
        val sorted = raw.sortedWith(compareBy<EditVersion> { it.recordedAt }.thenBy { it.messageIdHex })
        result[target] =
            EditState(
                latestText = sorted.last().text,
                count = sorted.size,
                versions = sorted,
            )
    }
    return result
}

private fun sendersMatch(
    a: String,
    b: String,
): Boolean = a.equals(b, ignoreCase = true)
