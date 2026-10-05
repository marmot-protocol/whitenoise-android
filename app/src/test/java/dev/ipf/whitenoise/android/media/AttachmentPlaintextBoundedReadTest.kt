package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.OutputStream

/**
 * Pins the bounded presentation read: the declared size is checked before any allocation, a dishonest source cannot
 * stream past the limit, and cancellation stops the copy at the next chunk.
 */
class AttachmentPlaintextBoundedReadTest {
    /** One byte under the limit is read exactly, as one array of the declared size. */
    @Test
    fun aLeaseJustUnderTheLimitIsReadExactly() {
        val bytes = pattern(LIMIT - 1)
        val lease = lease(bytes)

        assertArrayEquals(bytes, lease.toByteArrayWithin(LIMIT.toLong()))
    }

    /** The limit is inclusive: a source of exactly the limit is still read. */
    @Test
    fun aLeaseExactlyAtTheLimitIsRead() {
        val bytes = pattern(LIMIT)

        assertArrayEquals(bytes, lease(bytes).toByteArrayWithin(LIMIT.toLong()))
    }

    /** One byte over the limit is rejected by its declared size, the caller still owns and closes the lease. */
    @Test
    fun aLeaseJustOverTheLimitIsRejectedWithItsDeclaredSize() {
        val lease = lease(pattern(LIMIT + 1))

        val failure =
            assertThrows(AttachmentTooLargeToPresentException::class.java) {
                lease.toByteArrayWithin(LIMIT.toLong())
            }

        assertEquals((LIMIT + 1).toLong(), failure.declaredBytes)
        assertEquals(LIMIT.toLong(), failure.limitBytes)
        assertTrue("the rejected lease must stay the caller's to close", lease.file.exists())
        lease.close()
        assertFalse(lease.file.exists())
    }

    /** A declaration far above the limit is rejected without touching the source, so nothing is allocated. */
    @Test
    fun anOversizedDeclarationIsRejectedBeforeAnyRead() {
        val source = UnreadablePlaintext(size = 10L * 1024L * 1024L * 1024L)

        val failure =
            assertThrows(AttachmentTooLargeToPresentException::class.java) {
                source.toByteArrayWithin(LIMIT.toLong())
            }

        assertEquals(source.size, failure.declaredBytes)
        assertFalse("an oversized declaration must not be read", source.read)
    }

    /** Memory-backed plaintext within the limit is handed back as is, without a copy. */
    @Test
    fun bytesWithinTheLimitAreReturnedWithoutACopy() {
        val bytes = pattern(LIMIT)

        assertSame(bytes, AttachmentPlaintext.Bytes(bytes).toByteArrayWithin(LIMIT.toLong()))
    }

    /** Memory-backed plaintext over the limit is rejected like any other source. */
    @Test
    fun bytesOverTheLimitAreRejected() {
        val source = AttachmentPlaintext.Bytes(pattern(LIMIT + 1))

        assertThrows(AttachmentTooLargeToPresentException::class.java) {
            source.toByteArrayWithin(LIMIT.toLong())
        }
    }

    /** A source that declares a small size but streams past the limit fails closed after at most the limit. */
    @Test
    fun aSourceThatOutgrowsItsDeclarationFailsClosedWithinTheLimit() {
        val source = StreamingPlaintext(declared = 8, total = LIMIT + 1, chunk = 7)

        val failure =
            assertThrows(AttachmentTooLargeToPresentException::class.java) {
                source.toByteArrayWithin(LIMIT.toLong())
            }

        assertNull("a streaming overrun has no trustworthy declared size", failure.declaredBytes)
        assertEquals(LIMIT.toLong(), failure.limitBytes)
        assertTrue("more than the limit was accepted: ${source.accepted}", source.accepted <= LIMIT)
        assertTrue("the overrun was not noticed near the limit: ${source.accepted}", source.accepted > LIMIT - 7)
    }

    /** A source that under-declares but stays within the limit is still read whole. */
    @Test
    fun aSourceUnderItsDeclarationButWithinTheLimitIsReadWhole() {
        val source = StreamingPlaintext(declared = 2, total = 50, chunk = 7)

        assertArrayEquals(pattern(50), source.toByteArrayWithin(LIMIT.toLong()))
    }

    /** A source that streams fewer bytes than it declared returns only what it streamed. */
    @Test
    fun aShortSourceReturnsOnlyTheBytesItStreamed() {
        val source = StreamingPlaintext(declared = 10, total = 4, chunk = 7)

        assertArrayEquals(pattern(4), source.toByteArrayWithin(LIMIT.toLong()))
    }

