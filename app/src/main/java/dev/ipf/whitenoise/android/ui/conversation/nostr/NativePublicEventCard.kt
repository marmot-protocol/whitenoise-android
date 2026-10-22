package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.PublicEventPreviewFfi
import dev.ipf.marmotkit.PublicEventPreviewStateFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState.Loaded
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

/**
 * MDK's network-free cached state for up to [NOSTR_EVENT_CACHE_BATCH] authored references, in input order, with
 * cached author metadata and prepared preview text, so a restored card is complete on its first frame (#2238).
 * A null entry means nothing is cached. MDK owns persistence, signature verification, deletion and refresh policy.
 */
internal suspend fun WhiteNoiseAppState.readCachedPublicEventCards(
    accountRef: String?,
    references: List<String>,
): List<NostrEventCardState?> {
    if (accountRef == null || references.isEmpty()) return references.map { null }
    // A cold read may open the account's encrypted cache, so it always rides the binding IO hop.
    val previews = marmotIo { cachedPublicEventPreviews(accountRef, references) }
    return publicEventCardStates(previews)
}

/**
 * Asks MDK to refresh one authored reference and returns the card for its local state afterwards. MDK keeps prior
 * content when a fetch fails or finds nothing and only ever moves an addressable event forward.
 */
internal suspend fun WhiteNoiseAppState.refreshPublicEventCard(
    accountRef: String?,
    reference: String,
): NostrEventCardState? {
    if (accountRef == null) return NostrEventCardState.Failed
    val preview = marmotIo { resolvePublicEventPreview(accountRef, reference) }
    return publicEventCardStates(listOf(preview)).single()
}

/**
 * Refreshes the card author's kind-0 profile at most once per [PublicEventAuthorRefreshThrottle] interval, then
 * returns the card with MDK's cached author metadata. A cached card already carries that metadata, so this only
 * updates the header in place when the profile itself changed. A failed refresh does not use up the interval, so the
 * next card for that author tries again. Offline is such a failure: MDK's `refreshProfile` throws when no relay
 * connects. A refresh that reached a relay but found no newer profile returns normally and keeps the interval.
 */
internal suspend fun WhiteNoiseAppState.refreshPublicEventCardAuthor(
    loaded: Loaded,
    throttle: PublicEventAuthorRefreshThrottle = PublicEventAuthorRefreshThrottle.shared,
): Loaded {
    val pubkey = loaded.card.authorPubkeyHex
    val refreshed =
        throttle.refreshing(pubkey) {
            val relays = publicEventCardRelays()
            marmotIo {
                refreshProfile(pubkey, relays)
                userProfile(pubkey)?.toEventAuthorMetadata()
            }
        } ?: return loaded
    val author = refreshed.value
    return if (author == null || author == loaded.card.authorMetadata) {
        loaded
    } else {
        loaded.copy(card = loaded.card.copy(authorMetadata = author))
    }
}

/** Maps MDK previews to card states, enriching present events from one batched cached-identity read. */
private suspend fun WhiteNoiseAppState.publicEventCardStates(previews: List<PublicEventPreviewFfi>): CardStates {
    val events = previews.map { it.verifiedEvent() }
    val authors = cachedEventAuthors(events.mapNotNull { it?.pubkey })
    return previews.mapIndexed { index, preview ->
        when (preview.state) {
            PublicEventPreviewStateFfi.PRESENT ->
                events[index]?.let { event ->
                    val card =
                        event.toCardModel().copy(authorMetadata = authors[event.pubkey.lowercase(Locale.ROOT)])
                    Loaded(card, preparedPreview(card))
                } ?: NostrEventCardState.Invalid
            // MDK records an author's NIP-09 deletion durably, so it settles the card.
            PublicEventPreviewStateFfi.DELETED -> NostrEventCardState.NotFound
            PublicEventPreviewStateFfi.MISSING -> null
            PublicEventPreviewStateFfi.UNSUPPORTED -> NostrEventCardState.Invalid
        }
    }
}

/** The signature-verified event MDK attached to a present preview, or null when it is absent or unreadable. */
private fun PublicEventPreviewFfi.verifiedEvent(): NostrEvent? =
    eventJson
        ?.takeIf { state == PublicEventPreviewStateFfi.PRESENT }
        ?.let { json -> runCatching { NostrEvent.fromJson(JSONObject(json)) }.getOrNull() }

