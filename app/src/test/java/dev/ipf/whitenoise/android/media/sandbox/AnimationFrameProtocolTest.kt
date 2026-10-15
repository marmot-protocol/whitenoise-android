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
                parcel.writeInt(if (variant == 2) 4096 else 2)
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
}
