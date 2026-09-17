package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.core.EditVersion

/** One line of the history: revision 0 is the original body, higher numbers are accepted edits. */
internal data class EditHistoryRow(
    val versionNumber: Int,
    val text: String,
    val recordedAt: ULong,
)

/**
 * The rows the dialog lists, newest first. [versions] are oldest-first, as both the window aggregate and a
 * MarmotKit history page deliver them; when the page is only the newest part of a longer history the
 * numbering starts at [editCount] minus what the page holds, so revision numbers stay stable across pages.
 * [original] is appended last only when the caller still knows the original body: under MarmotKit 0.10.1 a
 * record's plaintext is already the edited text, and the engine's history never labels it as the original.
 */
internal fun editHistoryRows(
    original: String?,
    originalTimestamp: ULong,
    versions: List<EditVersion>,
    editCount: Int,
): List<EditHistoryRow> {
    val firstNumber = maxOf(1, editCount - versions.size + 1)
    val revisions =
        versions
            .mapIndexed { index, version ->
                EditHistoryRow(firstNumber + index, version.text, version.recordedAt)
            }.reversed()
    val originalRow = original?.let { EditHistoryRow(0, it, originalTimestamp) }
    return revisions + listOfNotNull(originalRow)
}
