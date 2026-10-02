package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.RecipientSearch

/** A complete NIP-05 address remains a directory query while its separate HTTPS lookup runs. */
internal fun usesRecipientDirectorySearch(
    query: String,
    accountIdHex: (String) -> String? = { null },
): Boolean =
    isPlainNameQuery(query, accountIdHex) ||
        ChatListIdentifierSearch.classify(query) is ChatListIdentifierSearch.Identifier.Nip05

/**
 * Directory matches are discovery, not proof of a NIP-05 claim. A resolved
 * identity takes precedence, including a self/member result that the caller
 * cannot select. Never fall back to a different key after successful lookup.
 */
@Suppress("LongParameterList") // Mirrors the existing directory projection with its exclusions and follow state.
internal fun recipientDirectoryMatches(
    query: String,
    resolvedHex: String?,
    known: List<RecipientSearch.Candidate>,
    discovered: List<RecipientSearch.Candidate>,
    activeAccountIdHex: String?,
    excludeAccountIdHexes: Set<String> = emptySet(),
    followedAccountIds: Set<String> = emptySet(),
    accountIdHex: (String) -> String? = { null },
): List<RecipientSearch.Candidate> =
    if (resolvedHex != null || (query.isNotBlank() && !usesRecipientDirectorySearch(query, accountIdHex))) {
        emptyList()
    } else {
        RecipientSearch.mergeAndBrowse(
            query,
            known,
            discovered,
            activeAccountIdHex,
            excludeAccountIdHexes,
            followedAccountIds,
        )
    }
