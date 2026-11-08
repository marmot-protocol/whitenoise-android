package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val AV_EDGE = 128
private const val AV_ERROR_CHARS = 64

/** Debug-only descriptor experiment; no production caller routes through this component. */
class MediaAvCapabilityService : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private val watchdog = AvProbeDeadlines { Process.killProcess(Process.myPid()) }
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::accept))
    private var admitted = false

    override fun onBind(intent: Intent) = messenger.binder

    private fun accept(message: Message): Boolean {
        if (Process.isIsolated()) {
            when (message.what) {
                MediaDecodePolicy.RETIRE -> Process.killProcess(Process.myPid())
                MediaDecodePolicy.DECODE ->
                    if (!admitted) {
                        admitted = true
                        val data = Bundle(message.data)
                        val reply = message.replyTo?.binder
                        val requestId = message.arg1
                        work.execute { execute(data, reply, requestId) }
                    }
            }
        }
        return true
    }

    // Debug request boundary records bounded platform/parser failures; watchdog and retirement remain active.
    @Suppress("TooGenericExceptionCaught")
    private fun execute(
        data: Bundle,
        reply: android.os.IBinder?,
        requestId: Int,
    ) {
        val trace = AvCapabilityTrace.from(data, requestId) ?: return
        val ceiling =
            if (data.getString("probeStage") == "waveform") {
                MediaDecodePolicy.WAVEFORM_DEADLINE_MS
            } else {
                MediaDecodePolicy.WATCHDOG_MS
            }
        val timeoutEvidence = AtomicReference<AvProbeSoftTimeout?>()
        val softMs = if (data.getString("probeStage") == "waveform") null else AV_SOFT_REFUSAL_MS
        watchdog.arm(ceiling, softMs) {
            timeoutEvidence.get()?.let { frame ->
                trace.terminal("watchdog", "timeout") { sendAvProbeSoftTimeout(checkNotNull(reply), frame) }
            }
        }
        try {
            val tuple =
                AvProbeTuple(
                    checkNotNull(data.getString("runToken")),
                    checkNotNull(data.getString("caseId")),
                    requestId,
                )
            sendAvProbeReady(checkNotNull(reply), tuple)
            val result = descriptors(data, trace) { timeoutEvidence.set(AvProbeSoftTimeout.observed(tuple, it)) }
            trace.complete(result) { reply?.let { sendMediaDecodeReply(it, requestId, result) } }
        } catch (failure: Exception) {
            trace.terminal("reply", "exception", failure) {}
        }
    }

    @Suppress("DEPRECATION") // The debug Binder envelope contains only these two selected handles.
    private fun descriptors(data: Bundle, trace: AvCapabilityTrace, admitted: (Bundle) -> Unit): Bundle {
        val result = isolation(data)
        val input = checkNotNull(data.getParcelable<ParcelFileDescriptor>("input"))
        input.use {
            val output = checkNotNull(data.getParcelable<SharedMemory>("output"))
            output.use {
                require(data.getInt("version") == MediaDecodePolicy.VERSION)
                require(validMediaRange(input.statSize, data.getLong("offset"), data.getLong("length")))
                result.putInt("inputWriteErrno", writeErrno(input))
                admitted(result)
                trace.run { AvCapabilityOperations.run(input, output, data, result, trace) }
            }
        }
        return result
    }

    private fun isolation(data: Bundle): Bundle =
        Bundle().apply {
            putBoolean("isolated", Process.isIsolated())
            putBoolean(
                "internetGranted",
                checkSelfPermission(android.Manifest.permission.INTERNET) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
            putBoolean(
                "runtimeInitialized",
                (application as dev.ipf.whitenoise.android.WhiteNoiseApplication)
                    .initializedAppState() != null,
            )
            val errno = canaryErrno(data)
            putInt("privateCanaryErrno", errno)
            putBoolean(
                "privateCanaryDenied",
                errno in
                    listOf(
                        android.system.OsConstants.EACCES,
                        android.system.OsConstants.EPERM,
                        android.system.OsConstants.ENOENT,
                    ),
            )
        }

    private fun writeErrno(input: ParcelFileDescriptor): Int =
        try {
            android.system.Os.write(input.fileDescriptor, byteArrayOf(0), 0, 1)
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }

    private fun canaryErrno(data: Bundle): Int {
        val path = data.getString("canaryPath")?.takeIf { it.endsWith("/files/media-containment-canary") } ?: return -1
        return try {
            android.system.Os.close(android.system.Os.open(path, android.system.OsConstants.O_RDONLY, 0))
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }
    }

    override fun onDestroy() {
        work.shutdownNow()
        watchdog.close()
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }
}

