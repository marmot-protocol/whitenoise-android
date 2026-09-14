package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchEpochBounds
import java.util.Locale

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
     * Case-insensitive, trimmed needle match against a whitespace-normalized
     * body, aligned with the title/preview match's substring semantics.
     * Returns false for a blank needle so callers don't have to pre-check.
     */
    fun bodyMatches(
        plaintext: String,
        ciNeedle: String,
    ): Boolean {
        val normalizedNeedle = normalizeWhitespace(ciNeedle)
        if (normalizedNeedle.isEmpty()) return false
        return normalizeSearchBody(plaintext)
            .contains(normalizedNeedle, ignoreCase = true)
    }

    /**
     * Whether a row's match is fully explained by its title, last-message
     * preview, or group description (the synchronous match the chat-list
     * filter already performs). When true, the body-match snippet line and
     * tap-to-message focus must be suppressed: the issue/PR contract restricts
     * the secondary snippet and scroll-to-message tap-through to rows that
     * matched *only* on an older message body, so a chat surfacing on its
     * title/preview/description keeps the normal single-line row and a normal
     * conversation open. Tokenized identically to the title/preview/description
     * match in `applyChatListSearchAndFilter` (lowercase + substring
     * containment). `description` defaults to empty so existing callers that
     * predate the description-search extension (#388) keep their behaviour.
     */
    fun titleOrPreviewMatches(
        displayTitle: String,
        previewText: String,
        ciNeedle: String,
        description: String = "",
    ): Boolean =
        ciNeedle.isNotEmpty() &&
            (
                displayTitle.lowercase(Locale.ROOT).contains(ciNeedle) ||
                    previewText.lowercase(Locale.ROOT).contains(ciNeedle) ||
                    description.lowercase(Locale.ROOT).contains(ciNeedle)
            )

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
        val normalized = normalizeSearchBody(plaintext)
        val normalizedNeedle = normalizeWhitespace(needle)
        if (normalizedNeedle.isEmpty()) return null
        val matchStart = normalized.indexOf(normalizedNeedle, ignoreCase = true)
        if (matchStart < 0) return null
        val matchEnd = matchStart + normalizedNeedle.length

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
        // Don't slice through a surrogate pair at either edge — a half pair
        // renders as U+FFFD in the snippet. Mirror MarkdownRenderer.previewSafeEnd.
        if (windowStart > 0 && Character.isLowSurrogate(normalized[windowStart])) windowStart++
        if (windowEnd > windowStart && Character.isHighSurrogate(normalized[windowEnd - 1])) windowEnd--

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

    private fun normalizeSearchBody(value: String): String = normalizeWhitespace(value.take(BODY_SEARCH_SCAN_LIMIT))

    private fun normalizeWhitespace(value: String): String = WHITESPACE_RUN.replace(value, " ").trim()

    /**
     * The minimal view of a timeline record this object needs to pick a body
     * match. Mirrors the relevant `TimelineMessageRecordFfi` fields so the
     * selection logic can be unit-tested without the FFI type.
     */
    interface SearchableRecord {
        val kind: ULong
        val deleted: Boolean
        val plaintext: String
        val messageIdHex: String
        val timelineAt: ULong

        /** Author account hex, matched case-insensitively against a sender filter. */
        val sender: String
            get() = ""

        /** MIME types of the attached media, one per attachment. */
        val mediaTypes: List<String>
            get() = emptyList()

        /** Human labels for the attachments (file name, else MIME type) for needle-less snippets. */
        val mediaLabels: List<String>
            get() = emptyList()
    }

    /**
     * The first record in [records] (engine order, newest-first) that is both
     * an eligible searchable body ([isSearchableBody]) and contains the needle
     * ([bodyMatches]). Returns null when none qualify.
     *
     * This is the per-page selection used by the chat-list body search. The
     * engine's `search` field pre-filters to needle-matching rows but cannot
     * filter by kind/deleted, so a page can be full of excluded rows
     * (reactions, deletes, kind:1210 system events) ahead of an eligible body.
     * Critically, selection scans the *entire* list — it does not stop at the
     * page-size cap — so an older eligible body that sits behind several newer
     * excluded hits in the same page is still found, and the caller's backward
     * paging continues across pages until one surfaces (issue #290).
     * Without a needle (filters only) any searchable body or attachment-bearing chat message is eligible.
     */
    fun firstEligibleBodyMatch(
        records: List<SearchableRecord>,
        ciNeedle: String,
        constraints: MessageSearchConstraints? = null,
    ): SearchableRecord? = records.firstOrNull { record -> isEligibleMatch(record, ciNeedle, constraints) }

    private fun isEligibleMatch(
        record: SearchableRecord,
        ciNeedle: String,
        constraints: MessageSearchConstraints?,
    ): Boolean {
        val hasBody = record.plaintext.isNotBlank()
        val needleSatisfied =
            if (ciNeedle.isEmpty()) {
                hasBody || record.mediaTypes.isNotEmpty()
            } else {
                hasBody && bodyMatches(record.plaintext, ciNeedle)
            }
        return !record.deleted &&
            record.kind in SearchableBodyKinds &&
            needleSatisfied &&
            (constraints == null || constraints.matches(record))
    }

    /** Snippet for a filter-only match: the leading body text, else the first attachment label. */
    fun buildFilteredSnippet(
        plaintext: String,
        mediaLabels: List<String>,
        maxLength: Int = DEFAULT_SNIPPET_LENGTH,
    ): SnippetHighlight? {
        val body = normalizeSearchBody(plaintext)
        val text =
            body.ifBlank { mediaLabels.firstOrNull { it.isNotBlank() }?.let(::normalizeWhitespace).orEmpty() }
        return when {
            text.isBlank() -> null
            text.length <= maxLength -> SnippetHighlight(text, 0, 0)
            else -> {
                val end = if (Character.isHighSurrogate(text[maxLength - 1])) maxLength - 1 else maxLength
                SnippetHighlight(text.substring(0, end) + ELLIPSIS, 0, 0)
            }
        }
    }

    const val DEFAULT_SNIPPET_LENGTH: Int = 80
    private const val BODY_SEARCH_SCAN_LIMIT = 4096
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
    /**
     * Timeline timestamp (unix seconds) of the matched message — *not* the
     * chat's last-message time. A body-match row surfaces this so its
     * timestamp points at the message the search actually found, which can be
     * far older than the conversation's latest activity (#594).
     */
    val timelineAt: ULong,
)

