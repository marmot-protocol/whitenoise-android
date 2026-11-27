package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent

private val patchSubjectPrefix = Regex("""^\[(?:RFC\s+)?PATCH(?:\s[^\]]*)?]\s*""", RegexOption.IGNORE_CASE)
private val patchLineBreak = Regex("""\r\n|\n|\r""")

/** The format-patch `Subject:` header of a kind 1617 patch, without its `[PATCH n/m]` tag. */
internal fun NostrEvent.patchSubject(): String? {
    if (kind != KIND_GIT_PATCH) return null
    return patchSubjectOf(splitPatchHeader(content).first)
}

/** A format-patch message without its mail headers, so the preview starts at the commit message. */
internal fun NostrEvent.patchMessageBody(): String? {
    val (header, body) = if (kind == KIND_GIT_PATCH) splitPatchHeader(content) else emptyList<String>() to null
    return body?.takeIf { patchSubjectOf(header) != null && it.isNotBlank() }
}

/** The `Subject:` header among [header] lines, unfolded and without its `[PATCH n/m]` tag. */
private fun patchSubjectOf(header: List<String>): String? {
    val start = header.indexOfFirst { it.startsWith("Subject:", ignoreCase = true) }
    if (start < 0) return null
    val folded = header.drop(start + 1).takeWhile { it.firstOrNull()?.isWhitespace() == true }
    // A subject folded straight after the colon joins with a leading space, which the anchored prefix misses.
    val lines = listOf(header[start].substringAfter(':')) + folded
    val joined = lines.joinToString(" ") { it.trim() }
    val subject = joined.trim()
    return subject.replace(patchSubjectPrefix, "").safeField().takeIf(String::isNotBlank)
}

/**
 * Splits a format-patch message at its first blank or whitespace-only line, with any mix of line endings: the
 * header lines before it, and the text after that line, or null when no line ends the header. The subject and
 * the body use this one scan, so they can never disagree about where the header ends.
 */
private fun splitPatchHeader(content: String): Pair<List<String>, String?> {
    val header = mutableListOf<String>()
    var lineStart = 0
    while (lineStart < content.length) {
        val lineBreak = patchLineBreak.find(content, lineStart)
        val line = content.substring(lineStart, lineBreak?.range?.first ?: content.length)
        val nextLine = lineBreak?.range?.last?.plus(1) ?: content.length
        if (line.isBlank()) return header to content.substring(nextLine)
        header += line
        lineStart = nextLine
    }
    return header to null
}
