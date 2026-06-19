package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi

/**
 * In-conversation / cross-conversation message-content search primitives.
 *
 * The matcher is intentionally a pure, allocation-light helper so it can be
 * shared between the conversation in-chat search (#292) and the chat-list
 * content search (#290) without either screen growing its own copy of the
 * substring + navigation logic.
 *
 * Scope, per #292:
 *   - Case-insensitive substring match on plaintext message bodies only.
 *   - Searchable kinds are the user-authored text bodies (chat / replies /
 *     text notes). Reactions (kind 7), deletes (kind 5), group-system events
 *     (kind 1210), agent-stream markers, and attachment-only media rows are
 *     NOT searchable text — they carry no plaintext the user typed and would
 *     produce confusing matches against synthesized labels or filenames.
 *   - The caller supplies the already-displayed text (edit-resolved) so a
 *     query matches what the user actually sees on the row.
 */
object MessageSearch {
    /**
     * Whether [record] contributes a searchable plaintext body.
     *
     * A row is searchable when it is a plain text message (not a reaction,
     * delete, group-system event, or agent-stream marker) AND it actually
     * carries text. Media rows are searchable only by their accompanying
     * caption text — never by attachment filenames (explicitly out of scope
     * for v1) — which falls out naturally because [displayedText] for a
     * caption-less media row resolves to a blank body here.
     */
    fun isSearchable(
        record: AppMessageRecordFfi,
        displayedText: String,
    ): Boolean {
        if (MessageProjector.isReaction(record)) return false
        if (MessageProjector.isDelete(record)) return false
        if (MessageProjector.isGroupSystem(record)) return false
        if (MessageProjector.isStreamStart(record)) return false
        return displayedText.isNotBlank()
    }

    /** Trim + lowercase a query or body to the canonical match form. */
    fun normalize(value: String): String = value.trim().lowercase()

    /**
     * Indices into [bodies] (in the same order) whose normalized text
     * contains the normalized [query].
     *
     * An empty/blank query yields no matches — an in-chat search with no
     * needle has nothing to navigate to, which is distinct from the chat-list
     * filter (where empty means "show everything"). Callers that want the
     * "show everything" semantics should short-circuit before calling this.
     */
    fun matchIndices(
        bodies: List<String>,
        query: String,
    ): List<Int> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        for (i in bodies.indices) {
            if (normalize(bodies[i]).contains(needle)) out += i
        }
        return out
    }

    /**
     * Realign the active-match cursor when the match set changes.
     *
     * As the user paginates older messages, the match set grows and the
     * previously-focused match keeps its position only if we re-resolve the
     * cursor against the new set. Callers pass the message id the cursor was
     * pinned to plus the freshly-computed ordered match ids; this returns the
     * cursor's new ordinal, or 0 when the old target is gone (or there was no
     * prior target). Returns -1 when there are no matches at all.
     */
    fun resolveCursor(
        orderedMatchIds: List<String>,
        pinnedMatchId: String?,
    ): Int {
        if (orderedMatchIds.isEmpty()) return -1
        if (pinnedMatchId == null) return 0
        val idx = orderedMatchIds.indexOf(pinnedMatchId)
        return if (idx >= 0) idx else 0
    }

    /**
     * Step the match cursor with wrap-around.
     *
     * [current] is the active ordinal in `[0, matchCount)`. A [forward] step
     * advances toward newer matches (next), a backward step toward older
     * (previous). Wraps at both ends so navigation is a loop. Returns -1 when
     * there are no matches.
     */
    fun step(
        current: Int,
        matchCount: Int,
        forward: Boolean,
    ): Int {
        if (matchCount <= 0) return -1
        val safeCurrent = current.coerceIn(0, matchCount - 1)
        return if (forward) {
            (safeCurrent + 1) % matchCount
        } else {
            (safeCurrent - 1 + matchCount) % matchCount
        }
    }
}
