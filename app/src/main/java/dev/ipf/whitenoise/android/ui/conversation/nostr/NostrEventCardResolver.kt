package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.normalizeRelayUrls
import dev.ipf.whitenoise.android.state.relayUrlPassesResolveTimeCheck
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState.Loaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

internal enum class NostrEventCardKind {
    Note,
    Article,
    Video,
    Release,
    File,
    Generic,
}

internal data class NostrEventCardModel(
    val kind: NostrEventCardKind,
    val eventIdHex: String,
    val authorPubkeyHex: String,
    val createdAt: Long,
    val eventKind: Int,
    val title: String?,
    val summary: String?,
    val metadata: List<String> = emptyList(),
    val readerBody: String? = null,
    val mediaUrl: String? = null,
    val mediaMimeType: String? = null,
    val imageUrls: List<String> = emptyList(),
    val authorMetadata: NostrEventAuthorMetadata? = null,
    /** An article's signed publication time in Unix seconds, when its `published_at` tag holds a valid one. */
    val publishedAt: Long? = null,
)

/**
 * The one date a card or reader shows for this event (#3317): an article's publication time when it has a valid
 * one, otherwise the event's own creation time. The two are never shown side by side.
 */
internal fun NostrEventCardModel.displayEpochSeconds(): Long = publishedAt ?: createdAt

internal data class NostrEventAuthorMetadata(
    val displayName: String?,
    val pictureUrl: String?,
)

internal sealed interface NostrEventCardState {
    data object Loading : NostrEventCardState

    data class Loaded(
        val card: NostrEventCardModel,
        /** Preview text prepared by the read off the main thread, so the first frame is already complete. */
        val preview: NostrEventPreparedPreview? = null,
    ) : NostrEventCardState

    data object NotFound : NostrEventCardState

    data object Invalid : NostrEventCardState

    data object Failed : NostrEventCardState
}

/**
 * Reads MDK's cached projection for up to [NOSTR_EVENT_CACHE_BATCH] authored references, in input order. A null
 * entry means nothing is cached yet. The read never touches the network.
 */
internal typealias NostrEventCacheRead = suspend (List<String>) -> List<NostrEventCardState?>

/** Asks MDK to refresh one authored reference, returning its local state afterwards or null when still missing. */
internal typealias NostrEventRefresh = suspend (String) -> NostrEventCardState?

/** Refreshes a loaded card's author profile, returning the card unchanged when nothing new is known. */
internal typealias NostrEventAuthorRefresh = suspend (NostrEventCardState.Loaded) -> NostrEventCardState.Loaded

/**
 * Conversation-lifetime adapter over MDK's cached-read and refresh contract for public event cards (#2238).
 *
 * MDK owns fetching, signature verification, deletion, addressable monotonicity and persistence. This class only
 * keeps the presented state for each card. A card already in [snapshots] starts from that state, so a reopened
 * conversation or a recreated resolver never shows a loading frame for a card it has resolved before. Only a card
 * with nothing cached starts as [NostrEventCardState.Loading]. A background refresh never replaces a loaded card
 * with a loading, missing or failed state, only with newer content or MDK's authorized deletion.
 */
