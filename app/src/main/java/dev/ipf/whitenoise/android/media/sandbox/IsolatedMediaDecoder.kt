package dev.ipf.whitenoise.android.media.sandbox

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.Process

/** All production still-image codec calls enter the permissionless service, never a local fallback. */
@Suppress("TooManyFunctions") // One process-owned facade for the complete platform codec boundary.
internal object IsolatedMediaDecoder {
    @Volatile private var transport: MediaDecodeTransport? = null

    @Volatile private var mediaTransport: MediaDecodeTransport? = null
    private var installedContext: Context? = null

    @Volatile internal var lastIsolation: WorkerIsolation? = null
        private set

    internal fun recordIsolation(result: android.os.Bundle) {
        lastIsolation =
            WorkerIsolation(
                result.getInt("uid"),
                result.getBoolean("isolated"),
                result.getBoolean("internetGranted"),
                result.getBoolean("runtimeInitialized"),
                result.getBoolean("privateCanaryDenied"),
                result.getInt("privateCanaryErrno"),
            )
    }

    fun install(context: Context) {
        if (!Process.isIsolated()) {
            clear()
            installedContext = context.applicationContext
            transport = IsolatedMediaTransport(context.applicationContext, "images")
            mediaTransport = PlatformMediaTransport(context.applicationContext)
        }
    }

    /** Explicit test seam; production initialization always installs the real IPC transport. */
    internal fun installTestTransport(next: MediaDecodeTransport?) {
        transport = next
        mediaTransport = next
    }

    internal fun resetForAccount() {
        if (transport is IsolatedMediaTransport) {
            val currentContext = installedContext ?: return
            clear()
            install(currentContext)
        }
    }

    fun clear() {
        (transport as? java.io.Closeable)?.close()
        (mediaTransport as? java.io.Closeable)?.close()
        transport = null
        mediaTransport = null
    }

    fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean = false,
    ): Bitmap? = safely { it.decode(bytes, edge, oriented, MediaDecodePolicy.DECODE) }

    fun probe(
        file: java.io.File,
        edge: Int = 1280,
        poster: Boolean = true,
    ): MediaProbe? = safely { mediaTransport?.probe(file, edge, poster) }

    fun waveform(file: java.io.File): FloatArray? = safely { mediaTransport?.waveform(file) }

    fun probeBytes(
        bytes: ByteArray,
        edge: Int = 1280,
        poster: Boolean = true,
    ): MediaProbe? = safely { mediaTransport?.probeBytes(bytes, edge, poster) }

    fun probeUri(
        resolver: ContentResolver,
        uri: Uri,
        edge: Int,
    ): MediaProbe? = safely { mediaTransport?.probeUri(resolver, uri, edge) }

    internal fun exerciseWatchdogForTest() {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        safely { it.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DEBUG_HANG) }
    }

    fun svg(bytes: ByteArray): Bitmap? =
        safely {
            it.decode(bytes, MediaDecodePolicy.SVG_EDGE, false, MediaDecodePolicy.SVG)
        }

    fun exif(bytes: ByteArray): Int? = safely { it.exif(bytes) }

    fun inspect(bytes: ByteArray): ImageInspection? = safely { it.inspect(bytes) }

    fun decodeUri(
        resolver: ContentResolver,
        uri: Uri,
        edge: Int,
    ): Bitmap? = safely { it.decodeUri(resolver, uri, edge) }

    private fun <T> safely(block: (MediaDecodeTransport) -> T?): T? =
        try {
            if (transport is IsolatedMediaTransport &&
                Looper.myLooper() == Looper.getMainLooper()
            ) {
                null
            } else {
                transport?.let(block)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
}

internal data class WorkerIsolation(
    val uid: Int,
    val isolated: Boolean,
    val internetGranted: Boolean,
    val runtimeInitialized: Boolean,
    val privateCanaryDenied: Boolean,
    val privateCanaryErrno: Int,
)
