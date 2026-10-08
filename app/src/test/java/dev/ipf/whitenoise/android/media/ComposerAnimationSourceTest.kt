package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.animatedWebp
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gif
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gifFrame
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.riff
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.vp8l
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/**
 * The composer's local preview entry point: selected or shared sources reach a native decoder only after
 * the same content admission as received animations. Spy decoders count every call; no pixels are decoded.
 */
class ComposerAnimationSourceTest {
    /** A stream that records closure and how many bytes were consumed from [bytes]. */
    private class SpyStream(
        bytes: ByteArray,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var consumed = 0
            private set
        var closed = false
            private set

        /** Reads one byte, or fails once [failAfter] bytes were served. */
        override fun read(): Int {
            if (consumed >= failAfter) throw IOException("stream failure")
            return delegate.read().also { if (it >= 0) consumed++ }
        }

        /** Reads a block no larger than the bytes left before the configured failure. */
        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (consumed >= failAfter) throw IOException("stream failure")
            val allowed = minOf(length, failAfter - consumed)
            return delegate.read(buffer, offset, allowed).also { if (it > 0) consumed += it }
        }

        /** Records that the owner released the stream. */
        override fun close() {
            closed = true
        }
    }

    /** Counts calls into the two decoders a preview may reach. */
    private class DecoderSpy {
        var animated = 0
        var still = 0

        /** Runs the animated decode as production wires it and counts the call. */
        fun animation(source: LocalPreviewSource) = decodeLocalPreviewAnimation(source) { animated++ }

        /** Runs the sampled still decode as production wires it and counts the call. */
        fun still(source: LocalPreviewSource) = decodeLocalPreviewStill(source) { still++.let { Unit } }
    }

    /** Reads [bytes] as a local source, returning the classification and its spy stream. */
    private fun read(
        bytes: ByteArray,
        maxBytes: Int = LOCAL_PREVIEW_SOURCE_MAX_BYTES,
    ): Pair<LocalPreviewSource, SpyStream> {
        val stream = SpyStream(bytes)
        return readLocalPreviewSource({ stream }, maxBytes) to stream
    }

    /** A tiny valid GIF is admitted, its complete bytes reach the animated decoder once, and the stream closes. */
    @Test
    fun tinyValidGifReachesTheAnimatedDecoderOnce() {
        val bytes = gif()
        val (source, stream) = read(bytes)
        val spy = DecoderSpy()

        assertTrue(source is LocalPreviewSource.Animation)
        assertArrayEquals(bytes, (source as LocalPreviewSource.Animation).bytes)
        spy.animation(source)
        assertEquals(1, spy.animated)
        assertTrue(stream.closed)
    }

    /** An admitted animation may still show its first frame through the sampled still decoder. */
    @Test
    fun admittedAnimationMayFallBackToItsStillFirstFrame() {
        val (source, _) = read(gif())
        val spy = DecoderSpy()

        spy.still(source)

        assertEquals(1, spy.still)
    }

    /** An animated WebP is admitted like a GIF. */
    @Test
    fun animatedWebpIsAdmitted() {
        val (source, _) = read(animatedWebp())

        assertTrue(source is LocalPreviewSource.Animation)
    }

    /** A fully walked still WebP takes the ordinary still path and never the animated decoder. */
    @Test
    fun stillWebpTakesTheStillPathOnly() {
        val (source, _) = read(riff(vp8l(3, 2)))
        val spy = DecoderSpy()

        assertSame(LocalPreviewSource.Still, source)
        spy.animation(source)
        spy.still(source)
        assertEquals(0, spy.animated)
        assertEquals(1, spy.still)
    }

    /** A declared canvas above the admission limit reaches neither decoder. */
    @Test
    fun excessiveDeclaredCanvasReachesNoDecoder() {
        val (source, _) = read(gif(width = 5000, height = 5000))

        assertRefusedBeforeAnyDecoder(source)
    }

    /** More frames than the aggregate work budget allows reach neither decoder. */
    @Test
    fun excessiveFrameWorkReachesNoDecoder() {
        val frames = List(9) { gifFrame() }

        val (source, _) = read(gif(width = 2048, height = 2048, frames = frames))

        assertRefusedBeforeAnyDecoder(source)
    }

    /** A GIF cut off mid-frame is malformed and reaches neither decoder. */
    @Test
    fun truncatedGifReachesNoDecoder() {
        val whole = gif()

        val (source, _) = read(whole.copyOf(whole.size - 6))

        assertRefusedBeforeAnyDecoder(source)
    }

    /** A source that is a GIF by content is refused as a GIF however its provider labelled it. */
    @Test
    fun dishonestMimeDoesNotBypassAdmission() {
        // The caller never sees the MIME type here, which is the point: the label cannot enable or skip admission.
        val (source, _) = read(gif(width = 5000, height = 5000))
        val spy = DecoderSpy()

        // A caller that trusted an image/jpeg label would run the still decoder, which must also be skipped.
        assertNull(spy.still(source))
        assertEquals(0, spy.still)
    }

    /** A JPEG labelled image/gif is just a still: the animated decoder is never called for it. */
    @Test
    fun jpegLabelledAsGifNeverReachesTheAnimatedDecoder() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) + ByteArray(64)
        val (source, _) = read(jpeg)
        val spy = DecoderSpy()

        assertSame(LocalPreviewSource.Still, source)
        spy.animation(source)
        assertEquals(0, spy.animated)
    }

    /** A source exactly at the byte ceiling is read, and one byte over it is refused. */
    @Test
    fun encodedByteBoundaryAdmitsTheCeilingAndRefusesOneOver() {
        val bytes = gif()

        val (atCeiling, _) = read(bytes, maxBytes = bytes.size)
        val (oneOver, _) = read(bytes, maxBytes = bytes.size - 1)

        assertTrue(atCeiling is LocalPreviewSource.Animation)
        assertSame(LocalPreviewSource.Refused, oneOver)
    }

    /** An oversized source is never copied whole: reading stops at the first chunk past the ceiling. */
    @Test
    fun oversizedSourceStopsReadingAtTheCeiling() {
        val bytes = gif() + ByteArray(1_000_000)
        val stream = SpyStream(bytes)

        val source = readLocalPreviewSource({ stream }, maxBytes = 1024)

        assertSame(LocalPreviewSource.Refused, source)
        assertTrue("read ${stream.consumed} bytes", stream.consumed < 200_000)
        assertTrue(stream.closed)
    }

    /** A format that is not GIF or WebP costs only its header, so ordinary photos are never copied. */
    @Test
    fun ordinaryPhotoReadsOnlyItsHeader() {
        val stream = SpyStream(byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(5_000_000))

        val source = readLocalPreviewSource({ stream })

        assertSame(LocalPreviewSource.Still, source)
        assertTrue("read ${stream.consumed} bytes", stream.consumed <= 12)
        assertTrue(stream.closed)
    }

    /** A stream that fails part-way is refused, never handed to a fallback decoder, and is closed. */
    @Test
    fun streamFailureMidReadReachesNoDecoderAndCloses() {
        val stream = SpyStream(gif(), failAfter = 20)

        val source = readLocalPreviewSource({ stream })

        assertRefusedBeforeAnyDecoder(source)
        assertTrue(stream.closed)
    }

    /** A source that cannot be opened at all is refused rather than left loading. */
    @Test
    fun unopenableSourcesAreRefused() {
        assertSame(LocalPreviewSource.Refused, readLocalPreviewSource({ null }))
        assertSame(LocalPreviewSource.Refused, readLocalPreviewSource({ throw IOException("gone") }))
        assertSame(LocalPreviewSource.Refused, readLocalPreviewSource({ throw SecurityException("revoked") }))
    }

    /** A provider that fails with an unchecked exception, at open or while reading, is refused, not crashed. */
    @Test
    fun providerRuntimeFailuresAreRefused() {
        assertSame(
            LocalPreviewSource.Refused,
            readLocalPreviewSource({ throw IllegalArgumentException("bad uri") }),
        )
        val failing =
            object : InputStream() {
                /** Fails like an unregistered provider stream. */
                override fun read(): Int = throw UnsupportedOperationException("no stream")
            }

        assertSame(LocalPreviewSource.Refused, readLocalPreviewSource({ failing }))
    }

    /** Cancellation stops the copy, propagates to the caller, and still closes the stream. */
    @Test
    fun cancellationPropagatesAndClosesTheStream() {
        val stream = SpyStream(gif())

        try {
            readLocalPreviewSource({ stream }, ensureActive = { throw CancellationException("removed") })
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("removed", expected.message)
        }

        assertTrue(stream.closed)
    }

    /** Asserts the source is refused and neither spy decoder is ever called for it. */
    private fun assertRefusedBeforeAnyDecoder(source: LocalPreviewSource) {
        val spy = DecoderSpy()

        assertSame(LocalPreviewSource.Refused, source)
        assertNull(spy.animation(source))
        assertNull(spy.still(source))
        assertEquals(0, spy.animated)
        assertEquals(0, spy.still)
    }
}
