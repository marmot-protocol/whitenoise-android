package dev.ipf.darkmatter.core

import java.util.Locale

/**
 * Pure, Compose-free name matching for the New Chat / Create Group recipient
 * search. Candidates are derived in the UI from already-loaded chat-list state
 * (no profile-enumeration FFI exists); this only does the matching, so it stays
 * unit-testable. Semantics mirror the chat-list search: case-insensitive,
 * trimmed, substring, de-duped by hex, prefix matches first.
 */
object RecipientSearch {
    data class Candidate(
        val accountIdHex: String,
        val displayName: String,
        val npub: String,
    )

    /**
     * Display-name substring matches for [query], excluding [activeAccountIdHex],
     * de-duped by hex (first wins), prefix matches before contained (stable
     * within each). Blank query returns empty.
     */
    fun filterByDisplayName(
        query: String,
        candidates: List<Candidate>,
        activeAccountIdHex: String?,
    ): List<Candidate> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()
        val active = activeAccountIdHex?.trim()?.lowercase(Locale.ROOT)
        val seen = HashSet<String>()
        val prefix = ArrayList<Candidate>()
        val contained = ArrayList<Candidate>()
        for (candidate in candidates) {
            val hex = candidate.accountIdHex.trim().lowercase(Locale.ROOT)
            if (hex.isEmpty()) continue
            if (active != null && hex == active) continue
            if (!seen.add(hex)) continue
            val name = candidate.displayName.trim().lowercase(Locale.ROOT)
            when {
                name.startsWith(needle) -> prefix.add(candidate)
                name.contains(needle) -> contained.add(candidate)
            }
        }
        return prefix + contained
    }
}
