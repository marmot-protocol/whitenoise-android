package dev.ipf.whitenoise.android.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Largest GIF or WebP the composer reads to preview a picked or shared source, 8 MiB.
 *
 * A presentation ceiling only: it bounds what Android copies into memory before admission and says
 * nothing about what MDK uploads or acquires.
 */
internal const val LOCAL_PREVIEW_SOURCE_MAX_BYTES: Int = 8 * 1024 * 1024

private const val PREVIEW_SIGNATURE_BYTES = 12
private const val PREVIEW_READ_CHUNK_BYTES = 64 * 1024

/**
 * Which decoder a locally picked or shared image may reach, decided from its bytes alone.
 *
 * Provider MIME types, file names and dimensions are never consulted, so a GIF presented as a JPEG
 * is still admitted or refused as a GIF.
 */
internal sealed interface LocalPreviewSource {
    /** An admitted GIF or animated WebP; [bytes] is the complete source for the animated decoder. */
    class Animation(
        val bytes: ByteArray,
    ) : LocalPreviewSource

    /** Not an animation container, or a still WebP that passed the full walk: the sampled still path may run. */
    data object Still : LocalPreviewSource

    /**
     * A recognized GIF or WebP that is malformed, over an admission limit or over the byte ceiling,
     * or a source that could not be read. No decoder, animated or still, may see it.
     */
    data object Refused : LocalPreviewSource
}

/**
 * Reads [open] just far enough to classify it. Only a GIF or WebP signature leads to reading the whole
 * source, and never more than [maxBytes] of it; every other format costs a twelve-byte header read.
 * The stream is always closed. A source that cannot be opened or read, for any provider failure, is
 * [LocalPreviewSource.Refused]; cancellation is never swallowed. [ensureActive] runs before every chunk so
 * cancellation stops the copy.
 */
@Suppress("TooGenericExceptionCaught") // A content provider can fail with any runtime exception, all meaning refused.
internal fun readLocalPreviewSource(
    open: () -> InputStream?,
    maxBytes: Int = LOCAL_PREVIEW_SOURCE_MAX_BYTES,
    ensureActive: () -> Unit = {},
): LocalPreviewSource =
    try {
        open()?.use { input -> classifyPreviewStream(input, maxBytes, ensureActive) } ?: LocalPreviewSource.Refused
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        LocalPreviewSource.Refused
    } catch (_: RuntimeException) {
        LocalPreviewSource.Refused
    }

/** Classifies an open [input]; the caller owns closing it. */
private fun classifyPreviewStream(
    input: InputStream,
    maxBytes: Int,
    ensureActive: () -> Unit,
): LocalPreviewSource {
    val header = ByteArray(PREVIEW_SIGNATURE_BYTES)
    var headerLength = 0
    while (headerLength < header.size) {
        val read = input.read(header, headerLength, header.size - headerLength)
        if (read < 0) break
        headerLength += read
    }
    val prefix = header.copyOf(headerLength)
    val animationCandidate = isGif(prefix) || isWebp(prefix)
    val source = if (animationCandidate) readBoundedSource(input, prefix, maxBytes, ensureActive) else null
    return when {
        !animationCandidate -> LocalPreviewSource.Still
        source == null -> LocalPreviewSource.Refused
        else ->
            when (admitAnimationSource(source)) {
                is AnimationSourceAdmission.Admitted -> LocalPreviewSource.Animation(source)
                is AnimationSourceAdmission.Refused -> LocalPreviewSource.Refused
                AnimationSourceAdmission.NotAnimation -> LocalPreviewSource.Still
            }
    }
}

/** Reads the rest of [input] after [prefix], or null as soon as the source would pass [maxBytes]. */
private fun readBoundedSource(
    input: InputStream,
    prefix: ByteArray,
    maxBytes: Int,
    ensureActive: () -> Unit,
): ByteArray? {
    val source = ByteArrayOutputStream(minOf(maxBytes, PREVIEW_READ_CHUNK_BYTES))
    source.write(prefix)
    val chunk = ByteArray(PREVIEW_READ_CHUNK_BYTES)
    var withinCeiling = source.size() <= maxBytes
    while (withinCeiling) {
        ensureActive()
        val read = input.read(chunk)
        if (read < 0) break
        withinCeiling = source.size() + read <= maxBytes
        if (withinCeiling) source.write(chunk, 0, read)
    }
    return source.toByteArray().takeIf { withinCeiling }
}

/** Runs [decode] on the animated decoder only for an admitted animation; any other source returns null. */
internal fun <T : Any> decodeLocalPreviewAnimation(
    source: LocalPreviewSource,
    decode: (ByteArray) -> T?,
): T? = (source as? LocalPreviewSource.Animation)?.let { decode(it.bytes) }

/**
 * Runs [decode] on the sampled still decoder unless the source was refused. An admitted animation may
 * still reach it, which shows the first frame of an already admitted canvas.
 */
internal fun <T : Any> decodeLocalPreviewStill(
    source: LocalPreviewSource,
    decode: () -> T?,
): T? = if (source === LocalPreviewSource.Refused) null else decode()