internal class NostrEventCardResolver(
    parentScope: CoroutineScope,
    private val readCached: NostrEventCacheRead,
    private val refresh: NostrEventRefresh,
    private val refreshAuthor: NostrEventAuthorRefresh? = null,
    private val snapshots: NostrEventCardSnapshots = NostrEventCardSnapshots(),
    private val maxEntries: Int = MAX_RESOLVED_ENTRIES,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val lock = Any()
    private val states = LinkedHashMap<String, MutableStateFlow<NostrEventCardState>>(16, 0.75f, true)
    private val jobs = HashMap<String, Job>()
    private val pendingCacheReads = LinkedHashMap<String, CompletableDeferred<NostrEventCardState?>>()
    private var cacheReadJob: Job? = null

    /**
     * The presented state of [reference], created on first use. Its initial value is the snapshot of a previously
     * resolved card, so the first composed frame is complete, otherwise [NostrEventCardState.Loading].
     */
    fun state(
        reference: NostrEventReference,
        authoredReference: String = reference.stableId,
    ): StateFlow<NostrEventCardState> =
        synchronized(lock) {
            states[reference.stableId]?.also { return@synchronized it }
            makeRoomForNewEntryLocked()
            val seeded = snapshots[reference.stableId]
            MutableStateFlow(seeded ?: NostrEventCardState.Loading).also { state ->
                states[reference.stableId] = state
                startResolveLocked(reference, state, authoredReference, readCache = seeded == null)
            }
        }

    /** Refreshes [reference] again, keeping a loaded card on screen while the refresh runs. */
    fun retry(
        reference: NostrEventReference,
        observedState: StateFlow<NostrEventCardState>? = null,
        authoredReference: String = reference.stableId,
    ) {
        synchronized(lock) {
            val state =
                states[reference.stableId] ?: run {
                    makeRoomForNewEntryLocked()
                    @Suppress("UNCHECKED_CAST")
                    val retainedState = observedState as? MutableStateFlow<NostrEventCardState>
                    (retainedState ?: MutableStateFlow<NostrEventCardState>(NostrEventCardState.Loading)).also {
                        states[reference.stableId] = it
                    }
                }
            jobs.remove(reference.stableId)?.cancel()
            if (state.value !is NostrEventCardState.Loaded) state.value = NostrEventCardState.Loading
            startResolveLocked(reference, state, authoredReference, readCache = false)
        }
    }

    /** Starts the single resolve job owning [state], replacing any earlier one for the same reference. */
    private fun startResolveLocked(
        reference: NostrEventReference,
        state: MutableStateFlow<NostrEventCardState>,
        authoredReference: String,
        readCache: Boolean,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                resolve(reference, authoredReference, state, readCache)
            }
        jobs[reference.stableId] = job
        job.start()
    }

    /** Publishes the cached projection when the card has no state yet, then MDK's refreshed state and author. */
    private suspend fun resolve(
        reference: NostrEventReference,
        authoredReference: String,
        state: MutableStateFlow<NostrEventCardState>,
        readCache: Boolean,
    ) {
        val currentJob = coroutineContext[Job]
        if (readCache && !publishCached(reference, authoredReference, state, currentJob)) return
        val outcome = refresh.outcomeFor(authoredReference)
        coroutineContext.ensureActive()
        val settled = settleRefresh(state.value, outcome)
        val author = refreshAuthor
        if (settled is NostrEventCardState.Loaded && author != null) {
            if (publish(reference, state, currentJob, settled, complete = false)) {
                publish(reference, state, currentJob, enrichedAuthor(author, settled), complete = true)
            }
        } else {
            publish(reference, state, currentJob, settled, complete = true)
        }
    }

    /** Publishes the cached state, if any, and returns whether this job still owns the card. */
    private suspend fun publishCached(
        reference: NostrEventReference,
        authoredReference: String,
        state: MutableStateFlow<NostrEventCardState>,
        currentJob: Job?,
    ): Boolean {
        val cached = cachedState(authoredReference)
        coroutineContext.ensureActive()
        return cached == null || publish(reference, state, currentJob, cached, complete = false)
    }

    /** Joins the next batched cache read; a failed read is treated as a miss so the refresh still runs. */
    private suspend fun cachedState(authoredReference: String): NostrEventCardState? {
        val pending =
            synchronized(lock) {
                val deferred = pendingCacheReads.getOrPut(authoredReference) { CompletableDeferred() }
                if (cacheReadJob?.isActive != true) cacheReadJob = scope.launch { drainCacheReads() }
                deferred
            }
        return pending.await()
    }

    /** Reads every queued reference in batches of at most [NOSTR_EVENT_CACHE_BATCH], one native call per batch. */
    private suspend fun drainCacheReads() {
        // Cards composed in the same frame queue their references before the first batch is read.
        yield()
        while (true) {
            val batch =
                synchronized(lock) {
                    val next = pendingCacheReads.entries.take(NOSTR_EVENT_CACHE_BATCH).map { it.key to it.value }
                    next.forEach { (key, _) -> pendingCacheReads.remove(key) }
                    if (next.isEmpty()) cacheReadJob = null
                    next
                }
            if (batch.isEmpty()) return
            val results =
                try {
                    readCached(batch.map { it.first })
                } catch (cancelled: CancellationException) {
                    batch.forEach { (_, deferred) -> deferred.cancel(cancelled) }
                    throw cancelled
                } catch (_: Exception) {
                    // A failed projection read is not proof that the event is absent: fall through to refresh.
                    emptyList()
                }
            batch.forEachIndexed { index, (_, deferred) -> deferred.complete(results.getOrNull(index)) }
        }
    }

    /** Sets [value] only while [currentJob] still owns [state], and remembers settled values for later frames. */
    private fun publish(
        reference: NostrEventReference,
        state: MutableStateFlow<NostrEventCardState>,
        currentJob: Job?,
        value: NostrEventCardState,
        complete: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (jobs[reference.stableId] !== currentJob || states[reference.stableId] !== state) {
                false
            } else {
                state.value = value
                snapshots.put(reference.stableId, value)
                if (complete) jobs.remove(reference.stableId)
                true
            }
        }

    /** Evicts a settled entry, or failing that the eldest, so the resolver stays bounded across long histories. */
    private fun makeRoomForNewEntryLocked() {
        val limit = maxEntries.coerceAtLeast(1)
        if (states.size < limit) return
        val candidate =
            states.entries.firstOrNull { (key, state) ->
                state.value != NostrEventCardState.Loading && !jobs.containsKey(key)
            } ?: states.entries.firstOrNull()
                ?: return
        if (candidate.value.value == NostrEventCardState.Loading) {
            candidate.value.value = NostrEventCardState.Failed
        }
        states.remove(candidate.key)
        jobs.remove(candidate.key)?.cancel()
    }

    /** Cancels every read and refresh, so no late result reaches a retired conversation. */
    override fun close() {
        scope.cancel()
        synchronized(lock) {
            jobs.clear()
            states.clear()
            pendingCacheReads.clear()
            cacheReadJob = null
        }
    }

    private companion object {
        const val MAX_RESOLVED_ENTRIES = 64
    }
}

