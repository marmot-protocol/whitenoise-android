package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostr.NostrEventVerifier
import dev.ipf.whitenoise.android.core.nostr.NostrRelayQueryClient
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.normalizeRelayUrls
import dev.ipf.whitenoise.android.state.relayUrlPassesResolveTimeCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
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
)

internal sealed interface NostrEventCardState {
    data object Loading : NostrEventCardState

    data class Loaded(
        val card: NostrEventCardModel,
    ) : NostrEventCardState

    data object NotFound : NostrEventCardState

    data object Invalid : NostrEventCardState

    data object Failed : NostrEventCardState
}

/** Conversation-lifetime single-flight resolver with bounded in-memory state. */
internal class NostrEventCardResolver(
    parentScope: CoroutineScope,
    private val relayProvider: suspend () -> List<String>,
    private val fetchEvents: suspend (List<String>, JSONObject) -> List<NostrEvent> = defaultNostrEventQuery(),
    private val verifyEvent: (NostrEvent) -> Boolean = NostrEventVerifier::verifies,
    private val maxEntries: Int = MAX_RESOLVED_ENTRIES,
    private val verificationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val lock = Any()
    private val relayLock = Mutex()
    private val states = LinkedHashMap<String, MutableStateFlow<NostrEventCardState>>(16, 0.75f, true)
    private val jobs = HashMap<String, Job>()
    private var cachedRelays: List<String>? = null

    fun state(reference: NostrEventReference): StateFlow<NostrEventCardState> =
        synchronized(lock) {
            states[reference.stableId]?.also { return@synchronized it }
            makeRoomForNewEntryLocked()
            MutableStateFlow<NostrEventCardState>(NostrEventCardState.Loading).also { state ->
                states[reference.stableId] = state
                startResolveLocked(reference, state)
            }
        }

    fun retry(
        reference: NostrEventReference,
        observedState: StateFlow<NostrEventCardState>? = null,
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
            state.value = NostrEventCardState.Loading
            startResolveLocked(reference, state, refreshRelays = true)
        }
    }

    private fun startResolveLocked(
        reference: NostrEventReference,
        state: MutableStateFlow<NostrEventCardState>,
        refreshRelays: Boolean = false,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) { resolve(reference, state, refreshRelays) }
        jobs[reference.stableId] = job
        job.start()
    }

    private suspend fun resolve(
        reference: NostrEventReference,
        state: MutableStateFlow<NostrEventCardState>,
        refreshRelays: Boolean = false,
    ) {
        val result =
            try {
                val relays = relays(refreshRelays)
                if (relays.isEmpty()) {
                    NostrEventCardState.Failed
                } else {
                    resolveFromRelays(reference, relays)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NostrEventCardState.Failed
            }
        val currentJob = coroutineContext[Job]
        synchronized(lock) {
            if (jobs[reference.stableId] === currentJob && states[reference.stableId] === state) {
                state.value = result
                jobs.remove(reference.stableId)
            }
        }
    }

    private suspend fun relays(refresh: Boolean): List<String> =
        relayLock.withLock {
            if (refresh) cachedRelays = null
            cachedRelays ?: relayProvider().take(MAX_QUERY_RELAYS).also { cachedRelays = it }
        }

    private suspend fun resolveFromRelays(
        reference: NostrEventReference,
        relays: List<String>,
    ): NostrEventCardState {
        val events = fetchEvents(relays, reference.exactFilter())
        return withContext(verificationDispatcher) {
            val verified = events.filter(verifyEvent)
            val matching = verified.filter { reference.matches(it) }
            val selected =
                matching
                    .sortedWith(
                        compareByDescending<NostrEvent> { it.createdAt }
                            .thenBy { it.id.lowercase(Locale.ROOT) },
                    ).firstOrNull()
            when {
                selected != null -> NostrEventCardState.Loaded(selected.toCardModel())
                events.isNotEmpty() -> NostrEventCardState.Invalid
                else -> NostrEventCardState.NotFound
            }
        }
    }

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

    override fun close() {
        scope.cancel()
        synchronized(lock) {
            jobs.clear()
            states.clear()
        }
    }

    private companion object {
        const val MAX_RESOLVED_ENTRIES = 64
        const val MAX_QUERY_RELAYS = 4
    }
}

private fun defaultNostrEventQuery(): suspend (List<String>, JSONObject) -> List<NostrEvent> {
    val client = NostrRelayQueryClient()
    return { relays, filter -> client.query(relays, filter, maxEvents = EVENT_CARD_QUERY_LIMIT).events }
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

private fun NostrEventReference.exactFilter(): JSONObject =
    when (this) {
        is NostrEventReference.Event ->
            JSONObject()
                .put("ids", JSONArray().put(eventIdHex))
                .put("limit", EXACT_EVENT_QUERY_LIMIT)
        is NostrEventReference.Address ->
            JSONObject()
                .put("authors", JSONArray().put(authorPubkeyHex))
                .put("kinds", JSONArray().put(kind.toLong()))
                .put("#d", JSONArray().put(identifier))
                .put("limit", EVENT_CARD_QUERY_LIMIT)
    }

private fun NostrEventReference.matches(event: NostrEvent): Boolean =
    when (this) {
        is NostrEventReference.Event ->
            event.id.equals(eventIdHex, ignoreCase = true)
        is NostrEventReference.Address ->
            event.pubkey.equals(authorPubkeyHex, ignoreCase = true) &&
                event.kind.toUInt() == kind &&
                event.firstTagValue("d") == identifier
    }

private const val MAX_PUBLIC_EVENT_RELAYS = 4
private const val EXACT_EVENT_QUERY_LIMIT = 1
private const val EVENT_CARD_QUERY_LIMIT = 8
