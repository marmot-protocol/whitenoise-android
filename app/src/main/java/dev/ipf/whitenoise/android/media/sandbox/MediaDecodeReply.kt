package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

internal const val MEDIA_REPLY_DESCRIPTOR = "dev.ipf.whitenoise.media.pixels.v1"

/** Worker replies contain only explicitly read primitives, never arbitrary parcelable objects. */
internal fun sendMediaDecodeReply(
    callback: IBinder,
    id: Int,
    result: Bundle,
) {
    val parcel = Parcel.obtain()
    try {
        parcel.writeInterfaceToken(MEDIA_REPLY_DESCRIPTOR)
        parcel.writeInt(id)
        parcel.writeInt(if (result.getBoolean("success")) 1 else 0)
        parcel.writeInt(if (result.getBoolean("isolated")) 1 else 0)
        parcel.writeInt(if (result.getBoolean("internetGranted")) 1 else 0)
        parcel.writeInt(if (result.getBoolean("runtimeInitialized")) 1 else 0)
        parcel.writeInt(if (result.getBoolean("privateCanaryDenied")) 1 else 0)
        parcel.writeInt(result.getInt("privateCanaryErrno"))
        parcel.writeInt(result.getInt("width"))
        parcel.writeInt(result.getInt("height"))
        parcel.writeInt(result.getInt("orientation"))
        parcel.writeString(result.getString("mime").orEmpty().take(MediaDecodePolicy.MAX_MIME_CHARS))
        parcel.writeInt(result.getInt("sourceWidth"))
        parcel.writeInt(result.getInt("sourceHeight"))
        parcel.writeLong(result.getLong("duration"))
        parcel.writeInt(if (result.getBoolean("pixels")) 1 else 0)
        parcel.writeFloatArray(result.getFloatArray("waveform"))
        callback.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY)
    } finally {
        parcel.recycle()
    }
}

internal fun readMediaDecodeReply(parcel: Parcel): Bundle {
    val result = Bundle()
    result.putBoolean("success", parcel.readInt() == 1)
    result.putBoolean("isolated", parcel.readInt() == 1)
    result.putBoolean("internetGranted", parcel.readInt() == 1)
    result.putBoolean("runtimeInitialized", parcel.readInt() == 1)
    result.putBoolean("privateCanaryDenied", parcel.readInt() == 1)
    result.putInt("privateCanaryErrno", parcel.readInt())
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
    result.putBoolean("pixels", parcel.readInt() == 1)
    val count = parcel.readInt()
    require(count == -1 || count == dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS)
    if (count == dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS) {
        val waveform = FloatArray(count) { parcel.readFloat() }
        require(waveform.all { it.isFinite() && it in 0f..1f })
        result.putFloatArray("waveform", waveform)
    }
    require(parcel.dataAvail() == 0)
    return result
}
