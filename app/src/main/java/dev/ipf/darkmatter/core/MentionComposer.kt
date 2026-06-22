package dev.ipf.darkmatter.core

/**
 * Pure, JVM-testable logic for the group composer's `@`-mention picker
 * (issue #414). All Compose/Android wiring lives in the UI layer; everything
 * here operates on plain strings and selection offsets so it can be exercised
 * by unit tests without an emulator.
 *
 * Design contract:
 * - The composer's text state is plain text. A "chip" is just the literal
 *   `@npub1…` run the engine's markdown parser already recognizes as a
 *   `NostrMention`; there is no hidden span. The chip's *single-unit* feel
 *   (cursor can't land inside it, backspace deletes the whole token) is
 *   recreated here by reasoning about those literal runs.
 * - The picker is triggered by an `@` whose query extends to the caret. The
 *   query is the run of non-whitespace, non-`@` characters immediately after
 *   the most recent `@` that the caret sits within or just past.
 */
object MentionComposer {
    /**
     * A member the composer can mention. Built by the UI from the group's
     * member roster (`memberIdHex` → [npub] via the FFI, [displayName] via the
     * profile cache, [nip05] from published profile metadata). Kept free of any
     * FFI/Compose type so the filter is unit-testable.
     */
    data class Candidate(
        val accountIdHex: String,
        val npub: String,
        val displayName: String,
        val nip05: String? = null,
    )

    /**
     * An in-progress `@`-mention query located in the composer text.
     *
     * [start] is the index of the `@` trigger; [query] is the text between the
     * `@` and the caret (may be empty right after typing `@`). Replacing
     * `text.substring(start, caret)` with the canonical `@npub1…` literal is
     * what [insertMention] does.
     */
    data class ActiveQuery(
        val start: Int,
        val query: String,
    )

    /**
     * A composer mention chip: `@` immediately followed by an npub run. A real
     * npub is `npub1` + 58 body chars (length 63), but matching the full run
     * greedily lets us treat a partially-typed or future-length entity as one
     * token too.
     */
    private const val CANONICAL_NPUB_LENGTH = 63
    private const val NPUB_BODY_CHAR_CLASS = "[ac-hj-np-z02-9]"

    private val MENTION_CHIP = Regex("@npub1$NPUB_BODY_CHAR_CLASS+")
    private val RAW_NPUB_DISPLAY_NAME = Regex("npub1$NPUB_BODY_CHAR_CLASS{58}", RegexOption.IGNORE_CASE)