/** Cached kind-0 metadata for [pubkeys] from one cache-only read, keyed by lowercase pubkey. */
private suspend fun WhiteNoiseAppState.cachedEventAuthors(pubkeys: List<String>): AuthorsByPubkey {
    val distinct = pubkeys.map { it.lowercase(Locale.ROOT) }.distinct().take(MAX_IDENTITY_PAGE)
    if (distinct.isEmpty()) return emptyMap()
    val rows =
        try {
            marmotIo { cachedIdentityProjections(distinct) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Author metadata is enrichment: the verified event still renders without it.
            emptyList()
        }
    return rows
        .mapNotNull { row ->
            row.profile?.toEventAuthorMetadata()?.let { row.requestedId.lowercase(Locale.ROOT) to it }
        }.toMap()
}

/** Parses [card]'s preview text now, off the main thread, so its first frame needs no second pass. */
private suspend fun WhiteNoiseAppState.preparedPreview(card: NostrEventCardModel): NostrEventPreparedPreview? =
    try {
        prepareNostrEventPreview(card) { text -> parseMarkdownOrEmpty(text) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

/** Sanitized author label and picture, or null when the profile offers neither. */
private fun UserProfileMetadataFfi.toEventAuthorMetadata(): NostrEventAuthorMetadata? =
    NostrEventAuthorMetadata(
        displayName = ProfileSanitizer.displayName(displayName) ?: ProfileSanitizer.displayName(name),
        pictureUrl = ProfileSanitizer.protocolImageUrl(picture),
    ).takeIf { it.displayName != null || it.pictureUrl != null }

/**
 * Limits network author-profile refreshes for event cards to one per author per [intervalNanos], so reopening a
 * conversation, or a card hitting the cache, does not refetch kind-0 metadata every time.
 */
internal class PublicEventAuthorRefreshThrottle(
    private val intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lastRefresh = LinkedHashMap<String, Long>(16, 0.75f, true)

    /**
     * Runs [refresh] when [pubkey] has not been refreshed within the interval, and returns its result, or null when
     * throttled. The slot is held while [refresh] runs, so concurrent cards share one refresh, and is kept only when
     * it succeeds. A failure or cancellation releases it before rethrowing.
     */
    suspend fun <T> refreshing(
        pubkey: String,
        refresh: suspend () -> T,
    ): Refreshed<T>? {
        if (!tryAcquire(pubkey)) return null
        var succeeded = false
        try {
            return Refreshed(refresh()).also { succeeded = true }
        } finally {
            if (!succeeded) release(pubkey)
        }
    }

    /** One completed refresh's result, distinguishing a null result from a throttled call. */
    data class Refreshed<T>(
        val value: T,
    )

    /** Forgets [pubkey]'s attempt, so the next card for it may refresh again. */
    private fun release(pubkey: String) {
        synchronized(lastRefresh) { lastRefresh.remove(pubkey.lowercase(Locale.ROOT)) }
    }

    /** True, and records the attempt, when [pubkey] has not been refreshed within the interval. */
    private fun tryAcquire(pubkey: String): Boolean {
        val key = pubkey.lowercase(Locale.ROOT)
        val now = nanoTime()
        return synchronized(lastRefresh) {
            val previous = lastRefresh[key]
            if (previous != null && now - previous < intervalNanos) {
                false
            } else {
                lastRefresh[key] = now
                val iterator = lastRefresh.entries.iterator()
                while (lastRefresh.size > capacity && iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
                true
            }
        }
    }

    companion object {
        /** The process-wide throttle production cards share across conversations and resolvers. */
        val shared = PublicEventAuthorRefreshThrottle()

        private const val DEFAULT_INTERVAL_NANOS = 30L * 60L * 1_000_000_000L
        private const val DEFAULT_CAPACITY = 512
    }
}

/** Card states in the order of the previews they were read from. */
private typealias CardStates = List<NostrEventCardState?>

/** Cached author metadata keyed by lowercase pubkey. */
private typealias AuthorsByPubkey = Map<String, NostrEventAuthorMetadata>

/** The most account IDs one cached-identity page accepts. */
private const val MAX_IDENTITY_PAGE = 100
