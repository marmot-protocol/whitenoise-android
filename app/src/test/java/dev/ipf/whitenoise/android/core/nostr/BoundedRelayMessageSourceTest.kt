package dev.ipf.whitenoise.android.core.nostr

import okio.Buffer
import okio.ForwardingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ProtocolException

class BoundedRelayMessageSourceTest {
    @Test
    fun textBinaryAndRepeatedMessagesHaveIndependentBudgets() {
        val wire = Buffer().write(frame(1, "abcd")).write(frame(2, "abcd")).write(frame(1, "abcd"))
        assertForwarded(wire, limit = 4)
    }

    @Test
    fun fragmentsShareBudgetWithoutControlFramesResettingIt() {
        val wire = Buffer().write(frame(1, "ab", isFinal = false)).write(frame(9, "ping")).write(frame(0, "cd"))
        assertForwarded(wire, limit = 4)
        val tooLarge = Buffer().write(frame(1, "abc", isFinal = false)).write(frame(10, "pong")).write(frame(0, "de"))
        assertThrows(ProtocolException::class.java) { drain(BoundedRelayMessageSource(tooLarge, 4)) }
        assertEquals("de", tooLarge.readUtf8())
    }

    @Test
    fun declaredOversizeIsRejectedBeforeReadingPayload() {
        for (opcode in listOf(1, 2)) {
            val wire = Buffer().write(frame(opcode, "abcde"))
            assertThrows(ProtocolException::class.java) { drain(BoundedRelayMessageSource(wire, 4)) }
            assertEquals("abcde", wire.readUtf8())
        }
    }

    @Test
    fun defaultReceiveLimitRejectsOversizeBeforeReadingTextOrBinaryPayload() {
        for (opcode in listOf(1, 2)) {
            val wire =
                Buffer()
                    .writeByte(0x80 or opcode)
                    .writeByte(127)
                    .writeLong(1024 * 1024L + 1)
                    .writeUtf8("unread")
            val failure = assertThrows(ProtocolException::class.java) { drain(BoundedRelayMessageSource(wire)) }
            assertEquals("Relay message exceeds receive limit", failure.message)
            assertEquals("unread", wire.readUtf8())
        }
    }

    @Test
    fun extendedLengthsAreCheckedBeforePayloadAndCannotOverflow() {
        val length16 =
            Buffer()
                .writeByte(0x81)
                .writeByte(126)
                .writeShort(126)
                .writeUtf8("unread")
        assertThrows(ProtocolException::class.java) { drain(BoundedRelayMessageSource(length16, 4)) }
        assertEquals("unread", length16.readUtf8())
        for (length in listOf(65536L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val length64 =
                Buffer()
                    .writeByte(0x82)
                    .writeByte(127)
                    .writeLong(length)
                    .writeUtf8("unread")
            assertThrows(ProtocolException::class.java) { drain(BoundedRelayMessageSource(length64, 4)) }
            assertEquals("unread", length64.readUtf8())
        }
    }

    @Test
    fun validExtendedLengthsPreservePayloadBytes() {
        assertForwarded(
            Buffer()
                .writeByte(0x82)
                .writeByte(126)
                .writeShort(126)
                .write(ByteArray(126)),
            limit = 126,
        )
        assertForwarded(
            Buffer()
                .writeByte(0x82)
                .writeByte(127)
                .writeLong(65536)
                .write(ByteArray(65536)),
            limit = 65536,
        )
    }

    @Test
    fun headersAndPayloadsCanArriveOneByteAtATime() {
        val wire = Buffer().write(frame(1, "abcd"))
        val expected = wire.clone().readByteString()
        val oneByte =
            object : ForwardingSource(wire) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = super.read(sink, minOf(byteCount, 1))
            }
        assertEquals(expected, drain(BoundedRelayMessageSource(oneByte, 4)).readByteString())
    }

    @Test
    fun truncatedHeaderPayloadAndUnfinishedMessageFailClosed() {
        val cases =
            listOf(
                Buffer().writeByte(0x81),
                Buffer().writeByte(0x81).writeByte(126).writeByte(0),
                Buffer().writeByte(0x81).writeByte(4).writeUtf8("ab"),
                Buffer().write(frame(1, "ab", isFinal = false)),
            )
        for (wire in cases) {
            assertThrows(EOFException::class.java) { drain(BoundedRelayMessageSource(wire, 4)) }
        }
    }

    @Test
    fun invalidFramingCannotBypassAccounting() {
        val cases =
            listOf(
                Buffer().writeByte(0xc1).writeByte(0), // Unnegotiated compression.
                Buffer().writeByte(0x81).writeByte(0x80), // Masked server frame.
                Buffer().writeByte(0x83).writeByte(0), // Reserved opcode.
                Buffer().write(frame(0, "")), // Continuation without a message.
                Buffer().write(frame(1, "", isFinal = false)).write(frame(2, "")),
                Buffer().write(frame(9, "", isFinal = false)),
                Buffer().writeByte(0x89).writeByte(126).writeShort(126),
                Buffer().writeByte(0x81).writeByte(126).writeShort(4), // Non-minimal length.
            )
        for (wire in cases) {
            assertThrows(ProtocolException::class.java) { drain(BoundedRelayMessageSource(wire, 4)) }
        }
    }

    @Test
    fun zeroLengthFramesAndControlFramesDoNotConsumeMessageBudget() {
        assertForwarded(
            Buffer()
                .write(frame(1, "", isFinal = false))
                .write(frame(9, "ping"))
                .write(frame(0, ""))
                .write(frame(2, "abcd"))
                .write(frame(8, "")),
            limit = 4,
        )
    }

    @Test
    fun payloadReadsStayBoundedAndCloseAndTimeoutAreDelegated() {
        val wire =
            Buffer()
                .writeByte(0x82)
                .writeByte(126)
                .writeShort(20000)
                .write(ByteArray(20000))
        var largestRead = 0L
        var closed = false
        val delegate =
            object : ForwardingSource(wire) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    largestRead = maxOf(largestRead, byteCount)
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
        val guarded = BoundedRelayMessageSource(delegate, 20000)
        assertTrue(guarded.timeout() === delegate.timeout())
        assertEquals(20004L, drain(guarded).size)
        assertTrue(largestRead <= 8192)
        guarded.close()
        assertTrue(closed)
    }

    private fun assertForwarded(
        wire: Buffer,
        limit: Long,
    ) {
        val expected = wire.clone().readByteString()
        assertEquals(expected, drain(BoundedRelayMessageSource(wire, limit)).readByteString())
    }

    private fun drain(source: BoundedRelayMessageSource): Buffer {
        val sink = Buffer()
        while (source.read(sink, Long.MAX_VALUE) != -1L) Unit
        return sink
    }

    private fun frame(
        opcode: Int,
        text: String,
        isFinal: Boolean = true,
    ): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size < 126)
        return Buffer()
            .writeByte(opcode or (if (isFinal) 0x80 else 0))
            .writeByte(bytes.size)
            .write(bytes)
            .readByteArray()
    }
}