/** What a refresh produced: MDK's local state afterwards, or a failure before it answered. */
private sealed interface RefreshOutcome {
    data class Completed(
        val state: NostrEventCardState?,
    ) : RefreshOutcome

    data object Failed : RefreshOutcome
}

/** MDK's refreshed local state, or [RefreshOutcome.Failed] when the refresh itself failed. */
private suspend fun NostrEventRefresh.outcomeFor(authoredReference: String): RefreshOutcome =
    try {
        RefreshOutcome.Completed(this(authoredReference))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RefreshOutcome.Failed
    }

/** The card with refreshed author metadata, or unchanged when the author refresh fails. */
private suspend fun enrichedAuthor(
    author: NostrEventAuthorRefresh,
    loaded: NostrEventCardState.Loaded,
): NostrEventCardState.Loaded {
    val enriched =
        try {
            author(loaded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loaded
        }
    coroutineContext.ensureActive()
    return enriched
}

/**
 * The state a card shows after a refresh. Known-good content is never replaced by a transient miss or failure, and
 * author metadata or prepared preview text the refreshed card lacks is carried over from the same event author and
 * text. MDK's authorized deletion and newer content do replace it.
 */
private fun settleRefresh(
    previous: NostrEventCardState,
    outcome: RefreshOutcome,
): NostrEventCardState {
    val refreshed = (outcome as? RefreshOutcome.Completed)?.state
    val previousLoaded = previous as? NostrEventCardState.Loaded
    return when {
        refreshed is NostrEventCardState.Loaded -> refreshed.carryingPresentationOf(previousLoaded)
        refreshed == null || refreshed == NostrEventCardState.Failed ->
            when {
                previous != NostrEventCardState.Loading && previous != NostrEventCardState.Failed -> previous
                outcome is RefreshOutcome.Completed && refreshed == null -> NostrEventCardState.NotFound
                else -> NostrEventCardState.Failed
            }
        else -> refreshed
    }
}

/** This refreshed card with [previous]'s author metadata and preview filled in where they still apply. */
private fun Loaded.carryingPresentationOf(previous: Loaded?): Loaded {
    val author =
        card.authorMetadata
            ?: previous?.card?.authorMetadata.takeIf { previous?.card?.authorPubkeyHex == card.authorPubkeyHex }
    val withAuthor = if (author == card.authorMetadata) card else card.copy(authorMetadata = author)
    // An unchanged refresh keeps the previous instance, so the card does not recompose at all. Prepared previews
    // are compared through their card, because parsed documents hold arrays that only compare by identity.
    return when {
        previous == null -> this
        withAuthor == previous.card -> previous
        else -> NostrEventCardState.Loaded(withAuthor, preview ?: previous.preview?.takeIf { it.describes(card) })
    }
}

internal suspend fun WhiteNoiseAppState.publicEventCardRelays(): List<String> {
    val lists = accountRelayLists()
    val candidates =
        if (lists == null) {
            MarmotClient.bootstrapRelays
        } else {
            lists.defaultRelays + lists.nip65.relays + lists.bootstrapRelays + MarmotClient.bootstrapRelays
        }
    return withContext(Dispatchers.IO) {
        normalizeRelayUrls(candidates)
            .asSequence()
            .filter(::relayUrlPassesResolveTimeCheck)
            .take(MAX_PUBLIC_EVENT_RELAYS)
            .toList()
    }
}

/** The most public-event references MDK accepts in one cached read. */
internal const val NOSTR_EVENT_CACHE_BATCH = 16

private const val MAX_PUBLIC_EVENT_RELAYS = 4
