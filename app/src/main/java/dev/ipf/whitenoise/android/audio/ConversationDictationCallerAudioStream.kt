package dev.ipf.whitenoise.android.audio

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val PIPE_RETRY_MILLIS = 10L

/** Allows an offline provider to finish binding/loading before declaring its caller-audio pipe dead. */
private const val PIPE_STALL_TIMEOUT_MILLIS = 10_000L

/** One provider request backed by one exact, retryable caller-audio chunk. */
@Suppress("TooManyFunctions")
internal class ConversationDictationCallerAudioStream(
    private val capture: ConversationDictationCallerAudio,
    private val writeEnd: ParcelFileDescriptor,
    val providerEnd: ParcelFileDescriptor,
    private val buffer: ConversationDictationAudioChunkBuffer,
    private val pipeWriter: ConversationDictationAudioPipeWriter,
    private val onFailure: (ConversationDictationCallerAudioFailure) -> Unit,
) {
    private val feeding = AtomicBoolean(false)
    private val settled = AtomicBoolean(false)
    private val feedClosed = AtomicBoolean(false)
    private val fullyFed = AtomicBoolean(false)

    /** Set by a provider terminal callback; only the feeder thread acts on it, so claims cannot race it. */
    private val providerEnded = AtomicBoolean(false)
    private val feedClosedCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val cancelled = AtomicBoolean(false)
    private val captureFailureReported = AtomicBoolean(false)
    private val pipeFailureReported = AtomicBoolean(false)
    private val chunk = AtomicReference<ConversationDictationAudioChunk?>(null)
    private val request = AtomicReference<ConversationDictationAudioRequest?>(null)

    private data class FailureIdentity(
        val chunkId: Long,
        val version: String,
    )

    private val failureIdentity = AtomicReference<FailureIdentity?>(null)

    /** Starts the device or feeder once; a sealed capture may only drain retained PCM. */
    fun start(): Boolean {
        if (!capture.start() || !feeding.compareAndSet(false, true)) return false
        thread(name = "dictation-caller-audio-feed", isDaemon = true, block = ::feed)
        return true
    }

    /** Seals the logical capture while this generation continues feeding its owned chunk. */
    fun finishCapture(onClosed: () -> Unit) = capture.finish(onClosed)

    /** Observes closure of the shared microphone capture, including its final partial chunk. */
    fun onCaptureClosed(callback: () -> Unit) = capture.onCaptureClosed(callback)

    /** Registers an exactly-once observer, including when the feeder has already closed. */
    fun onFeedClosed(callback: () -> Unit) {
        if (feedClosed.get()) {
            callback()
        } else {
            feedClosedCallbacks.add(callback)
            if (feedClosed.get() && feedClosedCallbacks.remove(callback)) callback()
        }
    }

    /**
     * Ends an unfinished feed as soon as the provider reports a terminal callback, instead of
     * writing into a dead request until the pipe stalls. The feeder stops at its next poll; a claimed
     * chunk is requeued with its exact bytes and keeps its failure identity, and a fully fed stream
     * is left untouched. The callback runs once the feeder has closed.
     */
    fun endFeedAfterProviderTerminal(callback: () -> Unit) {
        providerEnded.set(true)
        onFeedClosed(callback)
    }

    /** Releases this generation’s chunk after a final transcript makes its audio expendable. */
    fun acknowledge(): Boolean = settle(requeue = false)

    /** An accepted result covers the entire feed and settles its transcript-span provenance once. */
    fun acknowledge(transcript: String): ConversationDictationAudioSettlement? {
        if (!fullyFed.get() || !settled.compareAndSet(false, true)) return null
        val owned = request.getAndSet(null)
        return if (owned == null) {
            null
        } else {
            chunk.set(null)
            val accepted = buffer.requests.acknowledge(owned, transcript)
            if (accepted == null) buffer.retry(owned.chunk.chunkId)
            capture.streamSettled(this)
            accepted
        }
    }

    fun request(): ConversationDictationAudioRequest? = request.get()

    /** A failed feeder retains only retry identity after relinquishing its PCM request. */
    fun requestVersion(): String? = request.get()?.version ?: failureIdentity.get()?.version

    /** Returns capture-side speech evidence for this generation's exact chunk, when claimed. */
    fun containsSpeech(): Boolean? = chunk.get()?.hasSpeech

    /** True only after every byte of this generation's exact chunk was supplied successfully. */
    fun fullyFed(): Boolean = fullyFed.get()

    /** Returns the stable identity of this generation's exact claimed chunk. */
    fun chunkId(): Long? = chunk.get()?.chunkId ?: failureIdentity.get()?.chunkId

    /** Checks sealed final ownership without acknowledging or changing retained PCM. */
    fun isFinalChunk(): Boolean = chunk.get()?.let { buffer.isFinalChunk(it.chunkId) } == true

    /** Returns this generation’s chunk to the front of the queue without duplicating its byte accounting. */
    fun retry(): Boolean = settle(requeue = true)

    /** Extends a no-speech chunk with following audio before returning it to the queue. */
    fun retryWithFollowingAudio(): Boolean = settle(requeue = true, coalesceFollowingAudio = true)

    /** Requeues the rejected new input with its immediately preceding recognized PCM. */
    fun retryWithContext(): Boolean {
        val owned = request.get()
        return if (owned == null ||
            !buffer.requests.canRetryWithContext(owned) ||
            !settled.compareAndSet(false, true)
        ) {
            false
        } else {
            val requeued = buffer.requests.retryWithContext(owned)
            request.set(null)
            chunk.set(null)
            capture.streamSettled(this)
            requeued
        }
    }

    /** Closes the feeder and relinquishes its lease, retaining unacknowledged PCM by default. */
    fun cancel(requeue: Boolean = true) {
        cancelled.set(true)
        settle(requeue)
        closePipe()
    }

    /** Delivers one typed capture failure to the generation that owns this stream. */
    fun reportFailure(failure: ConversationDictationCallerAudioFailure) {
        val reported =
            if (failure == ConversationDictationCallerAudioFailure.PipeFailed) {
                pipeFailureReported
            } else {
                captureFailureReported
            }
        if (!cancelled.get() && reported.compareAndSet(false, true)) onFailure(failure)
    }

    /** Claims one chunk, feeds its PCM, and requeues ownership on interruption or provider disconnection. */
    private fun feed() {
        try {
            var owned: ConversationDictationAudioRequest? = null
            while (feedOpen() && owned == null) {
                owned = buffer.requests.pollRequest()
                if (owned == null) Thread.sleep(PIPE_RETRY_MILLIS)
            }
            if (owned == null) {
                if (providerEnded.get()) {
                    conversationDictationDiagnostic(
                        "event=caller_audio_feed_ended reason=provider_result claimed=false",
                    )
                }
                return
            }
            request.set(owned)
            chunk.set(owned.chunk)
            if (cancelled.get() || settled.get()) {
                if (chunk.compareAndSet(owned.chunk, null)) buffer.retry(owned.chunk.chunkId)
                return
            }
            writeChunk(owned)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            settle(requeue = true)
        } catch (failure: ErrnoException) {
            reportFeedFailure(failure.javaClass.simpleName)
        } catch (failure: IOException) {
            reportFeedFailure(failure.javaClass.simpleName)
        } catch (_: RuntimeException) {
            reportFeedFailure("RuntimeException")
        } finally {
            closePipe()
        }
    }

    /** Requeues exact PCM before notifying the controller, even if settlement changed no buffer bytes. */
    private fun reportFeedFailure(failureType: String) {
        conversationDictationDiagnostic(
            "event=caller_audio_feed_failed type=$failureType action=requeue",
        )
        request.get()?.let { failureIdentity.set(FailureIdentity(it.chunk.chunkId, it.version)) }
        settle(requeue = true) {
            reportFailure(ConversationDictationCallerAudioFailure.PipeFailed)
        }
    }

    /** Feeds an owned chunk until completion, cancellation, or a bounded nonblocking-write stall. */
    private fun writeChunk(owned: ConversationDictationAudioRequest) {
        val sink = writeEnd.fileDescriptor
        var totalWritten = 0
        var stalledAt: Long? = null
        for (part in owned.pcmParts) {
            var offset = 0
            while (feedOpen() && offset < part.pcm.size) {
                val written =
                    try {
                        pipeWriter.write(sink, part.pcm, offset, part.pcm.size - offset)
                    } catch (failure: ErrnoException) {
                        if (failure.errno != OsConstants.EAGAIN) throw failure
                        0
                    }
                if (written > 0) {
                    offset += written
                    totalWritten += written
                    stalledAt = null
                } else {
                    val now = capture.elapsedRealtimeMillis()
                    val since = stalledAt ?: now.also { stalledAt = it }
                    if (now - since >= PIPE_STALL_TIMEOUT_MILLIS) {
                        conversationDictationDiagnostic(
                            "event=caller_audio_write_stalled chunk=${owned.chunk.chunkId} bytes=$totalWritten",
                        )
                        reportFeedFailure("PipeStalled")
                        return
                    }
                    Thread.sleep(PIPE_RETRY_MILLIS)
                }
            }
        }
        conversationDictationDiagnostic(
            "event=caller_audio_chunk_fed chunk=${owned.chunk.chunkId} first_sample=${owned.chunk.firstSample} " +
                "last_sample=${owned.chunk.lastSampleExclusive} replay_parts=${owned.replayParts.size} " +
                "replay_bytes=${owned.replayParts.sumOf { it.pcm.size }} " +
                "new_bytes=${owned.chunk.pcm.size} bytes=$totalWritten",
        )
        // A terminal that lands during the final write still wins: its result cannot cover this feed.
        if (totalWritten == owned.pcmParts.sumOf { it.pcm.size } && feedOpen()) {
            fullyFed.set(true)
        } else if (providerEnded.get() && !cancelled.get()) {
            conversationDictationDiagnostic("event=caller_audio_feed_ended reason=provider_result claimed=true")
            failureIdentity.set(FailureIdentity(owned.chunk.chunkId, owned.version))
            settle(requeue = true)
        }
    }

    /** True while no cancellation, settlement or provider terminal has ended this feed. */
    private fun feedOpen() = !cancelled.get() && !settled.get() && !providerEnded.get()

    /** Releases the active-stream lease exactly once, even before a chunk has been acquired. */
    private fun settle(
        requeue: Boolean,
        coalesceFollowingAudio: Boolean = false,
        onSettled: () -> Unit = {},
    ): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        val owned = chunk.getAndSet(null)
        request.set(null)
        val changed =
            when {
                owned == null -> false
                requeue && coalesceFollowingAudio -> buffer.retryWithFollowingAudio(owned.chunkId)
                requeue -> buffer.retry(owned.chunkId)
                else -> buffer.acknowledge(owned.chunkId)
            }
        capture.streamSettled(this)
        onSettled()
        return changed
    }

    /** Closes the writer and notifies feeder observers once even when cancellation races with completion. */
    private fun closePipe() {
        runCatching(writeEnd::close)
        if (!feedClosed.compareAndSet(false, true)) return
        while (true) feedClosedCallbacks.poll()?.invoke() ?: return
    }

    /** Releases the provider-facing descriptor independently of writer and capture ownership. */
    fun closeProviderEnd() = runCatching(providerEnd::close)
}
