package dev.ipf.whitenoise.android.core

import java.util.Locale

/**
 * Pure, Compose-free name matching for the New Chat / Create Group / Add Member
 * recipient picker. Candidates are derived in the UI from already-loaded
 * chat-list state (no profile-enumeration FFI exists); this only does the
 * ordering + matching, so it stays unit-testable. Semantics mirror the
 * chat-list search: case-insensitive, trimmed, substring, de-duped by hex,
 * prefix matches first.
 *
 * The picker shows a browsable list up-front (#831): with a blank query the
 * candidates are returned as-is (the caller sorts them by recency), and typing
 * filters that same list in place by display name.
 */
object RecipientSearch {
    /**
     * Where a candidate came from, so the row can show a dim hint. [InDm] wins
     * over [InGroups] when the same person is both a DM partner and a fellow
     * group member — the direct relationship is the stronger signal.
     */
    sealed interface Source {
        data object InDm : Source

        data class InGroups(
            val count: Int,
        ) : Source
    }

    data class Candidate(
        val accountIdHex: String,
        val displayName: String,
        val npub: String,
        val source: Source? = null,
    )

    /**
     * Candidates to show in the picker for [query], excluding [activeAccountIdHex]
     * and [excludeAccountIdHexes] (e.g. current group members), de-duped by hex
     * (first wins). The input order is preserved for the browse case, so the
     * caller can pre-sort by recency.
     *
     * - Blank query: every remaining candidate, in input order.
     * - Non-blank query: display-name substring matches only, prefix matches
     *   before contained (stable within each).
     */
    fun browse(
        query: String,
        candidates: List<Candidate>,
        activeAccountIdHex: String?,
        excludeAccountIdHexes: Set<String> = emptySet(),
    ): List<Candidate> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val active = activeAccountIdHex?.trim()?.lowercase(Locale.ROOT)
        val excluded = excludeAccountIdHexes.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        val seen = HashSet<String>()
        val prefix = ArrayList<Candidate>()
        val contained = ArrayList<Candidate>()
        for (candidate in candidates) {
            val hex = candidate.accountIdHex.trim().lowercase(Locale.ROOT)
            if (hex.isEmpty()) continue
            if (active != null && hex == active) continue
            if (hex in excluded) continue
            if (!seen.add(hex)) continue
            if (needle.isEmpty()) {
                prefix.add(candidate)
                continue
            }
            val name = candidate.displayName.trim().lowercase(Locale.ROOT)
            when {
                name.startsWith(needle) -> prefix.add(candidate)
                name.contains(needle) -> contained.add(candidate)
            }
        }
        return prefix + contained
    }

    /**
     * Display-name substring matches for [query], excluding [activeAccountIdHex],
     * de-duped by hex (first wins), prefix matches before contained (stable
     * within each). Blank query returns empty. Kept for callers that only want
     * the type-to-search behavior; the browsable picker uses [browse].
     */
    fun filterByDisplayName(
        query: String,
        candidates: List<Candidate>,
        activeAccountIdHex: String?,
    ): List<Candidate> {
        if (query.trim().isEmpty()) return emptyList()
        return browse(query, candidates, activeAccountIdHex)
    }
}
