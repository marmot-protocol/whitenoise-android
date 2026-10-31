package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

internal const val MEDIA_REPLY_DESCRIPTOR = "dev.ipf.whitenoise.media.pixels.v2"
private const val MIN_MEDIA_REPLY_BYTES = 72

/** Worker replies contain only explicitly read primitives, never arbitrary parcelable objects. */
internal fun sendMediaDecodeReply(
    callback: IBinder,
    id: Int,
    result: Bundle,
    synchronous: Boolean = false,
) {
    val parcel = Parcel.obtain()
    val response = if (synchronous) Parcel.obtain() else null
    try {
        parcel.writeInterfaceToken(MEDIA_REPLY_DESCRIPTOR)
        parcel.writeInt(id)
        writeMediaDecodePayload(parcel, result)
        val flags = if (synchronous) 0 else IBinder.FLAG_ONEWAY
        val accepted = callback.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, response, flags)
        if (synchronous) {
            val acknowledgement = checkNotNull(response)
            check(accepted && acknowledgement.dataAvail() == 0 && !acknowledgement.hasFileDescriptors())
        }
    } finally {
        parcel.recycle()
        response?.recycle()
    }
}

private fun writeMediaDecodePayload(
    parcel: Parcel,
    result: Bundle,
) {
    parcel.writeInt(if (result.getBoolean("success")) 1 else 0)
    parcel.writeInt(if (result.getBoolean("isolated")) 1 else 0)
    parcel.writeInt(if (result.getBoolean("internetGranted")) 1 else 0)
    parcel.writeInt(if (result.getBoolean("runtimeInitialized")) 1 else 0)
    parcel.writeInt(if (result.getBoolean("privateCanaryDenied")) 1 else 0)
    parcel.writeInt(result.getInt("privateCanaryErrno"))
    parcel.writeInt(result.getInt("inputWriteErrno", -1))
    parcel.writeInt(result.getInt("width"))
    parcel.writeInt(result.getInt("height"))
    parcel.writeInt(result.getInt("orientation"))
    parcel.writeString(result.getString("mime").orEmpty().take(MediaDecodePolicy.MAX_MIME_CHARS))
    parcel.writeInt(result.getInt("sourceWidth"))
    parcel.writeInt(result.getInt("sourceHeight"))
    parcel.writeLong(result.getLong("duration"))
    parcel.writeInt(if (result.getBoolean("pixels")) 1 else 0)
    parcel.writeFloatArray(result.getFloatArray("waveform"))
}

internal fun readMediaDecodeReply(parcel: Parcel): Bundle {
    require(parcel.dataAvail() >= MIN_MEDIA_REPLY_BYTES)
    val result = Bundle()
    result.putBoolean("success", readMediaBoolean(parcel))
    result.putBoolean("isolated", readMediaBoolean(parcel))
    result.putBoolean("internetGranted", readMediaBoolean(parcel))
    result.putBoolean("runtimeInitialized", readMediaBoolean(parcel))
    result.putBoolean("privateCanaryDenied", readMediaBoolean(parcel))
    result.putInt("privateCanaryErrno", parcel.readInt())
    result.putInt("inputWriteErrno", parcel.readInt())
    result.putInt("width", parcel.readInt())
    result.putInt("height", parcel.readInt())
    val orientation = parcel.readInt()
    require(orientation == 0 || orientation in 1..MediaDecodePolicy.MAX_EXIF_ORIENTATION)
    result.putInt("orientation", orientation)
    val mime = parcel.readString().orEmpty()
    require(mime.length <= MediaDecodePolicy.MAX_MIME_CHARS)
    result.putString("mime", mime)
    val sourceWidth = parcel.readInt()
    val sourceHeight = parcel.readInt()
    require((sourceWidth == 0 && sourceHeight == 0) || MediaDecodePolicy.sourceAllowed(sourceWidth, sourceHeight))
    result.putInt("sourceWidth", sourceWidth)
    result.putInt("sourceHeight", sourceHeight)
    val duration = parcel.readLong()
    require(duration in 0..MAX_MEDIA_DURATION_MS)
    result.putLong("duration", duration)
    result.putBoolean("pixels", readMediaBoolean(parcel))
    val count = parcel.readInt()
    require(count == -1 || count == dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS)
    if (count == dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS) {
        require(parcel.dataAvail() == count * Float.SIZE_BYTES)
        val waveform = FloatArray(count) { parcel.readFloat() }
        require(waveform.all { it.isFinite() && it in 0f..1f })
        result.putFloatArray("waveform", waveform)
    }
    require(parcel.dataAvail() == 0)
    return result
}

private fun readMediaBoolean(parcel: Parcel): Boolean = parcel.readInt().also { require(it in 0..1) } == 1

/** Actual isolated-UID callback corruption for debug instrumentation, never a production command. */
@Suppress("MagicNumber") // Four controlled malformed-frame fixtures, available only through debug dispatch.
internal fun sendMalformedMediaReply(
    callback: IBinder,
    id: Int,
    mode: Int,
) {
    val parcel = Parcel.obtain()
    try {
        parcel.writeInterfaceToken(MEDIA_REPLY_DESCRIPTOR)
        parcel.writeInt(if (mode == 1) id + 1 else id)
        when (mode) {
            2 -> parcel.writeInt(1) // Truncated primitive frame.
            3 -> parcel.writeByteArray(ByteArray(MediaDecodePolicy.MAX_REPLY_BYTES))
            4 -> {
                // Keep the rest of the frame complete so rejection reaches the boolean guard.
                val start = parcel.dataPosition()
                writeMediaDecodePayload(parcel, Bundle().apply { putBoolean("isolated", true) })
                val end = parcel.dataPosition()
                parcel.setDataPosition(start)
                parcel.writeInt(7)
                parcel.setDataPosition(end)
            }
        }
        callback.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY)
    } finally {
        parcel.recycle()
    }
}
