package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.PollProjectionFfi

private val PollFieldLineBreak = Regex("\\s*\\R\\s*")

/**
 * The readable plain text whole-message Copy puts on the clipboard for a poll: a localized heading carrying the
 * question, then each option label numbered in poll order. Pasting it anywhere, including another White Noise chat,
 * gives ordinary text, never a new poll.
 *
 * Only what the poll asks is copied. Tallies, voters, the local selection, the creator and the deadline belong to
 * the original group and stay out. A malformed projection (blank question, no options or a blank label) returns
 * null, so Copy offers nothing rather than the bare "Poll" label.
 */
internal fun pollCopyText(
    poll: PollProjectionFfi,
    copy: MessageTextCopy,
): String? {
    val question = poll.question.asPollCopyLine()
    val labels = poll.options.map { it.label.asPollCopyLine() }
    if (question.isEmpty() || labels.isEmpty() || labels.any(String::isEmpty)) return null
    return buildString {
        append(copy.pollCopyHeading(question))
        labels.forEachIndexed { index, label ->
            append('\n').append(index + 1).append(". ").append(label)
        }
    }
}

/** Trims a poll field and folds embedded line breaks into one space, so every option keeps its own numbered line. */
private fun String.asPollCopyLine(): String = trim().replace(PollFieldLineBreak, " ")
