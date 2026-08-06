package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TtsState {
    val chunkIndex: Int
    val chunkCount: Int
    val messageIndex: Int
    val messageCount: Int
    val sentenceIndexWithinMessage: Int
    val sentenceCountWithinMessage: Int
    val messagePreview: String

    data class Idle(
        override val chunkIndex: Int = 0,
        override val chunkCount: Int = 0,
        override val messageIndex: Int = 0,
        override val messageCount: Int = 0,
        override val sentenceIndexWithinMessage: Int = 0,
        override val sentenceCountWithinMessage: Int = 0,
        override val messagePreview: String = "",
    ) : TtsState

    data class Speaking(
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val sentenceIndexWithinMessage: Int,
        override val sentenceCountWithinMessage: Int,
        override val messagePreview: String,
    ) : TtsState

    data class Paused(
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val sentenceIndexWithinMessage: Int,
        override val sentenceCountWithinMessage: Int,
        override val messagePreview: String,
    ) : TtsState

    data class Error(
        val error: TtsError,
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val sentenceIndexWithinMessage: Int,
        override val sentenceCountWithinMessage: Int,
        override val messagePreview: String,
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
internal class TtsPlaybackQueue(
    private val stopEngine: () -> Unit,
    private val enqueue: (chunk: TtsChunk, utteranceId: String) -> Int,
    private val onTerminal: () -> Unit = {},
) {
    private val _state = MutableStateFlow<TtsState>(TtsState.Idle())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var messages: List<TtsQueuedMessage> = emptyList()
    private var chunks: List<TtsChunk> = emptyList()
    private var messageFirstChunkIndex: IntArray = intArrayOf()
    private var messageSentenceCount: IntArray = intArrayOf()
    private var currentIndex = 0
    private var generation = 0L
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
    private var edgeRequestGeneration: Long? = null
    private var parkedTerminalGeneration: Long? = null

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

    fun start(messages: List<TtsQueuedMessage>) {
        stopEngine()
        generation += 1
        refreshAtNextBoundary = false
        replaceMessages(messages)
        currentIndex = 0
        announceSenderForCurrentMessage = false
        senderAnnouncedAtMessageIndex = null
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
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
                val result = enqueue(spokenChunk(chunk), utteranceId)
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

    private fun pauseAt(chunkIndex: Int) {
        stopEngine()
        generation += 1
        // Resume re-reads the rate per utterance anyway, a leaked flag would
        // only force a needless engine restart at the first boundary.
        refreshAtNextBoundary = false
        currentIndex = chunkIndex
        pendingResumeAnnouncement = null
        messageIndexAtPause = messageIndexForChunk(currentIndex)
        publishPaused(currentIndex)
    }

    fun resume() {
        val paused = _state.value as? TtsState.Paused ?: return
        currentIndex = paused.chunkIndex
        // A deferred Announce that navigated back to the message interrupted
        // by pause() would repeat a sender the listener already heard, so it
        // demotes to Suppress. A genuinely unheard sender keeps its Announce.
        val backAtPausedMessage = messageIndexForChunk(currentIndex) == messageIndexAtPause
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
                senderAnnouncedAtMessageIndex = messageIndexForChunk(currentIndex)
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

    fun stop() {
        stopEngine()
        generation += 1
        messages = emptyList()
        chunks = emptyList()
        messageFirstChunkIndex = intArrayOf()
        messageSentenceCount = intArrayOf()
        currentIndex = 0
        announceSenderForCurrentMessage = false
        senderAnnouncedAtMessageIndex = null
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        _state.value = TtsState.Idle()
    }

    fun skipNextMessage(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        val nextMessage = messageIndexForChunk(currentIndex) + 1
        return when {
            nextMessage >= messages.size && deferAtEdge -> deferToEdge(TtsNavigationOutcome.AtNewerEdge)
            nextMessage >= messages.size -> {
                completeThroughNavigation()
                TtsNavigationOutcome.Completed
            }

            else -> {
                moveTo(firstChunkIndexOfMessage(nextMessage), SenderAnnouncement.Announce)
                TtsNavigationOutcome.Moved
            }
        }
    }

    fun skipPreviousMessage(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        val currentMessage = messageIndexForChunk(currentIndex)
        return if (currentMessage == 0 && deferAtEdge) {
            deferToEdge(TtsNavigationOutcome.AtOlderEdge)
        } else {
            val target = firstChunkIndexOfMessage((currentMessage - 1).coerceAtLeast(0))
            moveTo(target, announcementForTarget(target))
            TtsNavigationOutcome.Moved
        }
    }

    fun skipNextSentence(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!isNavigable()) return TtsNavigationOutcome.Inactive
        val target = firstChunkIndexAfterSentenceContaining(currentIndex)
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
            val currentSentenceStart = firstChunkIndexOfSentenceContaining(currentIndex)
            val target =
                if (currentSentenceStart == 0) 0 else firstChunkIndexOfSentenceContaining(currentSentenceStart - 1)
            moveTo(target, announcementForTarget(target))
            TtsNavigationOutcome.Moved
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
        val currentMessageId = messageIdAt(messageIndexForChunk(currentIndex))
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

    private fun targetChunkFor(
        messageIndex: Int,
        targetSentence: TtsWindowSentenceTarget,
    ): Int {
        val firstChunk = firstChunkIndexOfMessage(messageIndex)
        if (targetSentence == TtsWindowSentenceTarget.First) return firstChunk
        val lastChunk =
            if (messageIndex == messages.lastIndex) {
                chunks.lastIndex
            } else {
                firstChunkIndexOfMessage(messageIndex + 1) - 1
            }
        return firstChunkIndexOfSentenceContaining(lastChunk)
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

    fun onDone(utteranceId: String?) {
        val completedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value !is TtsState.Speaking || completedIndex != currentIndex) return
        val next = completedIndex + 1
        when {
            next < chunks.size -> advanceToChunk(next)
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
        if (_state.value !is TtsState.Speaking || failedIndex < currentIndex) return
        val error =
            when (errorCode) {
                TextToSpeech.ERROR_NETWORK,
                TextToSpeech.ERROR_NETWORK_TIMEOUT,
                -> TtsError.Network

                else -> TtsError.Synthesis
            }
        val messageIndex = messageIndexForChunk(failedIndex)
        fail(
            error = error,
            chunkIndex = failedIndex,
            chunkCount = chunks.size,
            messageIndex = messageIndex,
            messageCount = messages.size,
            sentenceIndex = chunks[failedIndex].sentenceIndex,
            sentenceCount = messageSentenceCount[messageIndex],
            messagePreview = messages.getOrNull(messageIndex)?.preview.orEmpty(),
        )
    }

    private fun isNavigable(): Boolean = _state.value is TtsState.Speaking || _state.value is TtsState.Paused

    /** Arms the edge deferral for the request the caller is about to start. */
    private fun deferToEdge(outcome: TtsNavigationOutcome): TtsNavigationOutcome {
        edgeRequestGeneration = generation
        return outcome
    }

    private fun announcementForTarget(target: Int): SenderAnnouncement =
        if (messageIndexForChunk(target) != messageIndexForChunk(currentIndex)) {
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

    private fun completeThroughNavigation() {
        stopEngine()
        finishPlayback()
    }

    private fun finishPlayback() {
        generation += 1
        val completedCount = chunks.size
        val completedMessages = messages.size
        val lastPreview = messages.lastOrNull()?.preview.orEmpty()
        val lastSentenceCount = messageSentenceCount.lastOrNull() ?: 0
        messages = emptyList()
        chunks = emptyList()
        messageFirstChunkIndex = intArrayOf()
        messageSentenceCount = intArrayOf()
        currentIndex = completedCount
        announceSenderForCurrentMessage = false
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        _state.value =
            TtsState.Idle(
                chunkIndex = completedCount,
                chunkCount = completedCount,
                messageIndex = completedMessages,
                messageCount = completedMessages,
                sentenceIndexWithinMessage = lastSentenceCount,
                sentenceCountWithinMessage = lastSentenceCount,
                messagePreview = lastPreview,
            )
        onTerminal()
    }

    private fun fail(
        error: TtsError,
        chunkIndex: Int,
        chunkCount: Int,
        messageIndex: Int,
        messageCount: Int,
        sentenceIndex: Int,
        sentenceCount: Int,
        messagePreview: String,
    ) {
        stopEngine()
        generation += 1
        messages = emptyList()
        chunks = emptyList()
        messageFirstChunkIndex = intArrayOf()
        messageSentenceCount = intArrayOf()
        currentIndex = chunkIndex
        announceSenderForCurrentMessage = false
        pendingResumeAnnouncement = null
        messageIndexAtPause = null
        _state.value =
            TtsState.Error(
                error = error,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                messageIndex = messageIndex,
                messageCount = messageCount,
                sentenceIndexWithinMessage = sentenceIndex,
                sentenceCountWithinMessage = sentenceCount,
                messagePreview = messagePreview,
            )
        onTerminal()
    }

    private fun requeueFrom(
        index: Int,
        announcement: SenderAnnouncement,
    ) {
        stopEngine()
        generation += 1
        refreshAtNextBoundary = false
        currentIndex = index
        announceSenderForCurrentMessage = announcement == SenderAnnouncement.Announce
        senderAnnouncedAtMessageIndex =
            if (announcement == SenderAnnouncement.Suppress) messageIndexForChunk(index) else null
        enqueueFromCurrentIndex()
    }

    private fun enqueueFromCurrentIndex() {
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
        publishSpeaking(currentIndex)
        for (chunk in chunks.drop(currentIndex)) {
            val utteranceId = utteranceId(generation, chunk.index)
            val result = enqueue(spokenChunk(chunk), utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                onError(utteranceId, result)
                break
            }
            // Only the requeue target may carry a forced announcement — later
            // chunks fall back to the sender-change playback heuristic.
            announceSenderForCurrentMessage = false
        }
    }

    private fun spokenChunk(chunk: TtsChunk): TtsChunk {
        val messageIndex = messageIndexForChunk(chunk.index)
        val message = messages[messageIndex]
        val isFirstChunkOfMessage = chunk.index == firstChunkIndexOfMessage(messageIndex)
        // A cross-message sentence skip can target a mid-message sentence, so
        // a forced announcement attaches to the target chunk itself.
        val forcedAtTarget = announceSenderForCurrentMessage && chunk.index == currentIndex
        val announced =
            (forcedAtTarget || (isFirstChunkOfMessage && shouldAnnounceSender(messageIndex))) &&
                message.senderDisplayName.isNotBlank()
        if (!announced) return chunk
        senderAnnouncedAtMessageIndex = messageIndex
        return chunk.copy(text = "${message.senderDisplayName}: ${chunk.text}")
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
        val firstIndices = mutableListOf<Int>()
        val sentenceCounts = mutableListOf<Int>()
        val flat = mutableListOf<TtsChunk>()
        var nextIndex = 0
        for (message in messages) {
            // An empty message would duplicate first-chunk indices and alias
            // navigation targets.
            require(message.chunks.isNotEmpty()) { "queued messages must contain at least one chunk" }
            firstIndices += nextIndex
            sentenceCounts += (message.chunks.maxOfOrNull(TtsChunk::sentenceIndex) ?: -1) + 1
            for (chunk in message.chunks) {
                flat += chunk.copy(index = nextIndex)
                nextIndex += 1
            }
        }
        chunks = flat
        messageFirstChunkIndex = firstIndices.toIntArray()
        messageSentenceCount = sentenceCounts.toIntArray()
    }

    private fun messageIndexForChunk(chunkIndex: Int): Int {
        // Binary search over the sorted first-chunk offsets: this projection
        // runs once per chunk on every requeue, so a linear scan would go
        // quadratic as the paged window grows.
        var low = 0
        var high = messageFirstChunkIndex.size - 1
        var messageIndex = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (messageFirstChunkIndex[mid] <= chunkIndex) {
                messageIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return messageIndex
    }

    private fun firstChunkIndexOfMessage(messageIndex: Int): Int = messageFirstChunkIndex[messageIndex]

    private fun inSameSentence(
        first: Int,
        second: Int,
    ): Boolean =
        messageIndexForChunk(first) == messageIndexForChunk(second) &&
            chunks[first].sentenceIndex == chunks[second].sentenceIndex

    private fun firstChunkIndexOfSentenceContaining(chunkIndex: Int): Int {
        var index = chunkIndex
        while (index > 0 && inSameSentence(index - 1, chunkIndex)) index -= 1
        return index
    }

    private fun firstChunkIndexAfterSentenceContaining(chunkIndex: Int): Int {
        var index = chunkIndex + 1
        while (index < chunks.size && inSameSentence(index, chunkIndex)) index += 1
        return index
    }

    private fun publishSpeaking(chunkIndex: Int) {
        val messageIndex = messageIndexForChunk(chunkIndex)
        _state.value =
            TtsState.Speaking(
                chunkIndex = chunkIndex,
                chunkCount = chunks.size,
                messageIndex = messageIndex,
                messageCount = messages.size,
                sentenceIndexWithinMessage = chunks[chunkIndex].sentenceIndex,
                sentenceCountWithinMessage = messageSentenceCount[messageIndex],
                messagePreview = messages[messageIndex].preview,
            )
    }

    private fun publishPaused(chunkIndex: Int) {
        val messageIndex = messageIndexForChunk(chunkIndex)
        _state.value =
            TtsState.Paused(
                chunkIndex = chunkIndex,
                chunkCount = chunks.size,
                messageIndex = messageIndex,
                messageCount = messages.size,
                sentenceIndexWithinMessage = chunks[chunkIndex].sentenceIndex,
                sentenceCountWithinMessage = messageSentenceCount[messageIndex],
                messagePreview = messages[messageIndex].preview,
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
