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
     * The npub bech32 grammar (lowercase, no `b`/`i`/`o`/`1` in the body).
     * Used both to recognize an already-inserted chip run and to bound the
     * whole-token backspace. A real npub is `npub1` + 58 body chars (length
     * 63), but matching the full run greedily lets us treat a partially-typed
     * or future-length entity as one token too.
     */
    private val NPUB_RUN = Regex("npub1[ac-hj-np-z02-9]+")

    /** A composer mention chip: `@` immediately followed by an npub run. */
    private val MENTION_CHIP = Regex("@npub1[ac-hj-np-z02-9]+")

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
        // Don't reopen the picker when the caret is sitting in an already
        // inserted `@npub1…` chip.
        if (query.startsWith("npub1") && NPUB_RUN.matchAt(query, 0)?.range?.last == query.lastIndex) {
            return null
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
     * the deleted character was the last char of an `@npub1…` chip (i.e. the
     * caret was at the chip's right edge), return the [Insertion] that instead
     * removes the whole chip in one keypress. Returns null when the edit isn't
     * a right-edge-of-chip backspace, so the caller applies the IME edit as-is.
     *
     * This makes the chip feel atomic on delete without storing a hidden span:
     * the literal `@npub1…` run is the chip, and we just widen the deletion to
     * cover all of it.
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
        // Find a chip in oldText whose right edge is exactly at oldCaret.
        val chip = chipEndingAt(oldText, oldCaret) ?: return null
        val before = oldText.substring(0, chip.first)
        val after = oldText.substring(chip.last + 1)
        return Insertion(text = before + after, selection = before.length)
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
}
