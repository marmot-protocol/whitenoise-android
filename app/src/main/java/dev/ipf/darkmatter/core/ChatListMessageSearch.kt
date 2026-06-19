package dev.ipf.darkmatter.core

/**
 * Pure logic for the chat-list message-body search (issue #290).
 *
 * The chat-list search field (PR #128) matched only on the row's title and
 * last-message preview. This object adds the message-body half: classifying
 * which locally-persisted timeline records are searchable plaintext, picking
 * the snippet to surface under a matched row, and computing the highlight
 * range so the user can see *why* a chat appeared in the results.
 *
 * No Marmot/FFI, Android, or Compose types are referenced here on purpose --
 * everything is plain Kotlin so it can be unit-tested directly. The
 * controller feeds it `(kind, plaintext, deleted)` triples read from the
 * `timelineMessages` FFI search query; the UI consumes [MessageBodyMatch].
 */
object ChatListMessageSearch {
    // Kinds whose plaintext body is user-authored conversation text and is
    // therefore searchable. Per the issue's scope decision: kind:1 (legacy
    // note), kind:9 (chat), kind:1209 (agent stream final). Everything else --
    // reactions (7), deletes (5), edits (1009), stream-start (1200), and
    // group-system events (1210) -- is excluded so search never matches on
    // machine-authored content or raw JSON the user never typed.
    private val SearchableBodyKinds: Set<ULong> = setOf(1uL, 9uL, 1209uL)

    /**
     * Whether a timeline record's body is eligible to match a chat-list
     * query. Deleted messages are skipped -- their plaintext is gone and a
     * "deleted message" tombstone has no body to search.
     */
    fun isSearchableBody(
        kind: ULong,
        deleted: Boolean,
        plaintext: String,
    ): Boolean = !deleted && kind in SearchableBodyKinds && plaintext.isNotBlank()

    /**
     * Case-insensitive, trimmed needle match against a body, tokenized the
     * same way the title/preview match in `applyChatListSearchAndFilter` is:
     * lowercase + substring containment. Returns false for a blank needle so
     * callers don't have to pre-check.
     */
    fun bodyMatches(
        plaintext: String,
        ciNeedle: String,
    ): Boolean = ciNeedle.isNotEmpty() && plaintext.lowercase().contains(ciNeedle)

    /**
     * Build the snippet to show under a matched chat row. Centers a window of
     * up to [maxLength] characters on the first occurrence of the needle so
     * the match is visible even in a long message, collapsing internal
     * whitespace/newlines to single spaces (a chat-list row is single-line).
     * Adds a leading/trailing ellipsis when the window is clipped from the
     * original body.
     *
     * Returns the snippet text plus the [SnippetHighlight.highlightStart] /
     * [SnippetHighlight.highlightEnd] range (in snippet coordinates) of the
     * matched needle, or null when the needle isn't present in the body.
     */
    fun buildSnippet(
        plaintext: String,
        needle: String,
        maxLength: Int = DEFAULT_SNIPPET_LENGTH,
    ): SnippetHighlight? {
        if (needle.isEmpty()) return null
        // Collapse runs of whitespace (incl. newlines) to single spaces before
        // locating the needle, so the index we compute lines up with the
        // single-line snippet the UI renders. Don't trim mid-string -- only the
        // window edges get ellipsized.
        val normalized = WHITESPACE_RUN.replace(plaintext, " ").trim()
        val matchStart = normalized.lowercase().indexOf(needle.lowercase())
        if (matchStart < 0) return null
        val matchEnd = matchStart + needle.length

        // No clipping needed: short body fits whole.
        if (normalized.length <= maxLength) {
            return SnippetHighlight(
                text = normalized,
                highlightStart = matchStart,
                highlightEnd = matchEnd,
            )
        }

        // Center the window on the match. Reserve room around the needle; if
        // the needle itself is longer than the window, anchor at its start.
        val needleLen = matchEnd - matchStart
        val slack = (maxLength - needleLen).coerceAtLeast(0)
        var windowStart = (matchStart - slack / 2).coerceAtLeast(0)
        var windowEnd = (windowStart + maxLength).coerceAtMost(normalized.length)
        // Pull the window left if we hit the right edge with room to spare on
        // the left, so we use the full budget.
        windowStart = (windowEnd - maxLength).coerceAtLeast(0)
        windowEnd = (windowStart + maxLength).coerceAtMost(normalized.length)

        val clippedLeft = windowStart > 0
        val clippedRight = windowEnd < normalized.length
        val core = normalized.substring(windowStart, windowEnd)
        val prefix = if (clippedLeft) ELLIPSIS else ""
        val suffix = if (clippedRight) ELLIPSIS else ""
        val text = prefix + core + suffix

        // Re-locate the highlight in snippet coordinates: the needle moved by
        // (prefix length - windowStart). Clamp into the snippet in case the
        // match sits right at a clipped edge.
        val shift = prefix.length - windowStart
        val hlStart = (matchStart + shift).coerceIn(0, text.length)
        val hlEnd = (matchEnd + shift).coerceIn(hlStart, text.length)
        return SnippetHighlight(
            text = text,
            highlightStart = hlStart,
            highlightEnd = hlEnd,
        )
    }

    const val DEFAULT_SNIPPET_LENGTH: Int = 80
    private const val ELLIPSIS = "\u2026"
    private val WHITESPACE_RUN = Regex("\\s+")
}

/**
 * A snippet plus the [highlightStart], [highlightEnd) range of the matched
 * needle within [text]. Range is always a valid substring of [text].
 */
data class SnippetHighlight(
    val text: String,
    val highlightStart: Int,
    val highlightEnd: Int,
)

/**
 * The chat-list search result for a chat that matched on a message *body*
 * (not its title or preview). Carries the matched message so the row can
 * render the highlighted snippet and tap-through can scroll to it.
 */
data class MessageBodyMatch(
    val groupIdHex: String,
    val messageIdHex: String,
    val snippet: SnippetHighlight,
)
