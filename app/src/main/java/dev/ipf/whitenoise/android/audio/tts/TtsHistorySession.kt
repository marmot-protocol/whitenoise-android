package dev.ipf.whitenoise.android.audio.tts

import androidx.annotation.VisibleForTesting
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Transport-visible progress of a pending read-aloud history edge request. */
sealed interface TtsHistoryEdgeState {
    val direction: TtsHistoryDirection

    data class Loading(
        override val direction: TtsHistoryDirection,
    ) : TtsHistoryEdgeState

    data class Failed(
        override val direction: TtsHistoryDirection,
    ) : TtsHistoryEdgeState
}

/** Stable protocol-free owner for one conversation-backed playback session. */
internal data class TtsConversationSource(
    val accountRef: String,
    val groupIdHex: String,
    val sessionId: Long,
)

/**
 * Canonical-timeline paging surface a read-aloud session drives. Backed by the
 * conversation's live subscription window — never by an Android-side cache.
 */
internal interface TtsHistoryPager {
    val hasMoreBefore: Boolean
    val hasMoreAfter: Boolean

    fun timelineRecords(): List<AppMessageRecordFfi>

    suspend fun loadOlder(): Boolean

    suspend fun loadNewer(): Boolean

    suspend fun ensureLoaded(
        messageIdHex: String,
        timelineAt: ULong,
    ): Boolean

    suspend fun projectSpeakable(record: AppMessageRecordFfi): TtsSpeakableEntry?
}

/**
 * Routes transport navigation for the active read-aloud queue and, for
 * conversation-backed sessions, resolves edge hits by paging the canonical
 * timeline instead of letting the queue complete early. All entry points run
 * on the main thread — page loads run in [scope] guarded by a generation that
 * every invalidation (stop, replacement, conversation switch) advances.
 */
