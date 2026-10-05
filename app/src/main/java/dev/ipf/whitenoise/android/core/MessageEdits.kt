package dev.ipf.whitenoise.android.core

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
 * One message's accepted-edit state as MarmotKit resolved it (0.10.1). The engine owns edit acceptance and
 * shares the effective text across timelines, replies and chat previews, so this holds regardless of how
 * much of the conversation the app has loaded.
 */
data class AuthoritativeEdit(
    val messageIdHex: String,
    val editCount: Int,
    val effectiveText: String,
)

/**
 * Maps MarmotKit's accepted-edit summaries into display state. History is loaded through the native
 * paged history API; Android does not inspect e-tags, compare authors or order wire edit versions.
 */
fun withAuthoritativeEdits(
    aggregated: Map<String, EditState>,
    authoritative: Collection<AuthoritativeEdit>,
): Map<String, EditState> {
    if (authoritative.isEmpty()) return aggregated
    val merged = LinkedHashMap(aggregated)
    for (edit in authoritative) {
        if (edit.editCount <= 0) continue
        merged[edit.messageIdHex] =
            EditState(
                latestText = edit.effectiveText,
                count = edit.editCount,
                versions = merged[edit.messageIdHex]?.versions.orEmpty(),
            )
    }
    return merged
}
