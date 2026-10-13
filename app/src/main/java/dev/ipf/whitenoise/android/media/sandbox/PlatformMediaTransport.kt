package dev.ipf.whitenoise.android.media.sandbox

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.ipf.whitenoise.android.audio.AudioWaveformExtractor
import java.io.File

/** Explicit FD-only platform-service route. Android denies these services to isolated UIDs. */
internal class PlatformMediaTransport(
    context: Context,
) : MediaDecodeTransport,
    java.io.Closeable {
    private val sources = MediaSourceAccess(context)
    private val refusals = MediaRefusalCache()

    @Volatile private var closed = false

    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? = null

    override fun inspect(bytes: ByteArray): ImageInspection? = null

    override fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        probeRequest(edge) { ensureCurrent ->
            withFile(file, "probe:$edge:$poster", ensureCurrent) { input ->
                probeMediaFromDescriptor(input, edge, poster)?.let { validateMediaProbe(it, edge) }
            }
        }

    override fun probeBytes(
        bytes: ByteArray,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        probeRequest(edge) { ensureCurrent ->
            if (bytes.size.toLong() !in 1..MAX_MEDIA_SOURCE_BYTES) return@probeRequest null
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
            val key = "probe:$edge:$poster:" + digest.joinToString("") { "%02x".format(it) }
            refusals.attempt(key, ensureCurrent) {
                sources.withInput(bytes) { input ->
                    ensureCurrent()
                    probeMediaFromDescriptor(input, edge, poster)?.let { validateMediaProbe(it, edge) }
                }
            }
        }

    override fun probeUri(
        resolver: ContentResolver,
        uri: Uri,
        edge: Int,
    ): MediaProbe? =
        probeRequest(edge) { ensureCurrent ->
            sources.withMediaUriInput(resolver, uri) { input ->
                ensureCurrent()
                probeMediaFromDescriptor(input, edge, true)
            }
        }

    override fun waveform(file: File): FloatArray? =
        BoundedMediaExecutor.shared.run(MediaDecodePolicy.WAVEFORM_DEADLINE_MS, { !closed }) { ensureCurrent ->
            withFile(file, "waveform", ensureCurrent) { input ->
                AudioWaveformExtractor.decodeFromDescriptor(input.fileDescriptor, ensureCurrent)?.takeIf { values ->
                    values.size == AudioWaveformExtractor.BARS && values.all { it.isFinite() && it in 0f..1f }
                }
            }
        }

    private fun probeRequest(
        edge: Int,
        block: (() -> Unit) -> MediaProbe?,
    ): MediaProbe? {
        if (MediaDecodePolicy.outputCapacity(edge) == null) return null
        return BoundedMediaExecutor.shared.run(MediaDecodePolicy.DEADLINE_MS, { !closed }, ::disposeProbe) { check ->
            block(check)?.let { validateMediaProbe(it, edge) }
        }
    }

    @Suppress("ReturnCount") // File type, bounded size and cooldown are distinct source admission guards.
    private fun <T> withFile(
        file: File,
        operation: String,
        check: () -> Unit,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        check()
        if (!file.isFile || file.length() !in 1..MAX_MEDIA_SOURCE_BYTES) return null
        val key = "$operation:${file.absolutePath}:${file.length()}:${file.lastModified()}"
        return refusals.attempt(key, check) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                if (input.statSize !in 1..MAX_MEDIA_SOURCE_BYTES) null else block(input)
            }
        }
    }

    override fun close() {
        closed = true
        sources.close()
    }
}

internal fun disposeProbe(probe: MediaProbe) {
    probe.poster?.recycle()
}

internal fun validateMediaProbe(
    probe: MediaProbe,
    edge: Int,
): MediaProbe? {
    val dimensionsValid =
        (probe.width == 0 && probe.height == 0) || MediaDecodePolicy.sourceAllowed(probe.width, probe.height)
    val bitmap = probe.poster
    val bitmapValid =
        bitmap == null ||
            (
                MediaDecodePolicy.pixelBytes(
                    bitmap.width,
                    bitmap.height,
                    edge,
                    MediaDecodePolicy.MAX_OUTPUT_BYTES,
                ) != null &&
                    bitmap.allocationByteCount <= MediaDecodePolicy.MAX_OUTPUT_BYTES
            )
    return if (dimensionsValid && bitmapValid && probe.durationMs in 0..MAX_MEDIA_DURATION_MS) {
        probe
    } else {
        disposeProbe(probe)
        null
    }
}
