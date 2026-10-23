package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import dev.ipf.whitenoise.android.core.mayNameNostrEventCardReference
import dev.ipf.whitenoise.android.core.nostrEventReferences
import dev.ipf.whitenoise.android.state.CONVERSATION_WINDOW_MAX_ROWS

/**
 * The presented state of each public event card a conversation has already read from MDK, keyed by the reference's
 * stable id (#2238).
 *
 * This is not a second protocol store: MDK's account-scoped projection stays the only durable source, and every entry
 * here was produced by reading it. The conversation controller owns one instance, fills it off the main thread before
 * its first page is shown, and every [NostrEventCardResolver] it creates writes settled results back. A card composed
 * for a reference found here therefore starts complete, including after the resolver is recreated, without a
 * blocking binding call on the main thread.
 */
internal class NostrEventCardSnapshots(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val entries = LinkedHashMap<String, NostrEventCardState>(16, 0.75f, true)

    // References a warm asked MDK about and found nothing cached for, with when. A hit lives in entries instead.
    private val misses = LinkedHashMap<String, Long>(16, 0.75f, true)

    /**
     * True when [stableId] has a snapshot, or a warm found it uncached within the last [MISS_MEMORY_MILLIS], so
     * reading it again now would most likely learn nothing new. A miss is forgotten after that, or as soon as a
     * resolver settles the card, since MDK may have cached it meanwhile.
     */
    fun isKnown(stableId: String): Boolean =
        synchronized(entries) {
            stableId in entries || misses[stableId]?.let { nowMs() - it < MISS_MEMORY_MILLIS } == true
        }

    /** The settled state last read for [stableId], or null when the conversation has not read it yet. */
    operator fun get(stableId: String): NostrEventCardState? = synchronized(entries) { entries[stableId] }

    /** Remembers a settled [state]; loading and failed states are transient and never seed a first frame. */
    fun put(
        stableId: String,
        state: NostrEventCardState,
    ) {
        if (!state.isSettled()) return
        synchronized(entries) {
            entries[stableId] = state
            misses.remove(stableId)
            evictLocked()
        }
    }

    /**
     * Reads MDK's cached projection for the cards [records] will show, in [order], keeps every hit and returns how
     * many references it read. A warm reads at most the snapshot capacity, so it can never evict its own snapshots;
     * the capacity covers the most cards a conversation window can name (see [DEFAULT_CAPACITY]). References with a
     * snapshot are skipped, but an earlier miss is asked about again, since MDK may have cached it since, and a
     * reference a resolver settled while the read ran keeps the resolver's newer state. Each
     * batch is kept as soon as it is read, so a warm stopped part-way keeps what it finished. Only parsed records name
     * cards, so the warm never parses Markdown itself.
     */
    suspend fun warm(
        records: List<TimelineMessageRecordFfi>,
        order: Comparator<TimelineMessageRecordFfi> = NEWEST_FIRST,
        readCached: NostrEventCacheRead,
    ): Int {
        val missing =
            nostrEventCardWarmOccurrences(records, capacity, order)
                .filter { synchronized(entries) { it.reference.stableId !in entries } }
        missing.chunked(NOSTR_EVENT_CACHE_BATCH).forEach { batch ->
            val states = readCached(batch.map(NostrEventReferenceOccurrence::authoredReference))
            synchronized(entries) {
                val readAt = nowMs()
                batch.zip(states).forEach { (occurrence, state) ->
                    val stableId = occurrence.reference.stableId
                    if (state != null && state.isSettled()) {
                        entries.putIfAbsent(stableId, state)
                        misses.remove(stableId)
                    } else if (stableId !in entries) {
                        misses[stableId] = readAt
                    }
                }
                evictLocked()
            }
        }
        return missing.size
    }

    /** Drops the least recently used entries, and remembered misses, beyond [capacity] each. */
    private fun evictLocked() {
        listOf(entries.keys, misses.keys).forEach { keys ->
            val iterator = keys.iterator()
            while (keys.size > capacity.coerceAtLeast(1) && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private companion object {
        /**
         * Every card one conversation window can name: [CONVERSATION_WINDOW_MAX_ROWS] rows of at most
         * [MAX_CARDS_PER_MESSAGE] cards each, so a warm of the whole window keeps all of its snapshots. The bound is
         * per conversation controller, and the app can retain several controllers at once (one per open or recently
         * open conversation), each with its own snapshots, so the process-wide total is a multiple of it.
         */
        val DEFAULT_CAPACITY = CONVERSATION_WINDOW_MAX_ROWS.toInt() * MAX_CARDS_PER_MESSAGE

        /** How long a miss keeps an on-screen row from being read again; MDK may cache the card meanwhile. */
        const val MISS_MEMORY_MILLIS = 30_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** True for states that describe what MDK knows rather than an in-flight or failed read. */
private fun NostrEventCardState.isSettled(): Boolean =
    this != NostrEventCardState.Loading &&
        this != NostrEventCardState.Failed

/**
 * The card references [records] will render, messages in [order] (newest first by default), at most
 * [MAX_CARDS_PER_MESSAGE] per message and [maxReferences] overall. Only messages whose text names a bech32 event
 * entity are considered, and only through their parsed content — a message whose Markdown is not hydrated yet names
 * nothing here, so the hydration parse is the only one.
 */
internal fun nostrEventCardWarmOccurrences(
    records: List<TimelineMessageRecordFfi>,
    maxReferences: Int,
    order: Comparator<TimelineMessageRecordFfi> = NEWEST_FIRST,
): List<NostrEventReferenceOccurrence> {
    val occurrences = LinkedHashMap<String, NostrEventReferenceOccurrence>()
    val candidates =
        records
            .filter { mayRenderNostrEventCard(it) && it.contentTokens.blocks.isNotEmpty() }
            .sortedWith(order)
    for (record in candidates) {
        if (occurrences.size >= maxReferences) break
        nostrEventReferences(record.contentTokens).take(MAX_CARDS_PER_MESSAGE).forEach { occurrence ->
            if (occurrences.size < maxReferences) occurrences.putIfAbsent(occurrence.reference.stableId, occurrence)
        }
    }
    return occurrences.values.toList()
}

/** A cheap text check: an undeleted message may show a card only when [mayNameNostrEventCard] holds for it. */
internal fun mayRenderNostrEventCard(record: TimelineMessageRecordFfi): Boolean =
    !record.deleted &&
        mayNameNostrEventCard(record.plaintext)

/**
 * True when [text] carries a form that renders an event card — a `nostr:` event URI or an HTTP(S) link with a NIP-19
 * event segment — so every card hold and the chat-list seed apply the same check.
 */
internal fun mayNameNostrEventCard(text: String): Boolean = mayNameNostrEventCardReference(text)

/** Newest message first, the order a reader scrolling up from the latest messages meets them in. */
internal val NEWEST_FIRST: Comparator<TimelineMessageRecordFfi> =
    compareByDescending<TimelineMessageRecordFfi> { it.timelineAt }.thenByDescending { it.messageIdHex }