    /**
     * The active `@`-query at [caret] in [text], or null when the caret is not
     * inside a fresh mention query.
     *
     * Rules:
     * - Scan left from the caret for the nearest `@`. If a whitespace is hit
     *   first, there's no open query.
     * - The `@` must be at the start of the text or preceded by whitespace, so
     *   an `@` inside `foo@bar` (e.g. an email) does not open the picker.
     * - The run between `@` and the caret must not already be a completed chip
     *   (`@npub1…`): once a chip is inserted, re-entering it must not reopen the
     *   picker.
     * - Only a collapsed caret (no selection) opens the picker.
     */
    fun activeMentionQuery(
        text: String,
        caret: Int,
    ): ActiveQuery? {
        if (caret < 0 || caret > text.length) return null
        // Walk left from the caret to find the trigger `@`.
        var i = caret - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '@') break
            // A space or newline before we hit an `@` means no open query.
            if (c.isWhitespace()) return null
            i--
        }
        if (i < 0 || text[i] != '@') return null
        // The `@` must begin a word: start-of-text or preceded by whitespace.
        if (i > 0 && !text[i - 1].isWhitespace()) return null
        val query = text.substring(i + 1, caret)
        // Don't reopen the picker when the caret is inside or at the right edge
        // of an already-inserted `@npub1…` chip. The trigger `@` we just found
        // may be the start of a completed chip; if a chip begins at `i` and the
        // caret sits within or just past it, the user is editing around an
        // atomic token, not composing a fresh query — suppress the picker.
        MENTION_CHIP.matchAt(text, i)?.let { chip ->
            if (caret <= chip.range.last + 1) return null
        }
        return ActiveQuery(start = i, query = query)
    }

    /**
     * Filter [candidates] by [query] (the text typed after `@`, without the
     * `@`). Matches, case-insensitively:
     * - display name contains the query, OR
     * - the NIP-05 local part (before `@`) starts with the query, OR
     * - the npub starts with `npub1` + query (so typing the bech32 body filters).
     *
     * An empty query returns all candidates (the picker opens listing everyone
     * the moment `@` is typed). Order is preserved: the UI is expected to feed
     * candidates already sorted most-recently-active first, and ties keep that
     * order (stable).
     */
    fun filter(
        query: String,
        candidates: List<Candidate>,
    ): List<Candidate> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return candidates
        return candidates.filter { matches(it, q) }
    }

    private fun matches(
        candidate: Candidate,
        loweredQuery: String,
    ): Boolean {
        if (candidate.displayName.lowercase().contains(loweredQuery)) return true
        val nip05Local = candidate.nip05?.substringBefore('@')?.lowercase()
        if (nip05Local != null && nip05Local.isNotEmpty() && nip05Local.startsWith(loweredQuery)) return true
        val npub = candidate.npub.lowercase()
        if (npub.startsWith(loweredQuery)) return true
        // Also allow matching the npub body (what the user types after `npub1`).
        if (npub.startsWith("npub1") && npub.substring(5).startsWith(loweredQuery)) return true
        return false
    }

    /**
     * Result of inserting a mention chip: the rewritten [text] and the new
     * collapsed-caret [selection] offset (always just past the trailing space).
     */
    data class Insertion(
        val text: String,
        val selection: Int,
    )

    /**
     * Replace the active `@`-query at [active] with the canonical
     * `@<npub> ` literal and place the caret just past the appended space, so
     * the user can keep typing. A single trailing space separates the chip from
     * following text (and from a following chip), matching how every chat
     * composer behaves and giving the markdown parser a clean token boundary.
     *
     * If the character right after the query is already whitespace, no extra
     * space is added (avoid double spaces when mentioning mid-sentence).
     */
    fun insertMention(
        text: String,
        active: ActiveQuery,
        candidate: Candidate,
    ): Insertion {
        val replaceEnd = active.start + 1 + active.query.length
        val before = text.substring(0, active.start)
        val after = text.substring(replaceEnd)
        val needsSpace = after.firstOrNull()?.isWhitespace() != true
        val chip = "@${candidate.npub}"
        val inserted = chip + if (needsSpace) " " else ""
        val newText = before + inserted + after
        return Insertion(text = newText, selection = before.length + inserted.length)
    }

    /**
     * Whole-chip backspace.
     *
     * Compose reports the post-edit state; we compare the user's [oldText] /
     * [oldCaret] against the proposed [newText] / [newCaret]. When the edit is
     * exactly the single-character deletion the IME performs on Backspace AND
     * that deletion lands at a chip boundary, return the [Insertion] that
     * instead removes the whole chip in one keypress. Two boundary positions
     * count as "at the chip":
     *
     *  1. The caret is at the chip's right edge (`…@npub1…▮`) — the deleted
     *     char is the chip's last char. Widen the deletion to the whole chip.
     *  2. The caret is just past the single trailing space [insertMention]
     *     appends (`…@npub1… ▮`) — the deleted char is that space. This is the
     *     caret position immediately after inserting a chip, so the *first*
     *     Backspace must still feel atomic: remove the chip AND its trailing
     *     space together rather than leaving a bare `@npub1…` behind that would
     *     then need a second keypress.
     *
     * Returns null when the edit isn't one of those chip-boundary backspaces,
     * so the caller applies the IME edit verbatim.
     *
     * This makes the chip feel atomic on delete without storing a hidden span:
     * the literal `@npub1…` run is the chip, and we just widen the deletion to
     * cover all of it (plus the trailing space when that's what was hit).
     */
    fun wholeChipBackspace(
        oldText: String,
        oldCaret: Int,
        newText: String,
        newCaret: Int,
    ): Insertion? {
        // Only handle a collapsed-caret single-char deletion (classic Backspace):
        // exactly one character removed, caret moved back by one.
        if (newText.length != oldText.length - 1) return null
        if (oldCaret != newCaret + 1) return null
        if (newCaret < 0 || newCaret > newText.length) return null
        // The deletion must be the prefix/suffix split at newCaret: everything
        // before newCaret is unchanged, everything after is shifted by one.
        if (oldText.substring(0, newCaret) != newText.substring(0, newCaret)) return null
        if (oldText.substring(oldCaret) != newText.substring(newCaret)) return null
        // Case 1: a chip ends exactly at the caret — widen to the whole chip.
        chipEndingAt(oldText, oldCaret)?.let { chip ->
            val before = oldText.substring(0, chip.first)
            val after = oldText.substring(chip.last + 1)
            return Insertion(text = before + after, selection = before.length)
        }
        // Case 2: the caret is just past a chip's single trailing space (the
        // post-insert caret position). The deleted char is that space; instead
        // remove the chip and the space as one unit so the first Backspace
        // after insertion still deletes the chip atomically.
        if (oldCaret >= 1 && oldText[oldCaret - 1] == ' ') {
            chipEndingAt(oldText, oldCaret - 1)?.let { chip ->
                val before = oldText.substring(0, chip.first)
                // Drop the chip plus the one trailing space (oldCaret - 1).
                val after = oldText.substring(oldCaret)
                return Insertion(text = before + after, selection = before.length)
            }
        }
        return null
    }

    /**
     * Repair a deletion that would leave a mention chip in a partial state
     * (issue #607).
     *
     * [wholeChipBackspace] only intercepts the *single-char* backspace at a
     * chip's right edge. But IME swipe-to-delete (hold-backspace-and-swipe on
     * Gboard, SwiftKey, and others) fires per-character or multi-character
     * delete events that can land *inside* an `@npub1…` token, chopping it into
     * a truncated `@npub1abc…` run. A truncated run still matches the greedy
     * chip regex, so it keeps being treated as a chip — but at a different
     * length than the canonical token, which corrupts the underlying npub
     * reference and drives the composer's [VisualTransformation]/offset mapping
     * into an inconsistent state that crashes the field.
     *
     * This function makes that partial state impossible. Given the user's
     * [oldText] and the proposed post-edit [newText], when the edit is a
     * contiguous deletion whose removed range *partially* overlaps one or more
     * chips (intersects a chip's interior without removing the whole chip), it
     * widens the deletion to also remove every partially-hit chip in full,
     * returning the repaired [Insertion]. A chip that the edit already removes
     * entirely, or doesn't touch at all, is left as-is.
     *
     * Returns null when the edit is not a contiguous deletion, or when no chip
     * is partially deleted (the caller then applies the IME edit verbatim).
     *
     * Unlike [wholeChipBackspace] this handles deletions of any length (and a
     * selected-text-replaced-by-delete), because swipe-delete is not a clean
     * one-char backspace. The deleted range is derived from the old/new text
     * diff rather than the IME-reported caret, which is transient and sometimes
     * inconsistent mid-swipe.
     */
    fun repairChipDeletion(
        oldText: String,
        newText: String,
    ): Insertion? {
        // Must be a net deletion: the new text is strictly shorter.
        if (newText.length >= oldText.length) return null
        // Identify the contiguous deleted range in oldText by matching the
        // common prefix and suffix of old/new. This handles single-char
        // backspace, multi-char swipe-delete, and selection-replaced-by-delete
        // uniformly.
        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(oldText.length, newText.length) - prefix
        while (suffix < maxSuffix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        val delStart = prefix
        val delEnd = oldText.length - suffix
        // A pure contiguous deletion: removing oldText[delStart, delEnd) and
        // keeping both ends must reproduce newText exactly. If it doesn't, the
        // edit also inserted characters (e.g. autocorrect) — not our concern.
        if (oldText.substring(0, delStart) + oldText.substring(delEnd) != newText) return null
        if (delStart >= delEnd) return null

        // Find chips in the ORIGINAL text that the deletion only partially
        // covers. "Partial" = the deleted range overlaps the chip's interior
        // but the chip is not entirely contained in the deleted range.
        val chips = chipRanges(oldText)
        var widenedStart = delStart
        var widenedEnd = delEnd
        var touchedPartial = false
        for (chip in chips) {
            val chipStart = chip.first
            val chipEnd = chip.last + 1 // exclusive
            // No overlap with the deleted range at all → untouched chip.
            if (delEnd <= chipStart || delStart >= chipEnd) continue
            // Whole chip already inside the deleted range → cleanly removed.
            if (delStart <= chipStart && delEnd >= chipEnd) continue
            // Otherwise the deletion cuts into the chip: widen to swallow it.
            touchedPartial = true
            if (chipStart < widenedStart) widenedStart = chipStart
            if (chipEnd > widenedEnd) widenedEnd = chipEnd
        }
        if (!touchedPartial) return null

        val before = oldText.substring(0, widenedStart)
        val after = oldText.substring(widenedEnd)
        return Insertion(text = before + after, selection = before.length)
    }

    /**
     * Clamp a (possibly ranged) selection so neither endpoint rests *inside* a
     * mention chip. A chip is atomic: the caret may sit at its left or right
     * edge but never between its characters, and a selection edge inside a chip
     * snaps to the nearer chip edge. This is applied by the UI to every
     * post-edit selection so taps, long-presses, and arrow keys can't land the
     * caret in the middle of an `@npub1…` token (which would let normal edits
     * corrupt it or reopen the picker mid-token).
     *
     * Returns the clamped [start, end] pair (same order as given; a collapsed
     * caret stays collapsed). Endpoints already at an edge or in plain text are
     * returned unchanged.
     */
    data class Selection(
        val start: Int,
        val end: Int,
    )

    fun clampSelectionOutOfChips(
        text: String,
        start: Int,
        end: Int,
    ): Selection {
        val ranges = chipRanges(text)
        return Selection(
            start = clampOffsetOutOfChips(start, ranges),
            end = clampOffsetOutOfChips(end, ranges),
        )
    }

    /**
     * Push a single caret [offset] out of any chip it lands strictly inside,
     * snapping to whichever chip edge is closer (ties go to the right edge so a
     * forward tap/drag settles past the chip). Offsets at an edge — `chip.first`
     * or `chip.last + 1` — are already valid and returned as-is.
     */
    private fun clampOffsetOutOfChips(
        offset: Int,
        ranges: List<IntRange>,
    ): Int {
        for (r in ranges) {
            val leftEdge = r.first
            val rightEdge = r.last + 1
            if (offset > leftEdge && offset < rightEdge) {
                val distToLeft = offset - leftEdge
                val distToRight = rightEdge - offset
                return if (distToLeft < distToRight) leftEdge else rightEdge
            }
        }
        return offset
    }

    /**
     * If a mention chip ends exactly at index [end] in [text], return its
     * [start, end-1] inclusive char range; else null. A chip is `@npub1…`
     * beginning at start-of-text or after whitespace.
     */
    private fun chipEndingAt(
        text: String,
        end: Int,
    ): IntRange? {
        for (match in MENTION_CHIP.findAll(text)) {
            val r = match.range
            if (r.last + 1 != end) continue
            val at = r.first
            if (at == 0 || text[at - 1].isWhitespace()) return r
        }
        return null
    }

    /**
     * Ranges of every mention chip (`@npub1…` at a word boundary) in [text].
     * The UI uses this to paint the chip background tint in the composer so a
     * mention reads as a single styled token while editing.
     */
    fun chipRanges(text: String): List<IntRange> =
        MENTION_CHIP
            .findAll(text)
            .map { it.range }
            .filter { it.first == 0 || text[it.first - 1].isWhitespace() }
            .toList()

    data class ChipVisualRange(
        val original: IntRange,
        val transformed: IntRange,
    )

    data class ChipVisualText(
        val text: String,
        val ranges: List<ChipVisualRange>,
        val originalLength: Int,
    ) {
        /** Map a cursor/selection offset in the stored `@npub…` text to the visible label text. */
        fun originalToTransformed(offset: Int): Int {
            val normalized = offset.coerceIn(0, originalLength)
            var removedChars = 0
            for (range in ranges) {
                val originalStart = range.original.first
                val originalEnd = range.original.last + 1
                val transformedStart = range.transformed.first
                val transformedEnd = range.transformed.last + 1
                if (normalized <= originalStart) return (normalized - removedChars).coerceIn(0, text.length)
                if (normalized < originalEnd) {
                    val leftDistance = normalized - originalStart
                    val rightDistance = originalEnd - normalized
                    return if (leftDistance < rightDistance) transformedStart else transformedEnd
                }
                removedChars += (originalEnd - originalStart) - (transformedEnd - transformedStart)
            }
            return (normalized - removedChars).coerceIn(0, text.length)
        }

        /** Map a cursor/selection offset in the visible label text back to the stored `@npub…` text. */
        fun transformedToOriginal(offset: Int): Int {
            val normalized = offset.coerceIn(0, text.length)
            var removedChars = 0
            for (range in ranges) {
                val originalStart = range.original.first
                val originalEnd = range.original.last + 1
                val transformedStart = range.transformed.first
                val transformedEnd = range.transformed.last + 1
                if (normalized <= transformedStart) return (normalized + removedChars).coerceIn(0, originalLength)
                if (normalized < transformedEnd) {
                    val leftDistance = normalized - transformedStart
                    val rightDistance = transformedEnd - normalized
                    return if (leftDistance < rightDistance) originalStart else originalEnd
                }
                removedChars += (originalEnd - originalStart) - (transformedEnd - transformedStart)
            }
            return (normalized + removedChars).coerceIn(0, originalLength)
        }
    }

    /**
     * Build the composer's visible text for mention chips without changing the
     * stored text. The backing string remains canonical `@npub…`; only the
     * visual layer swaps each chip for its resolved display label, or a short
     * `@npub…` fallback while profile data is still unresolved. The label may
     * be shorter or longer than the stored chip.
     */
    fun visualText(
        text: String,
        candidates: List<Candidate>,
    ): ChipVisualText = visualText(text, candidatesByNpub(candidates))

    fun candidatesByNpub(candidates: List<Candidate>): Map<String, Candidate> = candidates.associateBy { it.npub.lowercase() }

    fun visualText(
        text: String,
        candidatesByNpub: Map<String, Candidate>,
    ): ChipVisualText {
        val ranges = chipRanges(text)
        if (ranges.isEmpty()) return ChipVisualText(text = text, ranges = emptyList(), originalLength = text.length)

        val transformed = StringBuilder()
        val transformedRanges = mutableListOf<ChipVisualRange>()
        var sourceOffset = 0
        for (range in ranges) {
            transformed.append(text.substring(sourceOffset, range.first))
            val transformedStart = transformed.length
            val npub = text.substring(range.first + 1, range.last + 1)
            transformed.append(chipDisplayLabel(npub, candidatesByNpub[npub.lowercase()]))
            val transformedEnd = transformed.length
            transformedRanges +=
                ChipVisualRange(
                    original = range,
                    transformed = transformedStart until transformedEnd,
                )
            sourceOffset = range.last + 1
        }
        transformed.append(text.substring(sourceOffset))
        return ChipVisualText(
            text = transformed.toString(),
            ranges = transformedRanges,
            originalLength = text.length,
        )
    }

    private fun chipDisplayLabel(
        npub: String,
        candidate: Candidate?,
    ): String {
        val resolvedName = candidate?.displayName?.trim()?.removePrefix("@")
        if (resolvedName.isNullOrBlank()) return "@${shortChipNpub(npub)}"
        if (resolvedName.equals(npub, ignoreCase = true)) return "@${shortChipNpub(npub)}"
        if (looksLikeFullNpub(resolvedName)) return "@${shortChipNpub(npub)}"

        return "@$resolvedName"
    }

    private fun looksLikeFullNpub(value: String): Boolean = value.length == CANONICAL_NPUB_LENGTH && RAW_NPUB_DISPLAY_NAME.matches(value)

    private fun shortChipNpub(npub: String): String = IdentityFormatter.short(npub, prefix = 8, suffix = 0)
}