class TtsHistorySession internal constructor(
    private val controller: TtsController,
    private val scope: CoroutineScope,
    private val resolvePager: (accountRef: String?, groupIdHex: String) -> TtsHistoryPager?,
) {
    private val _edgeState = MutableStateFlow<TtsHistoryEdgeState?>(null)
    val edgeState: StateFlow<TtsHistoryEdgeState?> = _edgeState.asStateFlow()

    private val mutableConversationSource = MutableStateFlow<TtsConversationSource?>(null)
    internal val conversationSource: StateFlow<TtsConversationSource?> = mutableConversationSource.asStateFlow()

    private var conversation: TtsConversationSource? = null
    private val historyRequests = StalenessGuard()
    private var pendingLoad: Job? = null
    private var liveTailAttached = true

    /** Optional barrier used by concurrency tests at the guarded settlement boundary. */
    @VisibleForTesting
    internal var settlementAwaiterForTests: (() -> Unit)? = null

    // The live conversation tail as last verified against the timeline. A
    // genuine arrival keeps this id in the loaded window, a window trim drops
    // it — that asymmetry is what tells the two apart at append time.
    private var lastKnownTimelineTailId: String? = null

    init {
        scope.launch {
            controller.state.collect { state ->
                val terminal = state is TtsState.Idle || state is TtsState.Error
                if (terminal && conversation != null) onSessionCleared()
            }
        }
    }

    /** Registers the conversation whose canonical timeline backs the new queue. */
    fun onConversationSessionStarted(
        accountRef: String?,
        groupIdHex: String,
    ) {
        val sourceAccount = accountRef?.takeIf { it.isNotBlank() }
        val sourceGroup = groupIdHex.takeIf { it.isNotBlank() }
        val sessionId = controller.state.value.sessionId
        if (sourceAccount == null || sourceGroup == null) {
            onSessionCleared()
            return
        }
        invalidatePending()
        conversation =
            TtsConversationSource(
                accountRef = sourceAccount,
                groupIdHex = sourceGroup,
                sessionId = sessionId,
            ).also { mutableConversationSource.value = it }
        val timelineTailId =
            resolvePager(sourceAccount, sourceGroup)
                ?.timelineRecords()
                ?.lastOrNull()
                ?.messageIdHex
        // A capped backlog start queues only the oldest slice, leaving the
        // queue tail mid-history. Claiming the live tail there would splice
        // arrivals next to unrelated old messages and strand the unqueued
        // remainder — the newer-edge walk pages it instead and reattaches
        // once the queue tail really is the timeline tail.
        liveTailAttached = timelineTailId != null && timelineTailId == controller.queuedMessageIds().lastOrNull()
        lastKnownTimelineTailId = timelineTailId
    }

    /** Queue stopped or replaced by non-conversation speech: paging detaches. */
    fun onSessionCleared() {
        invalidatePending()
        conversation = null
        mutableConversationSource.value = null
        liveTailAttached = true
        lastKnownTimelineTailId = null
    }

    /**
     * Gate for one live-continuation append. Extends only a session whose
     * newer edge still is the live tail — after paging away, an arriving
     * message would otherwise splice next to an unrelated older neighbour.
     * A timeline-window trim also moves the window's last id without any
     * arrival, so acceptance additionally requires the last verified tail to
     * still be present in the loaded window (advancing it on success), and
     * refuses while an edge load owns the window.
     */
    fun allowsLiveAppend(): Boolean {
        val convo = conversation ?: return true
        val consultTimeline = liveTailAttached && _edgeState.value !is TtsHistoryEdgeState.Loading
        val records =
            if (consultTimeline) {
                resolvePager(convo.accountRef, convo.groupIdHex)?.timelineRecords().orEmpty()
            } else {
                emptyList()
            }
        val knownTail = lastKnownTimelineTailId
        val accepted = knownTail != null && records.any { it.messageIdHex == knownTail }
        if (accepted) lastKnownTimelineTailId = records.last().messageIdHex
        return accepted
    }

    fun nextMessage() {
        navigate(TtsWindowSentenceTarget.First) { defer -> controller.skipNextMessage(defer) }
    }

    fun previousMessage() {
        navigate(TtsWindowSentenceTarget.First) { defer -> controller.skipPreviousMessage(defer) }
    }

    fun nextSentence() {
        navigate(TtsWindowSentenceTarget.First) { defer -> controller.skipNextSentence(defer) }
    }

    fun previousSentence() {
        navigate(TtsWindowSentenceTarget.Last) { defer -> controller.skipPreviousSentence(defer) }
    }

    /** Applies an in-window navigation immediately or starts the matching bounded edge walk. */
    private fun navigate(
        targetSentence: TtsWindowSentenceTarget,
        skip: (Boolean) -> TtsNavigationOutcome,
    ) {
        if (_edgeState.value is TtsHistoryEdgeState.Loading) return
        val convo = conversation
        if (convo == null) {
            skip(false)
            return
        }
        // The edge decision happens inside the controller lock, so a racing
        // engine callback can never turn "try to load" into an early
        // completion or an interior move into a bogus page request.
        when (skip(true)) {
            TtsNavigationOutcome.AtOlderEdge ->
                startEdgeLoad(convo, TtsHistoryDirection.Older, targetSentence)

            TtsNavigationOutcome.AtNewerEdge ->
                startEdgeLoad(convo, TtsHistoryDirection.Newer, targetSentence)

            else -> _edgeState.value = null
        }
    }

    /** Starts one edge walk and rejects its settlement after a newer history request. */
    private fun startEdgeLoad(
        convo: TtsConversationSource,
        direction: TtsHistoryDirection,
        targetSentence: TtsWindowSentenceTarget,
    ) {
        val startedGeneration = historyRequests.advance()
        _edgeState.value = TtsHistoryEdgeState.Loading(direction)
        pendingLoad =
            scope.launch {
                var pager: TtsHistoryPager? = null
                val result =
                    try {
                        pager = resolvePager(convo.accountRef, convo.groupIdHex)
                        val queued = controller.queuedMessagesSnapshot().filter { it.messageIdHex.isNotEmpty() }
                        val anchor =
                            if (direction == TtsHistoryDirection.Older) {
                                queued.firstOrNull()
                            } else {
                                queued.lastOrNull()
                            }
                        val resolvedPager = pager
                        if (resolvedPager == null || anchor == null) {
                            TtsHistoryEdgeWalk.Result.Failed
                        } else {
                            TtsHistoryEdgeWalk(resolvedPager, direction) {
                                !historyRequests.isCurrent(startedGeneration)
                            }.run(anchor.messageIdHex, anchor.timelineAt)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        TtsHistoryEdgeWalk.Result.Failed
                    }
                historyRequests.runIfCurrent(startedGeneration) {
                    settlementAwaiterForTests?.invoke()
                    if (conversation != convo) return@runIfCurrent
                    // Every reachable branch settles the queue's edge deferral: a
                    // final chunk that finished mid-request parked instead of ending
                    // playback, and only these outcomes can say how it resolves.
                    when (result) {
                        is TtsHistoryEdgeWalk.Result.Found ->
                            try {
                                // Found is only reachable through a resolved pager.
                                pager?.let { applyProjection(it, direction, targetSentence, result) }
                            } finally {
                                // An extension that landed already repositioned the
                                // parked terminal, one that was refused has nothing
                                // left to play. A throw must not strand either.
                                controller.settleEdgeRequest(TtsEdgeSettlement.Resolved)
                                _edgeState.value = null
                            }

                        TtsHistoryEdgeWalk.Result.EndOfHistory -> {
                            _edgeState.value = null
                            // Replaying the navigation call here instead would read
                            // a cursor the parked terminal has already moved.
                            controller.settleEdgeRequest(direction.endOfHistorySettlement())
                        }

                        TtsHistoryEdgeWalk.Result.Failed -> {
                            // Retry-by-re-tap needs the window and cursor intact.
                            controller.settleEdgeRequest(TtsEdgeSettlement.Retained)
                            _edgeState.value = TtsHistoryEdgeState.Failed(direction)
                        }

                        TtsHistoryEdgeWalk.Result.PageBound -> {
                            controller.settleEdgeRequest(TtsEdgeSettlement.Resolved)
                            _edgeState.value = null
                        }

                        // Unreachable: a stale walk means the generation already
                        // advanced, which the guard above rejected. Settling here
                        // would clobber whichever request re-armed since.
                        TtsHistoryEdgeWalk.Result.Stale -> Unit
                    }
                }
            }
    }

    /** Extends the queue with a current edge-walk projection and updates live-tail ownership. */
    private fun applyProjection(
        pager: TtsHistoryPager,
        direction: TtsHistoryDirection,
        targetSentence: TtsWindowSentenceTarget,
        found: TtsHistoryEdgeWalk.Result.Found,
    ) {
        // The requested target is the nearest speakable message beyond the
        // edge: last in window order for older paging, first for newer.
        val targetId =
            when (direction) {
                TtsHistoryDirection.Older -> found.entries.last().messageIdHex
                TtsHistoryDirection.Newer -> found.entries.first().messageIdHex
            }
        val tailBefore = controller.queuedMessageIds().lastOrNull()
        if (!controller.extendReadAloudWindow(direction, found.entries, targetId, targetSentence)) return
        val tailAfter = controller.queuedMessageIds().lastOrNull()
        liveTailAttached =
            when (direction) {
                // Evicting the newest edge detaches the session from the live tail.
                TtsHistoryDirection.Older -> liveTailAttached && tailAfter == tailBefore
                // Reattached only when the queue tail is the timeline's live
                // tail RIGHT NOW — a walk-time snapshot would miss an arrival
                // that landed between the walk and this apply.
                TtsHistoryDirection.Newer -> {
                    val timelineTailId = pager.timelineRecords().lastOrNull()?.messageIdHex
                    val attached = !pager.hasMoreAfter && timelineTailId != null && timelineTailId == tailAfter
                    if (attached) lastKnownTimelineTailId = timelineTailId
                    attached
                }
            }
    }

    /** Cancels the current edge walk and invalidates any completion already queued. */
    private fun invalidatePending() {
        historyRequests.advance()
        pendingLoad?.cancel()
        pendingLoad = null
        _edgeState.value = null
    }
}

/**
 * A genuine end of history keeps the pre-paging semantics of the tap that armed
 * the request: newer completes the session, older restarts the window.
 */
private fun TtsHistoryDirection.endOfHistorySettlement(): TtsEdgeSettlement =
    when (this) {
        TtsHistoryDirection.Older -> TtsEdgeSettlement.RestartedWindow
        TtsHistoryDirection.Newer -> TtsEdgeSettlement.CompletedSession
    }

/**
 * One bounded hunt for the nearest speakable record beyond a window edge:
 * project what the loaded timeline already holds, otherwise page toward the
 * edge until something speakable, a deterministic bound, or an error appears.
 */
private class TtsHistoryEdgeWalk(
    private val pager: TtsHistoryPager,
    private val direction: TtsHistoryDirection,
    private val isStale: () -> Boolean,
) {
    sealed interface Result {
        data class Found(
            val entries: List<TtsSpeakableEntry>,
        ) : Result

        data object EndOfHistory : Result

        data object PageBound : Result

        data object Failed : Result

        data object Stale : Result
    }

    suspend fun run(
        anchorId: String,
        anchorTimelineAt: ULong,
    ): Result {
        var anchorRecoveryAttempted = false
        var pagesLoaded = 0
        var result: Result? = null
        while (result == null) {
            val records = pager.timelineRecords()
            val anchorIndex = records.indexOfFirst { it.messageIdHex == anchorId }
            result =
                if (anchorIndex < 0) {
                    // The queue window and the timeline window drifted apart
                    // (the chat was scrolled elsewhere while listening). One
                    // recovery walk toward the anchor, then retryable failure.
                    recoverAnchor(anchorId, anchorTimelineAt, anchorRecoveryAttempted)
                        .also { anchorRecoveryAttempted = true }
                } else {
                    val found = projectBeyond(records, anchorIndex)
                    when {
                        found != null -> if (isStale()) Result.Stale else found
                        !hasMore() -> Result.EndOfHistory
                        pagesLoaded >= MAX_EDGE_PAGES_PER_REQUEST -> Result.PageBound
                        else -> {
                            pagesLoaded += 1
                            advanceOnePage()
                        }
                    }
                }
        }
        return result
    }

    private suspend fun recoverAnchor(
        anchorId: String,
        anchorTimelineAt: ULong,
        alreadyAttempted: Boolean,
    ): Result? =
        when {
            alreadyAttempted || !pager.ensureLoaded(anchorId, anchorTimelineAt) -> Result.Failed
            isStale() -> Result.Stale
            else -> null
        }

    private suspend fun advanceOnePage(): Result? {
        val advanced = if (direction == TtsHistoryDirection.Older) pager.loadOlder() else pager.loadNewer()
        return when {
            isStale() -> Result.Stale
            advanced -> null
            hasMore() -> Result.Failed
            else -> Result.EndOfHistory
        }
    }

    private fun hasMore(): Boolean =
        when (direction) {
            TtsHistoryDirection.Older -> pager.hasMoreBefore
            TtsHistoryDirection.Newer -> pager.hasMoreAfter
        }

    private suspend fun projectBeyond(
        records: List<AppMessageRecordFfi>,
        anchorIndex: Int,
    ): Result.Found? {
        val beyond =
            when (direction) {
                TtsHistoryDirection.Newer -> records.subList(anchorIndex + 1, records.size)
                TtsHistoryDirection.Older -> records.subList(0, anchorIndex).asReversed()
            }
        val nearestFirst = mutableListOf<TtsSpeakableEntry>()
        for (record in beyond.take(MAX_PROJECTION_ATTEMPTS_PER_REQUEST)) {
            pager.projectSpeakable(record)?.let(nearestFirst::add)
            if (nearestFirst.size >= EDGE_FILL_TARGET_MESSAGES) break
        }
        if (nearestFirst.isEmpty()) return null
        val ordered = if (direction == TtsHistoryDirection.Older) nearestFirst.asReversed() else nearestFirst
        return Result.Found(entries = ordered.toList())
    }
}

// One edge request pages at most this many canonical pages while hunting for
// the next speakable record, so a long run of filtered records stays bounded.
private const val MAX_EDGE_PAGES_PER_REQUEST = 6

// How many speakable messages one edge request projects into the window —
// enough to keep taps cheap without prefetching meaningful history.
private const val EDGE_FILL_TARGET_MESSAGES = 10

// Projection attempts one scan of the window may spend hunting for those
// speakable records. Sized to a full live timeline window, so a realistic
// window is scanned whole while an unbounded run of unspeakable records
// cannot pile up projection work on the main thread per tap. Exhausting it
// reads exactly like finding nothing more nearby.
private const val MAX_PROJECTION_ATTEMPTS_PER_REQUEST = 200
