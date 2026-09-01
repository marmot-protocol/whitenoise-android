package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TtsState {
    /** Changes only when a new playback queue is started, not on pause or requeue. */
    val sessionId: Long
    val chunkIndex: Int
    val chunkCount: Int
    val messageIndex: Int
    val messageCount: Int
    val sentenceIndexWithinMessage: Int
    val sentenceCountWithinMessage: Int
    val messagePreview: String
    val messageProgressFraction: Float
    val messageProgressGeneration: Long
    val passage: TtsPassage?

    data class Idle(
        override val sessionId: Long = 0L,
        override val chunkIndex: Int = 0,
        override val chunkCount: Int = 0,
        override val messageIndex: Int = 0,
        override val messageCount: Int = 0,
        override val sentenceIndexWithinMessage: Int = 0,
        override val sentenceCountWithinMessage: Int = 0,
        override val messagePreview: String = "",
        override val messageProgressFraction: Float = 0f,
        override val messageProgressGeneration: Long = 0L,
        override val passage: TtsPassage? = null,
    ) : TtsState

    data class Speaking(
        override val sessionId: Long = 0L,
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val sentenceIndexWithinMessage: Int,
        override val sentenceCountWithinMessage: Int,
        override val messagePreview: String,
        override val messageProgressFraction: Float = 0f,
        override val messageProgressGeneration: Long = 0L,
        override val passage: TtsPassage? = null,
    ) : TtsState

    data class Paused(
        override val sessionId: Long = 0L,
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val sentenceIndexWithinMessage: Int,
        override val sentenceCountWithinMessage: Int,
        override val messagePreview: String,
        override val messageProgressFraction: Float = 0f,
        override val messageProgressGeneration: Long = 0L,
        override val passage: TtsPassage? = null,
    ) : TtsState

    data class Error(
        val error: TtsError,
        override val sessionId: Long = 0L,
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val sentenceIndexWithinMessage: Int,
        override val sentenceCountWithinMessage: Int,
        override val messagePreview: String,
        override val messageProgressFraction: Float = 0f,
        override val messageProgressGeneration: Long = 0L,
        override val passage: TtsPassage? = null,
    ) : TtsState
}

enum class TtsError {
    Network,
    Synthesis,
}

/**
 * What a navigation request did. Edge outcomes are only reported when the
 * caller asked for edge deferral — they mean the queue deliberately did NOT
 * complete or clamp, leaving the cursor for a history load to resolve.
 */
enum class TtsNavigationOutcome {
    Moved,
    Completed,
    AtOlderEdge,
    AtNewerEdge,
    Inactive,
}

/** Exact result of an explicit sentence seek within the active queue window. */
enum class TtsSeekResult {
    Repositioned,
    RepositionedAcrossMessages,
    MessageNotInWindow,
    SentenceOutOfRange,
    SessionInactive,
}

/**
 * How the outcome of a deferred edge request resolves the cursor it left
 * behind. The tap's meaning was fixed when it armed the deferral, so the
 * outcome only names the verdict — the queue applies it under its own lock,
 * where the cursor it has to reason about cannot drift underneath it.
 */
internal enum class TtsEdgeSettlement {
    /** The request repositioned playback itself, or had nothing to add. */
    Resolved,

    /** Retryable failure: the window and cursor stay put for a re-tap. */
    Retained,

    /** Older history really ended: the window replays from its first sentence. */
    RestartedWindow,

    /** Newer history really ended: the session ends, like an undeferred tail tap. */
    CompletedSession,
}

/** Which sentence of the target message a window replacement lands on. */
internal enum class TtsWindowSentenceTarget {
    First,
    Last,
}

/**
 * Pure message-aware sentence queue. The Android TTS owner supplies the two
 * engine operations so queue/progress behavior remains deterministic in tests.
 */