/**
 * Message-level constraints of the prototype's Senders, Date and Content filters, evaluated
 * client-side over the engine's timeline rows. AND across categories, OR within one.
 */
data class MessageSearchConstraints(
    val senderIds: Set<String> = emptySet(),
    val dateBounds: GlobalSearchEpochBounds? = null,
    val contentKinds: Set<GlobalSearchContentKind> = emptySet(),
) {
    val isActive: Boolean
        get() = senderIds.isNotEmpty() || dateBounds != null || contentKinds.isNotEmpty()

    fun matches(record: ChatListMessageSearch.SearchableRecord): Boolean =
        (senderIds.isEmpty() || record.sender.lowercase(Locale.ROOT) in senderIds) &&
            (dateBounds == null || dateBounds.containsUnixSeconds(record.timelineAt)) &&
            (
                contentKinds.isEmpty() ||
                    messageSearchContentKinds(record.plaintext, record.mediaTypes).any { it in contentKinds }
            )

    private fun GlobalSearchEpochBounds.containsUnixSeconds(seconds: ULong): Boolean {
        val millis = seconds.toLong() * MILLIS_PER_SECOND
        return millis >= startEpochMillisInclusive && millis < endEpochMillisExclusive
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

private val MESSAGE_SEARCH_LINK = Regex("(?i)(https?://|www\\.)\\S+")

/** The prototype's content classification: text, links, images & video, voice & audio, files, any attachment. */
fun messageSearchContentKinds(
    plaintext: String,
    mediaTypes: List<String>,
): Set<GlobalSearchContentKind> =
    buildSet {
        if (plaintext.isNotBlank()) add(GlobalSearchContentKind.TEXT)
        if (MESSAGE_SEARCH_LINK.containsMatchIn(plaintext)) add(GlobalSearchContentKind.LINKS)
        if (mediaTypes.isNotEmpty()) add(GlobalSearchContentKind.ANY_ATTACHMENT)
        mediaTypes.forEach { type ->
            when {
                type.startsWith("image/", ignoreCase = true) || type.startsWith("video/", ignoreCase = true) ->
                    add(GlobalSearchContentKind.IMAGES_VIDEO)
                type.startsWith("audio/", ignoreCase = true) -> add(GlobalSearchContentKind.VOICE_AUDIO)
                else -> add(GlobalSearchContentKind.FILES_DOCUMENTS)
            }
        }
    }
