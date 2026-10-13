package dev.ipf.whitenoise.android.media.sandbox

import android.os.IBinder
import android.os.Parcel

internal object AnimationFrameProtocol {
    const val DESCRIPTOR = "dev.ipf.whitenoise.media.animation.v1"
    const val OPEN = 100
    const val PLAY = 101
    const val PAUSE = 102
    const val ACK = 103
    const val MAX_EDGE = 1080
    const val MAX_REPLY_BYTES = 256
    const val MIN_FRAME_DELAY_MS = 32L
    const val FLAG_COUNT = 3
}

internal data class AnimationFrameInfo(
    val session: Int,
    val sequence: Long,
    val width: Int,
    val height: Int,
    val isolated: Boolean,
    val internetGranted: Boolean,
    val runtimeInitialized: Boolean,
    val privateCanaryErrno: Int,
)

internal fun writeAnimationFrame(
    callback: IBinder,
    frame: AnimationFrameInfo,
) {
    val parcel = Parcel.obtain()
    try {
        parcel.writeInterfaceToken(AnimationFrameProtocol.DESCRIPTOR)
        parcel.writeInt(frame.session)
        parcel.writeLong(frame.sequence)
        parcel.writeInt(frame.width)
        parcel.writeInt(frame.height)
        parcel.writeInt(if (frame.isolated) 1 else 0)
        parcel.writeInt(if (frame.internetGranted) 1 else 0)
        parcel.writeInt(if (frame.runtimeInitialized) 1 else 0)
        parcel.writeInt(frame.privateCanaryErrno)
        check(callback.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY))
    } finally {
        parcel.recycle()
    }
}

internal fun readAnimationFrame(parcel: Parcel): AnimationFrameInfo {
    require(parcel.dataAvail() <= AnimationFrameProtocol.MAX_REPLY_BYTES)
    parcel.enforceInterface(AnimationFrameProtocol.DESCRIPTOR)
    val session = parcel.readInt()
    val sequence = parcel.readLong()
    val width = parcel.readInt()
    val height = parcel.readInt()
    val flags = IntArray(AnimationFrameProtocol.FLAG_COUNT) { parcel.readInt().also { value -> require(value in 0..1) } }
    val canary = parcel.readInt()
    require(sequence > 0 && width in 1..AnimationFrameProtocol.MAX_EDGE && height in 1..AnimationFrameProtocol.MAX_EDGE)
    require(parcel.dataAvail() == 0)
    return AnimationFrameInfo(session, sequence, width, height, flags[0] == 1, flags[1] == 1, flags[2] == 1, canary)
}
