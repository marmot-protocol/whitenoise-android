package dev.ipf.whitenoise.android.media.sandbox

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimationFrameProtocolTest {
    @Test
    fun framesContainOnlyBoundedCanonicalPrimitives() {
        val frame = AnimationFrameInfo(42, 1, 2, 3, true, false, false, -1)
        var received: AnimationFrameInfo? = null
        val receiver =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    received = readAnimationFrame(data)
                    return true
                }
            }
        writeAnimationFrame(receiver, frame)
        assertEquals(frame, received)
    }

    @Test
    fun oversizedTrailingAndInvalidSequenceOrDimensionsNeverBecomeFrames() {
        for (variant in 0..5) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInterfaceToken(AnimationFrameProtocol.DESCRIPTOR)
                parcel.writeInt(42)
                parcel.writeLong(if (variant == 1) 0 else 1)
                parcel.writeInt(if (variant == 2) 4097 else 2)
                parcel.writeInt(3)
                parcel.writeInt(if (variant == 3) 7 else 1)
                parcel.writeInt(0)
                parcel.writeInt(0)
                if (variant != 5) parcel.writeInt(-1)
                if (variant == 0) parcel.writeByteArray(ByteArray(300))
                if (variant == 4) parcel.writeInt(123)
                parcel.setDataPosition(0)
                assertThrows(IllegalArgumentException::class.java) { readAnimationFrame(parcel) }
            } finally {
                parcel.recycle()
            }
        }
    }

    /** A source-sized rectangular frame is bounded by pixels, not a square maximum-edge allocation. */
    @Test
    fun viewerFramesPermitOnlyAdmittedCanvasDimensions() {
        val frame = AnimationFrameInfo(42, 1, 4096, 1024, true, false, false, -1)
        var received: AnimationFrameInfo? = null
        var transactions = 0
        val receiver =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    transactions++
                    received = readAnimationFrame(data)
                    return true
                }
            }
        writeAnimationFrame(receiver, frame)
        assertEquals(frame, received)
        for ((width, height) in listOf(4096 to 4096, Int.MAX_VALUE to 2, 1 to 4097)) {
            assertThrows(IllegalArgumentException::class.java) {
                writeAnimationFrame(receiver, frame.copy(width = width, height = height))
            }
            assertEquals(1, transactions)
        }
        for (sequence in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                writeAnimationFrame(receiver, frame.copy(sequence = sequence))
            }
            assertEquals(1, transactions)
        }
    }

    /** Forged worker bytes bypass sender checks and must still fail in the independent host parser. */
    @Test
    fun forgedFramesCannotBypassReceiverCanvasOrSequenceValidation() {
        val valid = AnimationFrameInfo(42, 1, 4096, 1024, true, false, false, -1)
        val invalid =
            listOf(
                valid.copy(width = 4096, height = 4096),
                valid.copy(width = Int.MAX_VALUE, height = 2),
                valid.copy(width = 1, height = 4097),
                valid.copy(width = 0),
                valid.copy(height = -1),
                valid.copy(sequence = 0),
                valid.copy(sequence = -1),
            )
        for (frame in invalid) {
            val parcel = Parcel.obtain()
            try {
                writeForgedFramePrimitives(parcel, frame)
                parcel.setDataPosition(0)
                assertThrows(IllegalArgumentException::class.java) { readAnimationFrame(parcel) }
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun writeForgedFramePrimitives(
        parcel: Parcel,
        frame: AnimationFrameInfo,
    ) {
        parcel.writeInterfaceToken(AnimationFrameProtocol.DESCRIPTOR)
        parcel.writeInt(frame.session)
        parcel.writeLong(frame.sequence)
        parcel.writeInt(frame.width)
        parcel.writeInt(frame.height)
        parcel.writeInt(1)
        parcel.writeInt(0)
        parcel.writeInt(0)
        parcel.writeInt(frame.privateCanaryErrno)
    }

    /** A status round-trips as fixed primitives, and unknown codes, flags, missing fields or trailing data fail. */
    @Test
    fun statusContainsOnlyTheFixedKnownPrimitiveShape() {
        val status = AnimationStatusInfo(42, AnimationFrameProtocol.CODEC_REJECTED, true, false, false)
        var received: AnimationStatusInfo? = null
        val receiver =
            object : Binder() {
                /** Asserts the status transaction code and decodes the status parcel into [received]. */
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    assertEquals(AnimationFrameProtocol.STATUS_TRANSACTION, code)
                    received = readAnimationStatus(data)
                    return true
                }
            }
        writeAnimationStatus(receiver, status)
        assertEquals(status, received)
        for (variant in 0..4) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInterfaceToken(AnimationFrameProtocol.DESCRIPTOR)
                parcel.writeInt(42)
                parcel.writeInt(if (variant == 0) 999 else AnimationFrameProtocol.CODEC_REJECTED)
                parcel.writeInt(if (variant == 1) 7 else 1)
                parcel.writeInt(0)
                if (variant != 2) parcel.writeInt(0)
                if (variant == 3) parcel.writeInt(9)
                if (variant == 4) parcel.writeByteArray(ByteArray(300))
                parcel.setDataPosition(0)
                assertThrows(IllegalArgumentException::class.java) { readAnimationStatus(parcel) }
            } finally {
                parcel.recycle()
            }
        }
    }
}
