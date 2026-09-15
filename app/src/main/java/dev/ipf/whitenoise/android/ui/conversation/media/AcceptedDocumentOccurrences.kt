package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri

/**
 * Removes only accepted occurrences from an append-only staging list. The send owner disables removal
 * until completion, so newly appended copies of the same URI remain distinct unsent occurrences.
 */
internal fun removeAcceptedDocumentOccurrences(
    current: List<Uri>,
    accepted: List<Uri>,
): List<Uri> {
    val remaining = accepted.groupingBy { it }.eachCount().toMutableMap()
    return current.filter { uri ->
        val count = remaining[uri] ?: 0
        if (count > 0) {
            remaining[uri] = count - 1
            false
        } else {
            true
        }
    }
}
