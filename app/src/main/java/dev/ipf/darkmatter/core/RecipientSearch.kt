package dev.ipf.darkmatter.core

import java.util.Locale

/**
 * Pure, Compose-free matching/derivation for the New Chat / Create Group
 * display-name search (issue #291).
 *
 * The Android app must not keep its own cache of Dark Matter protocol data
 * (see AGENTS.md), so there is no "list all known profiles" / "search
 * profiles" FFI to call. The candidate set is instead DERIVED in the UI from
 * the already-loaded chat-list state (each chat's member snapshot) and the
 * existing per-account profile accessors, then fed into [filterByDisplayName]
 * here. Keeping the matching here makes it unit-testable without standing up
 * Compose, mirroring [ChatListIdentifierSearch].
 *
 * Matching semantics mirror the chat-list search (`applyChatListSearchAndFilter`
 * in the UI): case-insensitive, trimmed, substring containment. Results are
 * de-duped by account hex and ordered prefix-matches-first so the closest
 * names surface at the top of the list.
 */
object RecipientSearch {
    /**
     * One searchable recipient candidate derived from local chat-list state.
     *
     * @param accountIdHex the member's Nostr account id (lowercase hex). The
     *   de-dupe + active-account exclusion key.
     * @param displayName the resolved display label (kind:0 name, account
     *   label, or short-npub fallback) used for substring matching.
     * @param npub the bech32 npub for display in the result row.
     */
    data class Candidate(
        val accountIdHex: String,
        val displayName: String,
        val npub: String,
    )

    /**
     * Filter [candidates] to those whose [Candidate.displayName] contains the
     * trimmed, lowercased [query] as a substring, excluding the active account
     * ([activeAccountIdHex], compared case-insensitively) and de-duping by
     * account hex (first occurrence wins).
     *
     * Ordering: prefix matches (name starts with the query) come before
     * contained-only matches; within each bucket the original candidate order
     * is preserved (a stable sort), so the caller can pre-sort by recency or
     * name if it wants a secondary tie-break.
     *
     * A blank [query] returns an empty list — the name search only kicks in
     * once the user has typed something to match against.
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

    /**
     * Whether the active account already has an active chat with the target
     * account — either a 1:1 DM or a shared group — derived purely from the
     * supplied chat-list facts. The "already in chats" badge (#291) and the
     * "open existing chat" affordance both gate on this.
     *
     * @param hasDirectChat whether an existing 1:1 DM with the target exists
     *   (the UI passes `appState.existingDirectChat(...) != null`).
     * @param sharedGroupCount how many groups the active account shares with
     *   the target (the UI passes `sharedChatListItemsWith(...).size`).
     */
    fun alreadyInChats(
        hasDirectChat: Boolean,
        sharedGroupCount: Int,
    ): Boolean = hasDirectChat || sharedGroupCount > 0
}