    /** The cancellation check runs before every chunk, so a cancelled read stops after the chunk in flight. */
    @Test
    fun theCancellationCheckStopsTheCopyBeforeTheNextChunk() {
        val source = StreamingPlaintext(declared = 21, total = 21, chunk = 7)
        var checks = 0

        assertThrows(CancellationException::class.java) {
            source.toByteArrayWithin(LIMIT.toLong()) {
                checks++
                if (checks == 2) throw CancellationException("reader left")
            }
        }

        assertEquals("only the first chunk may be copied", 7, source.accepted)
    }

    /** A limit that no byte array can hold is refused up front rather than failing later. */
    @Test
    fun aLimitLargerThanAnArrayIsRefused() {
        val source = AttachmentPlaintext.Bytes(pattern(4))

        assertThrows(IllegalArgumentException::class.java) {
            source.toByteArrayWithin(Int.MAX_VALUE.toLong() + 1L)
        }
    }

    /** The sink grows towards the limit and never past it, and refuses the write that would cross it. */
    @Test
    fun theSinkNeverGrowsPastTheLimit() {
        val sink = BoundedPlaintextSink(expected = 1, limit = LIMIT)
        var written = 0
        while (written < LIMIT) {
            val len = minOf(7, LIMIT - written)
            sink.write(pattern(len), 0, len)
            written += len
            assertTrue("the sink grew to ${sink.capacity} past the limit", sink.capacity <= LIMIT)
        }
        assertEquals(LIMIT, sink.size)

        assertThrows(AttachmentTooLargeToPresentException::class.java) { sink.write(1) }

        assertEquals("a refused write must not grow the sink", LIMIT, sink.capacity)
        assertEquals(LIMIT, sink.size)
    }

    /** Single-byte writes go through the same bound. */
    @Test
    fun singleByteWritesAreBounded() {
        val sink = BoundedPlaintextSink(expected = 0, limit = 2)
        sink.write(1)
        sink.write(2)

        assertThrows(AttachmentTooLargeToPresentException::class.java) { sink.write(3) }

        assertArrayEquals(byteArrayOf(1, 2), sink.toByteArray())
    }

    /** An exact declaration fills the array without a trailing copy, so the peak is one array of the limit. */
    @Test
    fun anExactDeclarationIsCollectedInOneArray() {
        val sink = BoundedPlaintextSink(expected = LIMIT, limit = LIMIT)
        sink.write(pattern(LIMIT), 0, LIMIT)

        assertEquals(LIMIT, sink.capacity)
        assertArrayEquals(pattern(LIMIT), sink.toByteArray())
    }

    /** A declaration above the limit cannot even construct the sink. */
    @Test
    fun theSinkRefusesADeclarationAboveTheLimit() {
        assertThrows(IllegalArgumentException::class.java) { BoundedPlaintextSink(expected = LIMIT + 1, limit = LIMIT) }
    }

    /** Deterministic bytes so misaligned copies are caught by content, not only by length. */
    private fun pattern(length: Int): ByteArray = ByteArray(length) { (it * 31 + 7).toByte() }

    /** A private-file lease holding [bytes], as the native chunked receive produces. */
    private fun lease(bytes: ByteArray): AttachmentPlaintext.Lease {
        val file = File.createTempFile("bounded-read", ".lease").apply { writeBytes(bytes) }
        return AttachmentPlaintext.Lease(DiskByteCacheLease(file))
    }

    /** Declares [size] and fails the test if anything tries to read it. */
    private class UnreadablePlaintext(
        override val size: Long,
    ) : AttachmentPlaintext {
        var read = false

        /** Records the read attempt and fails, because an oversized declaration must be rejected first. */
        override fun copyTo(output: OutputStream) {
            read = true
            fail("an oversized plaintext must be rejected before it is read")
        }

        /** Nothing to release. */
        override fun close() = Unit
    }

    /** Declares [declared] bytes but streams [total] bytes of the shared pattern in [chunk]-sized writes. */
    private class StreamingPlaintext(
        declared: Int,
        private val total: Int,
        private val chunk: Int,
    ) : AttachmentPlaintext {
        override val size: Long = declared.toLong()

        /** Bytes whose write returned normally, so bytes the sink accepted. */
        var accepted = 0

        /** Streams the pattern chunk by chunk, counting only writes the sink accepted. */
        override fun copyTo(output: OutputStream) {
            var offset = 0
            while (offset < total) {
                val len = minOf(chunk, total - offset)
                output.write(ByteArray(len) { ((offset + it) * 31 + 7).toByte() }, 0, len)
                accepted += len
                offset += len
            }
        }

        /** Nothing to release. */
        override fun close() = Unit
    }

    private companion object {
        const val LIMIT = 64
    }
}
