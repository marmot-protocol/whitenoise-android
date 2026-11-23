package dev.ipf.whitenoise.android.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

internal enum class AttachmentSpeechFailure {
    ANDROID_VERSION,
    PROVIDER_MISSING,
    PROVIDER_OUTDATED,
    PROVIDER_UNAVAILABLE,
    PERMISSION_DENIED,
    AUDIO_REJECTED,
    RECOGNITION_FAILED,
    TIMEOUT,
}

internal class AttachmentSpeechException(
    val failure: AttachmentSpeechFailure,
) : Exception()

private const val RECOGNITION_TIMEOUT_MS = 5L * 60L * 1_000L
private const val PIPE_STALL_TIMEOUT_MS = 10_000L
private const val PIPE_POLL_MS = 20L
private const val PIPE_WRITE_BYTES = 16_384

// OSTT 0.21.0 documents caller-audio ingestion through EOF without microphone endpointing.
private const val MIN_FILE_AUDIO_VERSION = 58L

/** Uses only OSTT's explicit service and the supplied PCM; never requests microphone capture. */
internal suspend fun transcribeAttachment(
    context: Context,
    file: File,
): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        throw AttachmentSpeechException(AttachmentSpeechFailure.ANDROID_VERSION)
    }
    return withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
        val service = withContext(Dispatchers.IO) { findAttachmentService(context) }
        // The whole lifetime, including finally after cancellation, belongs to the main looper.
        withContext(Dispatchers.Main.immediate) {
            recognizeAttachment(context.applicationContext, service, file)
        }
    } ?: throw AttachmentSpeechException(AttachmentSpeechFailure.TIMEOUT)
}

@Suppress("ThrowsCount") // Distinguish missing, outdated and unusable providers before decoding.
private fun findAttachmentService(context: Context): ComponentName {
    val providers =
        try {
            discoverConversationDictationProviders(context)
        } catch (_: RuntimeException) {
            throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_UNAVAILABLE)
        }
    val provider =
        providers.singleOrNull { it.packageName == OFFLINE_SPEECH_TO_TEXT_PACKAGE }
            ?: throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_MISSING)
    if (provider.choices.any { it.versionCode < MIN_FILE_AUDIO_VERSION }) {
        throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_OUTDATED)
    }
    return provider.choices
        .mapNotNull { it.service }
        .distinct()
        .singleOrNull()
        ?: throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_UNAVAILABLE)
}

@Suppress("ThrowsCount") // Every preparation or Binder failure terminates the complete descriptor lifetime.
private suspend fun recognizeAttachment(
    context: Context,
    service: ComponentName,
    file: File,
): String {
    val pipe =
        try {
            ParcelFileDescriptor.createPipe()
        } catch (_: IOException) {
            throw AttachmentSpeechException(AttachmentSpeechFailure.AUDIO_REJECTED)
        }
    val feed = AttachmentPcmFeed(pipe[1])
    val format = CompletableDeferred<AttachmentPcmFormat>()
    val terminal = CompletableDeferred<String>()
    val listener = AttachmentResultListener(feed, terminal)
    var recognizer: SpeechRecognizer? = null
    try {
        return coroutineScope {
            val writer =
                launch(Dispatchers.IO) {
                    feedAttachment(file, feed, format, terminal)
                }
            try {
                val pcmFormat = format.await()
                val currentService = withContext(Dispatchers.IO) { findAttachmentService(context) }
                if (currentService != service) {
                    throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_UNAVAILABLE)
                }
                val created =
                    try {
                        SpeechRecognizer.createSpeechRecognizer(context, service)
                    } catch (_: SecurityException) {
                        throw AttachmentSpeechException(AttachmentSpeechFailure.PERMISSION_DENIED)
                    } catch (_: RuntimeException) {
                        throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_UNAVAILABLE)
                    }
                recognizer = created
                try {
                    created.setRecognitionListener(listener)
                    created.startListening(attachmentSpeechIntent(pipe[0], pcmFormat))
                } catch (_: SecurityException) {
                    throw AttachmentSpeechException(AttachmentSpeechFailure.PERMISSION_DENIED)
                } catch (_: RuntimeException) {
                    throw AttachmentSpeechException(AttachmentSpeechFailure.PROVIDER_UNAVAILABLE)
                }
                terminal.await()
            } finally {
                writer.cancel()
            }
        }
    } finally {
        // coroutineScope has joined the nonblocking writer before either FD can be reused.
        listener.retire()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        feed.close()
        // startListening queues Binder marshalling; the original read FD must survive until here.
        runCatching { pipe[0].close() }
    }
}

private data class AttachmentPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
)