@Suppress("LargeClass") // Navigation, edge deferral, and progress share one stateful queue.
internal class TtsPlaybackQueue(
    private val stopEngine: () -> Unit,
    private val enqueue: (chunk: TtsChunk, utteranceId: String) -> Int,
    private val onTerminal: () -> Unit = {},
) {
    private val _state = MutableStateFlow<TtsState>(TtsState.Idle())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var messages: List<TtsQueuedMessage> = emptyList()
    private var projection = TtsQueueProjection.EMPTY
    private val chunks: List<TtsChunk>
        get() = projection.chunks
    private val messageSentenceCount: List<Int>
        get() = projection.messageSentenceCount
    private var currentIndex = 0
    private val playbackCallbacks = StalenessGuard()

    /** Current utterance token embedded in platform callback identifiers. */
    private val generation: Long
        get() = playbackCallbacks.capture()

    // staleness-exempt: observable progress version published to Compose state.
    private var messageProgressGeneration = 0L
    private var playbackSessionId: Long = 0L
    private var nextPlaybackSessionId: Long = 0L
    private val rangeTracker = TtsRangeTracker()
    private var refreshAtNextBoundary = false
    private var announceSenderForCurrentMessage = false
    private var senderAnnouncedAtMessageIndex: Int? = null
    private var pendingResumeAnnouncement: SenderAnnouncement? = null
    private var messageIndexAtPause: Int? = null

    // Generation of a deferred edge navigation the caller has not resolved
    // yet, and the generation whose final chunk finished while that request
    // was still in flight. Both are stamped rather than cleared: every reset
    // path advances the generation, so a late resolve is inert for the same
    // reason a stale utterance callback is.
    // staleness-exempt: captured playback-guard tokens, not counter owners.
    private var edgeRequestGeneration: Long? = null
    private var parkedTerminalGeneration: Long? = null
    private val progress = TtsPlaybackProgress()

    /** How a repositioned target treats its message's sender announcement. */
    private enum class SenderAnnouncement {
        // The target crossed into another message: announce its sender once.
        Announce,

        // The target stayed inside its message: never repeat the announcement.
        Suppress,

        // Not a user navigation: keep the sender-change playback heuristic.
        Natural,
    }

    /**
     * Applies changed enqueue-time parameters (speech rate) at the next chunk
     * boundary. The engine pre-buffers every remaining utterance at enqueue
     * time, so without a re-queue a mid-playback change would never land;
     * re-queueing only at the boundary keeps the current sentence unbroken.
     */
    fun refreshPendingChunksAtNextBoundary() {
        if (_state.value is TtsState.Speaking) refreshAtNextBoundary = true
    }

    /** Replaces playback with [messages] and starts a fresh callback lifetime. */
    fun start(
        messages: List<TtsQueuedMessage>,
        startSentenceIndex: Int = 0,
    ) {
        stopEngine()
        playbackCallbacks.advance()
        messageProgressGeneration += 1
        playbackSessionId = nextPlaybackSessionId
        nextPlaybackSessionId += 1
        rangeTracker.clear()
        refreshAtNextBoundary = false
        replaceMessages(messages)
        currentIndex =
            if (chunks.isEmpty()) {
                0
            } else {
                firstChunkIndexForSentence(startSentenceIndex.coerceAtLeast(0))
            }
        resetMessageProgress()
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        if (chunks.isEmpty()) {
            announceSenderForCurrentMessage = false
            senderAnnouncedAtMessageIndex = null
            _state.value = TtsState.Idle(sessionId = playbackSessionId)
            return
        }
        val messageIndex = projection.messageIndexForChunk(currentIndex)
        announceSenderForCurrentMessage = currentIndex != projection.firstChunkIndexOfMessage(messageIndex)
        senderAnnouncedAtMessageIndex = null
        enqueueFromCurrentIndex()
    }

    /**
     * Extends an active queue with more messages (auto-read live
     * continuation). No-op when idle or errored — appending must never
     * resurrect a finished session. While speaking, the new chunks enqueue
     * immediately behind the engine's pending utterances; while paused,
     * resume() re-enqueues everything from the current index anyway.
     */
    fun append(moreMessages: List<TtsQueuedMessage>): Boolean {
        val current = _state.value
        val active = current is TtsState.Speaking || current is TtsState.Paused
        // A live echo can race a history load that already queued the same
        // message — identity wins over arrival order.
        val queuedIds = messages.mapNotNullTo(hashSetOf()) { it.messageIdHex.takeIf(String::isNotEmpty) }
        val newMessages =
            if (active) {
                moreMessages.filter { it.messageIdHex.isEmpty() || queuedIds.add(it.messageIdHex) }
            } else {
                emptyList()
            }
        if (newMessages.isEmpty()) return false
        val appended = appendMessages(newMessages)
        if (current is TtsState.Speaking) {
            // A parked terminal chunk has already been spoken, so progress has
            // to move onto the appended run instead of waiting on a callback
            // that will never come again.
            if (parkedTerminalGeneration == generation) {
                parkedTerminalGeneration = null
                currentIndex = appended.first().index
            }
            publishSpeaking(currentIndex)
            for (chunk in appended) {
                val utteranceId = utteranceId(generation, chunk.index)
                val result = enqueueSubmitted(chunk, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    onError(utteranceId, result)
                    break
                }
            }
        } else {
            publishPaused(currentIndex)
        }
        return true
    }

    fun failBeforePlayback(
        error: TtsError,
        chunkCount: Int,
        messageCount: Int = 0,
        messagePreview: String = "",
    ) {
        fail(
            error = error,
            chunkIndex = 0,
            chunkCount = chunkCount,
            messageIndex = 0,
            messageCount = messageCount,
            sentenceIndex = 0,
            sentenceCount = 0,
            messagePreview = messagePreview,
        )
    }

    fun pause() {
        val speaking = _state.value as? TtsState.Speaking ?: return
        pauseAt(speaking.chunkIndex)
    }

    /** Freezes playback at [chunkIndex] after invalidating callbacks from the stopped engine queue. */
    private fun pauseAt(chunkIndex: Int) {
        val frozenPassage = (_state.value as? TtsState.Speaking)?.passage
        stopEngine()
        playbackCallbacks.advance()
        progress.clearSpokenPayloads()
        rangeTracker.clear()
        // Resume re-reads the rate per utterance anyway, a leaked flag would
        // only force a needless engine restart at the first boundary.
        refreshAtNextBoundary = false
        currentIndex = chunkIndex
        pendingResumeAnnouncement = null
        messageIndexAtPause = projection.messageIndexForChunk(currentIndex)
        publishPaused(currentIndex, frozenPassage)
    }

    fun resume() {
        val paused = _state.value as? TtsState.Paused ?: return
        currentIndex = paused.chunkIndex
        // A deferred Announce that navigated back to the message interrupted
        // by pause() would repeat a sender the listener already heard, so it
        // demotes to Suppress. A genuinely unheard sender keeps its Announce.
        val backAtPausedMessage = projection.messageIndexForChunk(currentIndex) == messageIndexAtPause
        val announcement =
            if (pendingResumeAnnouncement == SenderAnnouncement.Announce && backAtPausedMessage) {
                SenderAnnouncement.Suppress
            } else {
                pendingResumeAnnouncement
            }
        when (announcement) {
            SenderAnnouncement.Announce -> {
                announceSenderForCurrentMessage = true
                senderAnnouncedAtMessageIndex = null
            }

            SenderAnnouncement.Suppress -> {
                announceSenderForCurrentMessage = false
                senderAnnouncedAtMessageIndex = projection.messageIndexForChunk(currentIndex)
            }

            // The previous engine queue was stopped by pause(). Recompute
            // sender narration when the paused sentence is the first chunk of
            // a message — otherwise a changed speaker can resume without their
            // announcement.
            else -> {
                announceSenderForCurrentMessage = false
                senderAnnouncedAtMessageIndex = null
            }
        }
        pendingResumeAnnouncement = null
        enqueueFromCurrentIndex()
    }

    /** Clears the queue and makes every outstanding platform callback stale. */
    fun stop() {
        stopEngine()
        playbackCallbacks.advance()
        rangeTracker.clear()
        messages = emptyList()
        projection = TtsQueueProjection.EMPTY
        currentIndex = 0
        resetMessageProgress()
        announceSenderForCurrentMessage = false
        senderAnnouncedAtMessageIndex = null
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        _state.value = TtsState.Idle(sessionId = playbackSessionId)
    }

    fun skipNextMessage(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        val nextMessage = projection.messageIndexForChunk(currentIndex) + 1
        return when {
            nextMessage >= messages.size && deferAtEdge -> deferToEdge(TtsNavigationOutcome.AtNewerEdge)
            nextMessage >= messages.size -> {
                completeThroughNavigation()
                TtsNavigationOutcome.Completed
            }

            else -> {
                moveTo(projection.firstChunkIndexOfMessage(nextMessage), SenderAnnouncement.Announce)
                TtsNavigationOutcome.Moved
            }
        }
    }

    fun skipPreviousMessage(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        val currentMessage = projection.messageIndexForChunk(currentIndex)
        return if (currentMessage == 0 && deferAtEdge) {
            deferToEdge(TtsNavigationOutcome.AtOlderEdge)
        } else {
            val target = projection.firstChunkIndexOfMessage((currentMessage - 1).coerceAtLeast(0))
            moveTo(target, announcementForTarget(target))
            TtsNavigationOutcome.Moved
        }
    }

    fun skipNextSentence(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        val target = projection.firstChunkIndexAfterSentenceContaining(currentIndex)
        return when {
            target >= chunks.size && deferAtEdge -> deferToEdge(TtsNavigationOutcome.AtNewerEdge)
            target >= chunks.size -> {
                completeThroughNavigation()
                TtsNavigationOutcome.Completed
            }

            else -> {
                moveTo(target, announcementForTarget(target))
                TtsNavigationOutcome.Moved
            }
        }
    }

    fun skipPreviousSentence(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        // Only the very first chunk is a genuine boundary crossing — anywhere
        // later in the first sentence, "previous" restarts that sentence.
        return if (currentIndex == 0 && deferAtEdge) {
            deferToEdge(TtsNavigationOutcome.AtOlderEdge)
        } else {
            val currentSentenceStart = projection.firstChunkIndexOfSentenceContaining(currentIndex)
            val target =
                if (currentSentenceStart == 0) {
                    0
                } else {
                    projection.firstChunkIndexOfSentenceContaining(currentSentenceStart - 1)
                }
            moveTo(target, announcementForTarget(target))
            TtsNavigationOutcome.Moved
        }
    }

    /**
     * Repositions to the first chunk of [sentenceIndex] in [messageIdHex].
     * Unlike ordinary previous/next navigation, seeking to the current
     * sentence is an explicit replay request and therefore always requeues it.
     */
    @Suppress("ReturnCount") // Each result preserves the exact reason a requested seek could not move.
    fun seekTo(
        messageIdHex: String,
        sentenceIndex: Int,
    ): TtsSeekResult {
        if (!isNavigable()) return TtsSeekResult.SessionInactive
        val targetMessage = messages.indexOfFirst { it.messageIdHex == messageIdHex }
        if (targetMessage < 0) return TtsSeekResult.MessageNotInWindow
        if (sentenceIndex !in 0 until messageSentenceCount[targetMessage]) {
            return TtsSeekResult.SentenceOutOfRange
        }
        val target =
            projection.firstChunkIndexOfSentence(targetMessage, sentenceIndex)
                ?: return TtsSeekResult.SentenceOutOfRange
        // An explicit seek supersedes any in-flight history-edge request. Its
        // eventual settlement must not move the freshly chosen cursor.
        edgeRequestGeneration = null
        parkedTerminalGeneration = null
        val crossedMessage = projection.messageIndexForChunk(currentIndex) != targetMessage
        val announcement = if (crossedMessage) SenderAnnouncement.Announce else SenderAnnouncement.Suppress
        if (_state.value is TtsState.Paused) {
            currentIndex = target
            pendingResumeAnnouncement = announcement
            publishPaused(target)
        } else {
            requeueFrom(target, announcement)
        }
        return if (crossedMessage) {
            TtsSeekResult.RepositionedAcrossMessages
        } else {
            TtsSeekResult.Repositioned
        }
    }

    /**
     * Replaces the queued message window while a session is active, landing on
     * [targetMessageIdHex]. Bookkeeping is re-derived from message identity
     * because positions are not stable across prepends or eviction. While
     * speaking the engine restarts at the target — while paused only the
     * cursor moves, exactly like a paused navigation tap.
     */
    fun replaceWindow(
        window: List<TtsQueuedMessage>,
        targetMessageIdHex: String,
        targetSentence: TtsWindowSentenceTarget,
    ): Boolean {
        // An empty target would alias every ad-hoc message in the window.
        require(targetMessageIdHex.isNotEmpty()) { "window replacement needs a concrete target id" }
        val current = _state.value
        val active = current is TtsState.Speaking || current is TtsState.Paused
        val targetMessage = window.indexOfFirst { it.messageIdHex == targetMessageIdHex }
        if (!active || targetMessage < 0) return false
        messageProgressGeneration += 1
        val currentMessageId = messageIdAt(projection.messageIndexForChunk(currentIndex))
        val announcedId = senderAnnouncedAtMessageIndex?.let(::messageIdAt)
        val pausedId = messageIndexAtPause?.let(::messageIdAt)
        messages = window
        rebuildFlatChunks()
        senderAnnouncedAtMessageIndex = announcedId?.let(::messageIndexOf)
        messageIndexAtPause = pausedId?.let(::messageIndexOf)
        val targetChunk = targetChunkFor(targetMessage, targetSentence)
        val announcement =
            if (targetMessageIdHex == currentMessageId) SenderAnnouncement.Suppress else SenderAnnouncement.Announce
        if (current is TtsState.Paused) {
            currentIndex = targetChunk
            pendingResumeAnnouncement =
                when {
                    announcement == SenderAnnouncement.Announce -> SenderAnnouncement.Announce
                    pendingResumeAnnouncement == SenderAnnouncement.Announce -> SenderAnnouncement.Announce
                    else -> SenderAnnouncement.Suppress
                }
            publishPaused(targetChunk)
        } else {
            requeueFrom(targetChunk, announcement)
        }
        return true
    }

    fun queuedMessagesSnapshot(): List<TtsQueuedMessage> = messages

    private fun firstChunkIndexForSentence(sentenceIndex: Int): Int {
        val lastChunk =
            if (messages.size == 1) {
                chunks.lastIndex
            } else {
                projection.firstChunkIndexOfMessage(1) - 1
            }
        return (0..lastChunk).firstOrNull { chunks[it].sentenceIndex == sentenceIndex } ?: 0
    }

    private fun targetChunkFor(
        messageIndex: Int,
        targetSentence: TtsWindowSentenceTarget,
    ): Int {
        val firstChunk = projection.firstChunkIndexOfMessage(messageIndex)
        if (targetSentence == TtsWindowSentenceTarget.First) return firstChunk
        val lastChunk =
            if (messageIndex == messages.lastIndex) {
                chunks.lastIndex
            } else {
                projection.firstChunkIndexOfMessage(messageIndex + 1) - 1
            }
        return projection.firstChunkIndexOfSentenceContaining(lastChunk)
    }

    private fun messageIdAt(index: Int): String? = messages.getOrNull(index)?.messageIdHex?.takeIf(String::isNotEmpty)

    private fun messageIndexOf(id: String): Int? = messages.indexOfFirst { it.messageIdHex == id }.takeIf { it >= 0 }

    /**
     * Applies the verdict of the edge request armed by a deferred navigation.
     * A caller that already repositioned playback advanced the generation,
     * which makes this call inert.
     *
     * The end-of-history verdicts act whether or not a terminal chunk parked:
     * the tap has to mean the same thing either way, and only this side knows
     * where the cursor ended up. [TtsEdgeSettlement.Retained] instead pauses a
     * parked terminal — it was already spoken, so keeping the session Speaking
     * would hold audio focus in silence with nothing left to play.
     */
    fun settleEdgeRequest(settlement: TtsEdgeSettlement) {
        if (edgeRequestGeneration != generation) return
        val parked = parkedTerminalGeneration == generation
        edgeRequestGeneration = null
        parkedTerminalGeneration = null
        when (settlement) {
            TtsEdgeSettlement.RestartedWindow -> moveTo(0, announcementForTarget(0))
            TtsEdgeSettlement.CompletedSession -> completeThroughNavigation()
            TtsEdgeSettlement.Retained -> if (parked) pauseAt(currentIndex)
            TtsEdgeSettlement.Resolved -> if (parked) finishPlayback()
        }
    }

    /**
     * The exact engine payload of the utterance the queue is currently
     * speaking, or null when [utteranceId] is stale, out of generation, or not
     * the active chunk. This is the validation gate the estimated word-timing
     * lane shares with real engine callbacks: an utterance that fails it must
     * neither arm a schedule nor contribute a calibration sample.
     */
    fun submittedChunk(utteranceId: String?): TtsChunk? {
        val index = parseCurrentGenerationIndex(utteranceId) ?: return null
        val active = _state.value is TtsState.Speaking && index == currentIndex
        return if (active) rangeTracker.submitted(index) else null
    }

    fun onDone(utteranceId: String?) {
        val completedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value !is TtsState.Speaking || completedIndex != currentIndex) return
        val completedMessage = projection.messageIndexForChunk(completedIndex)
        rangeTracker.remove(completedIndex)
        val next = completedIndex + 1
        when {
            next < chunks.size -> {
                if (projection.messageIndexForChunk(next) == completedMessage) {
                    progress.advanceWithinMessage(progressAtChunkEnd(completedIndex))
                }
                advanceToChunk(next)
            }
            // An edge request is still hunting for history past this chunk, so
            // the terminal parks: publishing Idle here would tear the session
            // down (and drop audio focus) moments before the page extends it.
            edgeRequestGeneration == generation -> parkedTerminalGeneration = generation
            else -> finishPlayback()
        }
    }

    private fun advanceToChunk(next: Int) {
        currentIndex = next
        announceSenderForCurrentMessage = false
        publishSpeaking(currentIndex)
        if (refreshAtNextBoundary) {
            refreshAtNextBoundary = false
            requeueFrom(next, SenderAnnouncement.Natural)
        }
    }

    fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        val failedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        val activeState = _state.value as? TtsState.Speaking
        if (activeState == null || failedIndex < currentIndex) return
        val error =
            when (errorCode) {
                TextToSpeech.ERROR_NETWORK,
                TextToSpeech.ERROR_NETWORK_TIMEOUT,
                -> TtsError.Network

                else -> TtsError.Synthesis
            }
        val messageIndex = projection.messageIndexForChunk(failedIndex)
        fail(
            error = error,
            chunkIndex = failedIndex,
            chunkCount = chunks.size,
            messageIndex = messageIndex,
            messageCount = messages.size,
            sentenceIndex = chunks[failedIndex].sentenceIndex,
            sentenceCount = messageSentenceCount[messageIndex],
            messagePreview = messages.getOrNull(messageIndex)?.preview.orEmpty(),
            messageProgressFraction =
                if (messageIndex == activeState.messageIndex) {
                    maxOf(
                        activeState.messageProgressFraction,
                        sentenceFallbackProgress(failedIndex),
                    )
                } else {
                    sentenceFallbackProgress(failedIndex)
                },
        )
    }

    /**
     * Framework stop callbacks do not imply completion. Queue-owned stop paths
     * advance [generation] themselves; a synchronous active callback only
     * clears a word range so it can never masquerade as fresh progress.
     */
    fun onStopped(
        utteranceId: String?,
        @Suppress("UNUSED_PARAMETER") interrupted: Boolean,
    ) {
        val stoppedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value is TtsState.Speaking && stoppedIndex == currentIndex) {
            publishSpeaking(currentIndex)
        }
    }

    enum class RangeApplication {
        Stale,
        FallbackOnly,
        VisibleWord,
    }

    /** Publishes range progress only for the active generation and chunk. */
    @Suppress("ReturnCount")
    fun onRangeStart(
        utteranceId: String?,
        start: Int,
        end: Int,
        @Suppress("UNUSED_PARAMETER") frame: Int = 0,
        retainVisibleWordOnFallback: Boolean = false,
    ): RangeApplication {
        val callbackIndex = parseCurrentGenerationIndex(utteranceId) ?: return RangeApplication.Stale
        val speaking = _state.value as? TtsState.Speaking ?: return RangeApplication.Stale
        if (callbackIndex != currentIndex) return RangeApplication.Stale
        val chunk = chunks[callbackIndex]
        val passage = rangeTracker.passageForRange(chunk, start, end)
        val messageIndex = projection.messageIndexForChunk(callbackIndex)
        progress.applyRangeStart(
            chunkIndex = callbackIndex,
            start = start,
            end = end,
            messageOffsetBeforeChunk = chunkOffsetInMessage(callbackIndex),
            messageSpeakableLength = messageSpeakableLength(messageIndex),
            sentenceFallback = sentenceFallbackProgress(callbackIndex),
        )
        _state.value =
            speaking.copy(
                messageProgressFraction = progress.fraction,
                passage =
                    if (passage?.visibleWord?.isNotEmpty() == true) {
                        passage
                    } else if (retainVisibleWordOnFallback) {
                        speaking.passage
                    } else {
                        passage
                    },
            )
        return if (passage?.visibleWord?.isNotEmpty() == true) {
            RangeApplication.VisibleWord
        } else {
            RangeApplication.FallbackOnly
        }
    }

    private fun isNavigable(): Boolean = _state.value is TtsState.Speaking || _state.value is TtsState.Paused

    /** Arms the edge deferral for the request the caller is about to start. */
    private fun deferToEdge(outcome: TtsNavigationOutcome): TtsNavigationOutcome {
        edgeRequestGeneration = generation
        return outcome
    }

    private fun announcementForTarget(target: Int): SenderAnnouncement =
        if (projection.messageIndexForChunk(target) != projection.messageIndexForChunk(currentIndex)) {
            SenderAnnouncement.Announce
        } else {
            SenderAnnouncement.Suppress
        }

    /**
     * Repositions playback. While speaking the engine queue restarts at the
     * target immediately. While paused only the position and progress move —
     * no chunk is enqueued and the target's announcement decision is held
     * until resume(). An Announce earned by crossing a message boundary
     * survives later same-message repositions: that message's sender has
     * still never been spoken.
     */
    private fun moveTo(
        index: Int,
        announcement: SenderAnnouncement,
    ) {
        if (_state.value is TtsState.Paused) {
            // A tap that does not move the position must not disturb the
            // pending resume announcement.
            if (index == currentIndex) return
            currentIndex = index
            pendingResumeAnnouncement =
                when {
                    announcement == SenderAnnouncement.Announce -> SenderAnnouncement.Announce
                    pendingResumeAnnouncement == SenderAnnouncement.Announce -> SenderAnnouncement.Announce
                    else -> SenderAnnouncement.Suppress
                }
            publishPaused(index)
        } else {
            requeueFrom(index, announcement)
        }
    }

    /** Stops the engine before publishing terminal completion caused by user navigation. */
    private fun completeThroughNavigation() {
        stopEngine()
        finishPlayback()
    }

    /** Publishes terminal idle state after invalidating callbacks from the completed queue. */
    private fun finishPlayback() {
        playbackCallbacks.advance()
        rangeTracker.clear()
        val completedCount = chunks.size
        val completedMessages = messages.size
        val lastPreview = messages.lastOrNull()?.preview.orEmpty()
        val lastSentenceCount = messageSentenceCount.lastOrNull() ?: 0
        messages = emptyList()
        projection = TtsQueueProjection.EMPTY
        currentIndex = completedCount
        resetMessageProgress()
        announceSenderForCurrentMessage = false
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        _state.value =
            TtsState.Idle(
                sessionId = playbackSessionId,
                chunkIndex = completedCount,
                chunkCount = completedCount,
                messageIndex = completedMessages,
                messageCount = completedMessages,
                sentenceIndexWithinMessage = lastSentenceCount,
                sentenceCountWithinMessage = lastSentenceCount,
                messagePreview = lastPreview,
                messageProgressFraction = 1f,
                messageProgressGeneration = messageProgressGeneration,
            )
        onTerminal()
    }

    /** Publishes a terminal error and prevents the failed queue from reporting more progress. */
    private fun fail(
        error: TtsError,
        chunkIndex: Int,
        chunkCount: Int,
        messageIndex: Int,
        messageCount: Int,
        sentenceIndex: Int,
        sentenceCount: Int,
        messagePreview: String,
        messageProgressFraction: Float = TtsMessageProgress.sentenceFallback(sentenceIndex, sentenceCount),
    ) {
        stopEngine()
        playbackCallbacks.advance()
        rangeTracker.clear()
        messages = emptyList()
        projection = TtsQueueProjection.EMPTY
        currentIndex = chunkIndex
        resetMessageProgress()
        announceSenderForCurrentMessage = false
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        _state.value =
            TtsState.Error(
                error = error,
                sessionId = playbackSessionId,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                messageIndex = messageIndex,
                messageCount = messageCount,
                sentenceIndexWithinMessage = sentenceIndex,
                sentenceCountWithinMessage = sentenceCount,
                messagePreview = messagePreview,
                messageProgressFraction = messageProgressFraction,
                messageProgressGeneration = messageProgressGeneration,
            )
        onTerminal()
    }

    /** Replaces pending engine utterances from [chunkIndex] under a new callback token. */
    private fun requeueFrom(
        index: Int,
        announcement: SenderAnnouncement,
    ) {
        stopEngine()
        playbackCallbacks.advance()
        progress.clearSpokenPayloads()
        rangeTracker.clear()
        refreshAtNextBoundary = false
        currentIndex = index
        announceSenderForCurrentMessage = announcement == SenderAnnouncement.Announce
        senderAnnouncedAtMessageIndex =
            if (announcement == SenderAnnouncement.Suppress) projection.messageIndexForChunk(index) else null
        enqueueFromCurrentIndex()
    }

    /** Submits the current callback generation from the selected chunk through the queue tail. */
    private fun enqueueFromCurrentIndex() {
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle(sessionId = playbackSessionId)
            return
        }
        publishSpeaking(currentIndex)
        for (chunk in chunks.drop(currentIndex)) {
            val utteranceId = utteranceId(generation, chunk.index)
            val result = enqueueSubmitted(chunk, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                onError(utteranceId, result)
                break
            }
            // Only the requeue target may carry a forced announcement — later
            // chunks fall back to the sender-change playback heuristic.
            announceSenderForCurrentMessage = false
        }
    }

    private fun enqueueSubmitted(
        chunk: TtsChunk,
        utteranceId: String,
    ): Int {
        val submitted = spokenChunk(chunk)
        progress.recordEnqueue(
            chunkIndex = chunk.index,
            spokenTextLength = submitted.text.length,
            chunkTextLength = chunk.text.length,
        )
        rangeTracker.record(submitted)
        return enqueue(submitted, utteranceId)
    }

    private fun spokenChunk(chunk: TtsChunk): TtsChunk {
        val messageIndex = projection.messageIndexForChunk(chunk.index)
        val message = messages[messageIndex]
        val isFirstChunkOfMessage = chunk.index == projection.firstChunkIndexOfMessage(messageIndex)
        // A cross-message sentence skip can target a mid-message sentence, so
        // a forced announcement attaches to the target chunk itself.
        val forcedAtTarget = announceSenderForCurrentMessage && chunk.index == currentIndex
        val announced =
            (forcedAtTarget || (isFirstChunkOfMessage && shouldAnnounceSender(messageIndex))) &&
                message.senderDisplayName.isNotBlank()
        if (!announced) return chunk.copy(senderPrefix = null)
        senderAnnouncedAtMessageIndex = messageIndex
        val prefix = "${message.senderDisplayName}: "
        return chunk.copy(
            text = prefix + chunk.text,
            visibleSpans =
                chunk.visibleSpans.map { span ->
                    span.copy(
                        spoken =
                            TtsTextRange(
                                span.spoken.start + prefix.length,
                                span.spoken.end + prefix.length,
                            ),
                    )
                },
            senderPrefix = TtsTextRange(0, prefix.length),
        )
    }

    private fun shouldAnnounceSender(messageIndex: Int): Boolean =
        when {
            announceSenderForCurrentMessage -> true
            senderAnnouncedAtMessageIndex == messageIndex -> false
            messageIndex == 0 -> true
            else ->
                !messages[messageIndex].senderKey.equals(messages[messageIndex - 1].senderKey, ignoreCase = true)
        }

    private fun replaceMessages(newMessages: List<TtsQueuedMessage>) {
        rangeTracker.clear()
        messages = newMessages
        rebuildFlatChunks()
    }

    private fun appendMessages(moreMessages: List<TtsQueuedMessage>): List<TtsChunk> {
        val firstAppendedChunkIndex = chunks.size
        messages = messages + moreMessages
        rebuildFlatChunks()
        return chunks.drop(firstAppendedChunkIndex)
    }

    private fun rebuildFlatChunks() {
        projection = TtsQueueProjection.from(messages)
    }

    private fun messageSpeakableLength(messageIndex: Int): Int {
        val first = projection.firstChunkIndexOfMessage(messageIndex)
        val last =
            if (messageIndex == messages.lastIndex) {
                chunks.lastIndex
            } else {
                projection.firstChunkIndexOfMessage(messageIndex + 1) - 1
            }
        var length = 0
        for (index in first..last) {
            length += chunks[index].text.length
        }
        return length
    }

    private fun chunkOffsetInMessage(chunkIndex: Int): Int {
        val messageIndex = projection.messageIndexForChunk(chunkIndex)
        val first = projection.firstChunkIndexOfMessage(messageIndex)
        var offset = 0
        for (index in first until chunkIndex) {
            offset += chunks[index].text.length
        }
        return offset
    }

    private fun sentenceFallbackProgress(chunkIndex: Int): Float {
        val messageIndex = projection.messageIndexForChunk(chunkIndex)
        return TtsMessageProgress.sentenceFallback(
            sentenceIndex = chunks[chunkIndex].sentenceIndex,
            sentenceCount = messageSentenceCount[messageIndex],
        )
    }

    private fun progressAtChunkEnd(chunkIndex: Int): Float {
        val messageIndex = projection.messageIndexForChunk(chunkIndex)
        return TtsMessageProgress.chunkEndProgress(
            messageOffsetBeforeChunk = chunkOffsetInMessage(chunkIndex),
            chunkLength = chunks[chunkIndex].text.length,
            messageTotalLength = messageSpeakableLength(messageIndex),
        )
    }

    private fun resetMessageProgress() {
        progress.reset()
    }

    private fun publishSpeaking(chunkIndex: Int) {
        val messageIndex = projection.messageIndexForChunk(chunkIndex)
        progress.syncBaseline(
            message = messages[messageIndex],
            chunkIndex = chunkIndex,
            messageIndex = messageIndex,
            sentenceFallback = sentenceFallbackProgress(chunkIndex),
        )
        _state.value =
            TtsState.Speaking(
                sessionId = playbackSessionId,
                chunkIndex = chunkIndex,
                chunkCount = chunks.size,
                messageIndex = messageIndex,
                messageCount = messages.size,
                sentenceIndexWithinMessage = chunks[chunkIndex].sentenceIndex,
                sentenceCountWithinMessage = messageSentenceCount[messageIndex],
                messagePreview = messages[messageIndex].preview,
                messageProgressFraction = progress.fraction,
                messageProgressGeneration = messageProgressGeneration,
                passage = rangeTracker.fallbackPassage(chunks[chunkIndex]),
            )
    }

    private fun publishPaused(
        chunkIndex: Int,
        passage: TtsPassage? = rangeTracker.fallbackPassage(chunks[chunkIndex]),
    ) {
        val messageIndex = projection.messageIndexForChunk(chunkIndex)
        progress.syncBaseline(
            message = messages[messageIndex],
            chunkIndex = chunkIndex,
            messageIndex = messageIndex,
            sentenceFallback = sentenceFallbackProgress(chunkIndex),
        )
        _state.value =
            TtsState.Paused(
                sessionId = playbackSessionId,
                chunkIndex = chunkIndex,
                chunkCount = chunks.size,
                messageIndex = messageIndex,
                messageCount = messages.size,
                sentenceIndexWithinMessage = chunks[chunkIndex].sentenceIndex,
                sentenceCountWithinMessage = messageSentenceCount[messageIndex],
                messagePreview = messages[messageIndex].preview,
                messageProgressFraction = progress.fraction,
                messageProgressGeneration = messageProgressGeneration,
                passage = passage,
            )
    }

    private fun parseCurrentGenerationIndex(utteranceId: String?): Int? {
        val match = UTTERANCE_ID_PATTERN.matchEntire(utteranceId ?: return null) ?: return null
        val callbackGeneration = match.groupValues[1].toLongOrNull() ?: return null
        val index = match.groupValues[2].toIntOrNull() ?: return null
        return index.takeIf { callbackGeneration == generation && it in chunks.indices }
    }

    private companion object {
        val UTTERANCE_ID_PATTERN = Regex("whitenoise\\.tts\\.(\\d+)\\.(\\d+)")

        fun utteranceId(
            generation: Long,
            index: Int,
        ): String = "whitenoise.tts.$generation.$index"
    }
}
