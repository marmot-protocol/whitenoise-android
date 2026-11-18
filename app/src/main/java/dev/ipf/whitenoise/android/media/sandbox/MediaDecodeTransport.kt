package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import java.io.File

// One isolated codec protocol owns raster, vector and AV worker lifecycle.
@Suppress("TooManyFunctions")
internal interface MediaDecodeTransport {
    fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap?

    fun exif(bytes: ByteArray): Int? = inspect(bytes)?.orientation

    fun inspect(bytes: ByteArray): ImageInspection?

    fun region(
        bytes: ByteArray,
        region: MediaImageRegion,
        edge: Int,
    ): Bitmap? = null

    fun svgInspect(bytes: ByteArray): ImageInspection? = null

    fun svgRegion(
        bytes: ByteArray,
        region: ViewerSvgRegion,
        edge: Int,
    ): Bitmap? = null

    fun animation(
        bytes: ByteArray,
        edge: Int,
    ): android.graphics.drawable.Drawable? = null

    fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? = null

    fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? = null

    fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? = null

    fun probeUriAt(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        positionMs: Long,
        edge: Int,
    ): MediaProbe? = null

    fun probeBytes(
        bytes: ByteArray,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? = null

    fun waveform(file: File): FloatArray? = null

    /**
     * Retires [domain]'s worker, such as a draft that was sent or emptied. A lane without domain-bound workers
     * has nothing to retire.
     */
    fun retireDomain(domain: MediaTrustDomain) = Unit

    /**
     * Gives [domain] the next worker while [prioritized], such as the conversation on screen. A lane without
     * domain-bound workers has no worker starts to order.
     */
    fun prioritizeDomain(
        domain: MediaTrustDomain,
        prioritized: Boolean,
    ) = Unit
}

/** A lane that starts domain-bound workers on demand, and can say which inputs need none started first. */
internal interface MediaWorkerReadiness {
    /** The [domains] whose next input needs no worker to start first. */
    fun servableNow(domains: List<MediaTrustDomain>): Set<MediaTrustDomain>
}

@Suppress("LongParameterList") // Fixed IPC request tuple, with mutually exclusive raster/vector region shapes.
internal data class MediaDecodeRequest(
    val edge: Int,
    val option: Boolean,
    val operation: Int,
    val offset: Long = 0L,
    val length: Long? = null,
    val region: MediaImageRegion? = null,
    val positionUs: Long = 0L,
    val svgRegion: ViewerSvgRegion? = null,
    val pdfPage: Int = 0,
)

internal data class PdfResult(
    val pages: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?,
)
