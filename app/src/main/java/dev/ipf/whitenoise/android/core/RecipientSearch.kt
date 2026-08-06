package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MatchQualityFfi
import dev.ipf.marmotkit.MatchedFieldFfi
import dev.ipf.marmotkit.UserDirectorySearchResultFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import java.util.Locale

/**
 * Pure, Compose-free name matching for the New Chat / Create Group / Add Member
 * recipient picker. Known candidates come from already-loaded chat-list state;
 * live discovery candidates come from Marmot's lifecycle-bound user search.
 * This owns only pure ordering and matching so it stays unit-testable.
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
        /** Existing unnamed DM group to open locally when [source] is [InDm]. */
        val existingDmGroupIdHex: String? = null,
        /** Ephemeral profile metadata returned by live discovery. */
        val searchProfile: UserProfileMetadataFfi? = null,
        /** Social distance from the active account; 255 means off-graph discovery. */
        val searchRadius: UByte? = null,
        val isFollowing: Boolean = false,
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
        // Ids the engine already matched against this query. The local filter
        // only sees names and nip05, so it must not re-judge a result matched on
        // a field it can't read (about) or one with no published profile at all.
        preMatchedAccountIdHexes: Set<String> = emptySet(),
    ): List<Candidate> {
        val needle = folded(query)
        val active = activeAccountIdHex?.trim()?.lowercase(Locale.ROOT)
        val excluded = excludeAccountIdHexes.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        val preMatched = preMatchedAccountIdHexes.mapTo(HashSet()) { it.normalized() }
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
            val names =
                listOfNotNull(
                    candidate.displayName,
                    candidate.searchProfile?.displayName,
                    candidate.searchProfile?.name,
                ).map(::folded)
            when {
                names.any { it.startsWith(needle) } -> prefix.add(candidate)
                names.any { it.contains(needle) } -> contained.add(candidate)
                matchesIdentity(candidate, needle) -> contained.add(candidate)
                hex in preMatched -> contained.add(candidate)
            }
        }
        return prefix + contained
    }

    /** Merge streamed discovery with known people without losing local chat provenance. */
    fun merge(
        known: List<Candidate>,
        discovered: List<Candidate>,
        activeAccountIdHex: String?,
        excludeAccountIdHexes: Set<String> = emptySet(),
        followedAccountIds: Set<String> = emptySet(),
    ): List<Candidate> {
        val active = activeAccountIdHex?.normalized()
        val excluded = excludeAccountIdHexes.mapTo(HashSet()) { it.normalized() }
        val followed = followedAccountIds.mapTo(HashSet()) { it.normalized() }
        val discoveredById = discovered.associateBy { it.accountIdHex.normalized() }
        val seen = HashSet<String>()
        val mergedKnown =
            known.map { candidate ->
                discoveredById[candidate.accountIdHex.normalized()]?.let { remote ->
                    candidate.copy(
                        searchProfile = remote.searchProfile,
                        searchRadius = remote.searchRadius,
                        isFollowing = remote.isFollowing,
                    )
                } ?: candidate
            }
        return (mergedKnown + discovered)
            .filter { candidate ->
                val id = candidate.accountIdHex.normalized()
                id.isNotEmpty() && id != active && id !in excluded && seen.add(id)
            }.map { candidate ->
                // The follow list covers everyone, not just whoever the directory
                // happened to return, so a followed local contact ranks and reads
                // the same as a followed remote.
                if (candidate.isFollowing || candidate.accountIdHex.normalized() !in followed) {
                    candidate
                } else {
                    candidate.copy(isFollowing = true)
                }
            }.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Candidate>> { it.value.isFollowing }
                    .thenBy { it.index },
            ).map { it.value }
    }

    /** Enrich known people before filtering so discovery-only matches retain local chat provenance. */
    fun mergeAndBrowse(
        query: String,
        known: List<Candidate>,
        discovered: List<Candidate>,
        activeAccountIdHex: String?,
        excludeAccountIdHexes: Set<String> = emptySet(),
        followedAccountIds: Set<String> = emptySet(),
    ): List<Candidate> =
        browse(
            query = query,
            candidates = merge(known, discovered, activeAccountIdHex, excludeAccountIdHexes, followedAccountIds),
            activeAccountIdHex = activeAccountIdHex,
            excludeAccountIdHexes = excludeAccountIdHexes,
            preMatchedAccountIdHexes = discovered.mapTo(HashSet()) { it.accountIdHex.normalized() },
        )

    fun discoveredCandidates(
        results: List<UserDirectorySearchResultFfi>,
        followedAccountIds: Set<String>,
    ): List<Candidate> {
        val followed = followedAccountIds.mapTo(HashSet()) { it.normalized() }
        return sortedUniqueResults(results, followed).map { result ->
            Candidate(
                accountIdHex = result.accountIdHex.normalized(),
                displayName =
                    ProfileSanitizer.displayName(result.profile?.displayName)
                        ?: ProfileSanitizer.displayName(result.profile?.name)
                        ?: IdentityFormatter.short(result.npub),
                npub = result.npub,
                searchProfile = result.profile,
                searchRadius = result.radius,
                isFollowing = result.accountIdHex.normalized() in followed,
            )
        }
    }

    internal fun sortedUniqueResults(
        results: List<UserDirectorySearchResultFfi>,
        followedAccountIds: Set<String>,
    ): List<UserDirectorySearchResultFfi> {
        val followed = followedAccountIds.mapTo(HashSet()) { it.normalized() }
        val seen = HashSet<String>()
        return results
            .sortedWith(
                compareByDescending<UserDirectorySearchResultFfi> { it.accountIdHex.normalized() in followed }
                    .thenBy { it.radius }
                    .thenByDescending { it.providerRank ?: Double.NEGATIVE_INFINITY }
                    .thenBy { matchQualityRank(it.matchQuality) }
                    .thenBy { matchedFieldRank(it.matchedField) }
                    .thenBy { it.accountIdHex },
            ).filter { seen.add(it.accountIdHex.normalized()) }
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

    private fun matchesIdentity(
        candidate: Candidate,
        needle: String,
    ): Boolean {
        val nip05Matches =
            candidate.searchProfile
                ?.nip05
                ?.let(::folded)
                ?.contains(needle) == true
        val identityPrefixMatches =
            needle.length >= MIN_IDENTITY_QUERY_LENGTH &&
                (
                    candidate.npub.lowercase(Locale.ROOT).startsWith(needle) ||
                        candidate.accountIdHex.lowercase(Locale.ROOT).startsWith(needle)
                )
        return nip05Matches || identityPrefixMatches
    }

    private fun matchQualityRank(quality: MatchQualityFfi): Int =
        when (quality) {
            MatchQualityFfi.EXACT -> 0
            MatchQualityFfi.PREFIX -> 1
            MatchQualityFfi.CONTAINS -> 2
        }

    private fun matchedFieldRank(field: MatchedFieldFfi): Int =
        when (field) {
            MatchedFieldFfi.NAME -> 0
            MatchedFieldFfi.NIP05 -> 1
            MatchedFieldFfi.DISPLAY_NAME -> 2
            MatchedFieldFfi.ABOUT -> MATCHED_FIELD_ABOUT_RANK
            MatchedFieldFfi.NPUB -> MATCHED_FIELD_NPUB_RANK
            MatchedFieldFfi.PUBKEY -> MATCHED_FIELD_PUBKEY_RANK
        }

    private fun String.normalized(): String = trim().lowercase(Locale.ROOT)

    private fun folded(value: String): String =
        java.text.Normalizer
            .normalize(value.trim(), java.text.Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)

    private const val MIN_IDENTITY_QUERY_LENGTH = 4
    private const val MATCHED_FIELD_ABOUT_RANK = 3
    private const val MATCHED_FIELD_NPUB_RANK = 4
    private const val MATCHED_FIELD_PUBKEY_RANK = 5
}