/** Fixed correlation fields and bounded error identifiers; never exception messages or media paths. */
internal class AvCapabilityTrace private constructor(
    private val token: String,
    private val case: String,
    private val request: Int,
) {
    private var phase = "admission"
    private var failure: Exception? = null
    private val terminalGate = AvProbeTerminalGate()

    fun phase(value: String) {
        phase = value
        record(value, "begin")
    }

    // Experimental probe boundary retains only the Exception class/code, excluding messages and Throwable.
    @Suppress("TooGenericExceptionCaught")
    fun run(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            this.failure = failure
        }
    }

    fun complete(
        result: Bundle,
        send: () -> Unit,
    ) {
        val outcome =
            if (failure != null) {
                "exception"
            } else if (result.getBoolean("success")) {
                "success"
            } else {
                "no_result"
            }
        terminal(phase, outcome, failure, send)
    }

    fun terminal(
        phase: String,
        outcome: String,
        failure: Exception? = null,
        send: () -> Unit,
    ) {
        if (terminalGate.claim()) {
            record(phase, outcome, failure)
            send()
        }
    }

    fun record(
        phase: String,
        outcome: String,
        failure: Exception? = null,
    ) {
        val error =
            when (failure) {
                is android.system.ErrnoException -> failure.errno
                is android.media.MediaCodec.CodecException -> failure.errorCode
                else -> 0
            }
        val kind = failure?.javaClass?.simpleName?.take(AV_ERROR_CHARS) ?: "none"
        android.util.Log.i(
            "MediaAvCapability",
            "$token|$case|$request|${Process.myPid()}|${Process.myUid()}|" +
                "$phase|$outcome|$kind|$error|${android.os.SystemClock.elapsedRealtimeNanos()}",
        )
    }

    companion object {
        fun from(
            data: Bundle,
            request: Int,
        ): AvCapabilityTrace? {
            val token = data.getString("runToken").orEmpty()
            val case = data.getString("caseId").orEmpty()
            val valid = token.matches(Regex("[0-9a-f]{32}")) && case.matches(Regex("[a-z_]{1,32}"))
            return if (valid) AvCapabilityTrace(token, case, request) else null
        }
    }
}

internal object AvCapabilityOperations {
    fun run(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        data: Bundle,
        result: Bundle,
        trace: AvCapabilityTrace,
    ) {
        when (data.getString("probeStage")) {
            "retriever_construct" -> {
                trace.phase("retriever_construct")
                android.media.MediaMetadataRetriever().use { result.putBoolean("success", true) }
            }
            "retriever_source" -> retriever(input, data, result, trace)
            "probe" -> probe(input, output, data, result, trace)
            "extractor", "codec" -> extractor(input, data, result, trace)
            "waveform" -> {
                trace.phase("waveform")
                val waveform =
                    dev.ipf.whitenoise.android.audio
                        .decodeHistoricalWaveformFromDescriptor(input.fileDescriptor)
                waveform?.let {
                    result.putFloatArray("waveform", it)
                    result.putBoolean("success", true)
                }
            }
        }
    }

    private fun retriever(
        input: ParcelFileDescriptor,
        data: Bundle,
        result: Bundle,
        trace: AvCapabilityTrace,
    ) {
        trace.phase("retriever_construct")
        android.media.MediaMetadataRetriever().use {
            trace.phase("retriever_source")
            it.setDataSource(input.fileDescriptor, data.getLong("offset"), data.getLong("length"))
            trace.phase("retriever_metadata")
            val duration = it.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            result.putLong("duration", duration?.toLongOrNull()?.coerceIn(0, MAX_MEDIA_DURATION_MS) ?: 0)
            result.putBoolean("success", true)
        }
    }

    private fun probe(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        data: Bundle,
        result: Bundle,
        trace: AvCapabilityTrace,
    ) {
        trace.phase("probe")
        val offset = data.getLong("offset")
        val length = data.getLong("length")
        val probe = probeHistoricalMediaFromDescriptor(input, AV_EDGE, true, offset, length) ?: return
        result.putLong("duration", probe.durationMs)
        result.putInt("sourceWidth", probe.width)
        result.putInt("sourceHeight", probe.height)
        probe.poster?.let { bitmap ->
            try {
                val count = MediaDecodePolicy.pixelBytes(bitmap.width, bitmap.height, AV_EDGE, output.size)
                val packed = bitmap.rowBytes == bitmap.width * Int.SIZE_BYTES
                if (count == null || bitmap.config != android.graphics.Bitmap.Config.ARGB_8888 || !packed) return
                val buffer = output.mapReadWrite()
                try {
                    buffer.limit(count)
                    bitmap.copyPixelsToBuffer(buffer)
                    result.putInt("width", bitmap.width)
                    result.putInt("height", bitmap.height)
                    result.putBoolean("pixels", true)
                } finally {
                    SharedMemory.unmap(buffer)
                }
            } finally {
                bitmap.recycle()
            }
        }
        result.putBoolean("success", true)
    }

    private fun extractor(
        input: ParcelFileDescriptor,
        data: Bundle,
        result: Bundle,
        trace: AvCapabilityTrace,
    ) {
        trace.phase("extractor_construct")
        val extractor = android.media.MediaExtractor()
        try {
            trace.phase("extractor_source")
            extractor.setDataSource(input.fileDescriptor, data.getLong("offset"), data.getLong("length"))
            trace.phase("extractor_tracks")
            val audio =
                (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (data.getString("probeStage") == "codec") {
                if (audio == null) return
                codec(audio, trace)
            }
            result.putBoolean("success", true)
        } finally {
            extractor.release()
        }
    }

    private fun codec(
        format: android.media.MediaFormat,
        trace: AvCapabilityTrace,
    ) {
        trace.phase("codec_create")
        val mime = checkNotNull(format.getString(android.media.MediaFormat.KEY_MIME))
        val codec = android.media.MediaCodec.createDecoderByType(mime)
        try {
            trace.phase("codec_configure")
            codec.configure(format, null, null, 0)
            trace.phase("codec_start")
            codec.start()
            codec.stop()
        } finally {
            codec.release()
        }
    }
}
