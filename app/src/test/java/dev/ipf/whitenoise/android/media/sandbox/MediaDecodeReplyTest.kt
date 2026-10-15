package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaDecodeReplyTest {
    @Test
    fun replyCarriesOnlyTheExpectedPrimitiveShape() {
        val decoded =
            roundTrip(
                Bundle().apply {
                    putBoolean("success", true)
                    putInt("width", 2)
                    putInt("height", 3)
                },
            )
        assertEquals(2, decoded.getInt("width"))
        assertEquals(3, decoded.getInt("height"))
    }

    @Test
    fun invalidWaveformAndMetadataFailBeforeTheyCanBecomeUiState() {
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(Bundle().apply { putFloatArray("waveform", FloatArray(65)) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(Bundle().apply { putFloatArray("waveform", FloatArray(64) { Float.NaN }) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(Bundle().apply { putLong("duration", Long.MAX_VALUE) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(Bundle().apply { putInt("sourceWidth", -1) })
        }
    }

    @Test
    fun malformedBooleanFixtureIsOtherwiseACompleteValidReply() {
        var received = false
        val target =
            object : android.os.Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                    assertEquals(42, data.readInt())
                    val payloadStart = data.dataPosition()
                    assertEquals(7, data.readInt())
                    data.setDataPosition(payloadStart)
                    assertThrows(IllegalArgumentException::class.java) { readMediaDecodeReply(data) }
                    // Repair only the boolean: the identical length and every remaining field must pass.
                    data.setDataPosition(payloadStart)
                    data.writeInt(0)
                    data.setDataPosition(payloadStart)
                    assertEquals(false, readMediaDecodeReply(data).getBoolean("success"))
                    received = true
                    return true
                }
            }
        sendMalformedMediaReply(target, 42, 4)
        assertEquals(true, received)
    }

    private fun roundTrip(result: Bundle): Bundle {
        val target =
            object : android.os.Binder() {
                var decoded: Bundle? = null

                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    assertEquals(IBinder.FIRST_CALL_TRANSACTION, code)
                    data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                    assertEquals(42, data.readInt())
                    decoded = readMediaDecodeReply(data)
                    return true
                }
            }
        sendMediaDecodeReply(target, 42, result)
        return requireNotNull(target.decoded)
    }
}
