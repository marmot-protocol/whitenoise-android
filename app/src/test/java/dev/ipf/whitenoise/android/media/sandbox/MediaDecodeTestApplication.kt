package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import java.io.File

/** JVM rendering tests use an explicit codec fixture. Only device tests attest process isolation. */
class MediaDecodeTestApplication : WhiteNoiseApplication() {
    override fun onCreate() {
        super.onCreate()
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }
}

internal object TestMediaDecodeTransport : MediaDecodeTransport {
    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? =
        if (operation ==
            MediaDecodePolicy.SVG
        ) {
            PlatformMediaDecoder.svg(bytes)
        } else {
            PlatformMediaDecoder.decode(bytes, edge, oriented)
        }

    override fun exif(bytes: ByteArray): Int? =
        dev.ipf.whitenoise.android.media.MediaPipeline
            .readExifOrientationInDecoder(bytes)

    override fun inspect(bytes: ByteArray): ImageInspection? = PlatformMediaDecoder.inspect(bytes)

    override fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? = resolver.openInputStream(uri)?.use { PlatformMediaDecoder.decode(it.readBytes(), edge, true) }

    override fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? = resolver.openFileDescriptor(uri, "r")?.use { probeMediaFromDescriptor(it, edge, true) }

    override fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            probeMediaFromDescriptor(it, edge, poster)
        }
}