@Suppress("ThrowsCount", "TooGenericExceptionCaught") // Wake both waiters on failure; never accept partial audio.
private suspend fun feedAttachment(
    file: File,
    feed: AttachmentPcmFeed,
    format: CompletableDeferred<AttachmentPcmFormat>,
    terminal: CompletableDeferred<String>,
) {
    try {
        var firstFormat: AttachmentPcmFormat? = null
        feed.prepare()
        decodeAttachmentPcm(file) { chunk ->
            val hasLayout = chunk.sampleRateHz > 0 && chunk.channelCount > 0
            if (!hasLayout ||
                chunk.bytes.isEmpty() ||
                chunk.bytes.size.toLong() % (chunk.channelCount.toLong() * 2L) != 0L
            ) {
                throw IOException("Invalid decoded attachment PCM format")
            }
            val expected = firstFormat
            if (expected == null) {
                val currentFormat = AttachmentPcmFormat(chunk.sampleRateHz, chunk.channelCount)
                firstFormat = currentFormat
                format.complete(currentFormat)
            } else if (expected.sampleRateHz != chunk.sampleRateHz || expected.channelCount != chunk.channelCount) {
                throw IOException("Attachment PCM format changed during decoding")
            }
            feed.write(chunk.bytes)
        }
        if (firstFormat == null) {
            throw IOException("Attachment contains no decoded PCM")
        }
        // Only successful decoder EOS may close a complete utterance; not an individual chunk.
        feed.finish()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Wake both the pre-first-chunk handshake and an already-started recognition session.
        format.completeExceptionally(failure)
        terminal.completeExceptionally(failure)
    } finally {
        feed.close()
    }
}

private fun attachmentSpeechIntent(
    source: ParcelFileDescriptor,
    format: AttachmentPcmFormat,
): Intent =
    conversationDictationRecognitionIntent().apply {
        // Offline preference is advisory; only the explicitly selected OSTT service receives this.
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, format.channelCount)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, format.sampleRateHz)
    }

private class AttachmentPcmFeed(
    private val sink: ParcelFileDescriptor,
) {
    private val lock = Any()
    private var closed = false
    private var complete = false

    fun fullyFed(): Boolean = synchronized(lock) { complete }

    fun prepare() {
        try {
            val descriptor = sink.fileDescriptor
            val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        } catch (_: ErrnoException) {
            throw AttachmentSpeechException(AttachmentSpeechFailure.AUDIO_REJECTED)
        }
    }

    @Suppress("ThrowsCount") // Preserve cancellation and distinguish blocked writes from rejected audio.
    suspend fun write(bytes: ByteArray) {
        try {
            val descriptor = sink.fileDescriptor
            var offset = 0
            var stalledAt: Long? = null
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val written =
                    try {
                        Os.write(descriptor, bytes, offset, minOf(PIPE_WRITE_BYTES, bytes.size - offset))
                    } catch (failure: ErrnoException) {
                        if (failure.errno == OsConstants.EINTR) {
                            continue
                        }
                        if (failure.errno != OsConstants.EAGAIN) {
                            throw failure
                        }
                        0
                    }
                if (written > 0) {
                    offset += written
                    stalledAt = null
                } else {
                    val now = SystemClock.elapsedRealtime()
                    val since = stalledAt ?: now.also { stalledAt = it }
                    if (now - since >= PIPE_STALL_TIMEOUT_MS) {
                        throw AttachmentSpeechException(AttachmentSpeechFailure.TIMEOUT)
                    }
                    delay(PIPE_POLL_MS)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ErrnoException) {
            throw AttachmentSpeechException(AttachmentSpeechFailure.AUDIO_REJECTED)
        } catch (_: RuntimeException) {
            throw AttachmentSpeechException(AttachmentSpeechFailure.AUDIO_REJECTED)
        }
    }

    suspend fun finish() {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            // Serialize EOF and its completion marker with provider terminal callbacks.
            try {
                sink.close()
            } catch (_: IOException) {
                throw AttachmentSpeechException(AttachmentSpeechFailure.AUDIO_REJECTED)
            }
            closed = true
            complete = true
        }
    }

    fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                runCatching { sink.close() }
            }
        }
    }
}

@Suppress("TooManyFunctions") // RecognitionListener requires all callbacks, even unused ones.
private class AttachmentResultListener(
    private val feed: AttachmentPcmFeed,
    private val terminal: CompletableDeferred<String>,
) : RecognitionListener {
    private var retired = false

    fun retire() {
        retired = true
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) = Unit

    override fun onResults(results: Bundle?) {
        if (retired) {
            return
        }
        if (!feed.fullyFed()) {
            fail(AttachmentSpeechFailure.AUDIO_REJECTED)
            return
        }
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
        if (text == null) {
            fail(AttachmentSpeechFailure.RECOGNITION_FAILED)
        } else {
            terminal.complete(text.trim())
        }
    }

    override fun onError(error: Int) {
        if (retired) {
            return
        }
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> {
                if (feed.fullyFed()) {
                    // OSTT documents no-match as unrecognized audio, not proof of silence.
                    fail(AttachmentSpeechFailure.RECOGNITION_FAILED)
                } else {
                    fail(AttachmentSpeechFailure.AUDIO_REJECTED)
                }
            }
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            -> fail(AttachmentSpeechFailure.AUDIO_REJECTED)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(AttachmentSpeechFailure.PERMISSION_DENIED)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            -> fail(AttachmentSpeechFailure.PROVIDER_UNAVAILABLE)
            else -> fail(AttachmentSpeechFailure.RECOGNITION_FAILED)
        }
    }

    private fun fail(failure: AttachmentSpeechFailure) {
        if (!retired) {
            terminal.completeExceptionally(AttachmentSpeechException(failure))
        }
    }
}
