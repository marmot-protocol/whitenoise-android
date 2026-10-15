package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.CachedIdentityProjectionFfi

private const val IDENTITY_PAGE_SIZE = 100

/**
 * Reads local profile seeds in request order, using at most 100 identities per native call.
 *
 * A missing canonical identity is skipped, but a malformed or failed page discards the entire
 * preload so earlier pages cannot masquerade as a complete account list. Coroutine cancellation
 * propagates to the caller. Returned empty profile fields remain authoritative cleared values.
 */
internal suspend fun loadAccountSwitchIdentityPages(
    ids: List<String>,
    read: suspend (List<String>) -> List<CachedIdentityProjectionFfi>,
): List<AccountSwitchProfileSeed> =
    runCatchingCancellable {
        ids.chunked(IDENTITY_PAGE_SIZE).flatMap { page ->
            val rows = read(page)
            check(rows.size == page.size) { "Identity page changed its requested row count" }
            rows.zip(page).mapNotNull { (row, requested) ->
                check(row.requestedId == requested) { "Identity page changed its requested order" }
                val canonical = row.accountIdHex ?: return@mapNotNull null
                check(canonical.equals(requested, ignoreCase = true)) { "Identity page changed its identity" }
                accountSwitchProfileSeed(requested, row.profile, row.resolvedName)
            }
        }
    }.getOrDefault(emptyList())
