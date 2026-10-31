package dev.ipf.whitenoise.android.media.sandbox

import android.os.IBinder
import android.os.Parcel

internal object AnimationFrameProtocol {
    const val DESCRIPTOR = "dev.ipf.whitenoise.media.animation.v1"
    const val STATUS_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1
    const val STATUS_PRIMITIVE_BYTES = 20
    const val CODEC_REJECTED = 1
    const val OPEN = 100
    const val PLAY = 101
    const val PAUSE = 102
    const val ACK = 103
    const val MAX_EDGE = 1080
    const val MAX_REPLY_BYTES = 256
    const val MIN_FRAME_DELAY_MS = 32L
    const val FLAG_COUNT = 3
    const val FRAME_PRIMITIVE_BYTES = 36
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
    // One-way Binder does not propagate a receiver's rejection back to the sender.
    // Validate before even obtaining the Parcel; the receiver independently checks untrusted primitives.
    require(frame.sequence > 0 && ViewerAnimationOutput.fromDimensions(frame.width, frame.height) != null)
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
    require(parcel.dataAvail() == AnimationFrameProtocol.FRAME_PRIMITIVE_BYTES)
    val session = parcel.readInt()
    val sequence = parcel.readLong()
    val width = parcel.readInt()
    val height = parcel.readInt()
    val flags =
        IntArray(AnimationFrameProtocol.FLAG_COUNT) {
            val value = parcel.readInt()
            require(value in 0..1)
            value
        }
    val canary = parcel.readInt()
    require(sequence > 0 && ViewerAnimationOutput.fromDimensions(width, height) != null)
    require(parcel.dataAvail() == 0)
    return AnimationFrameInfo(session, sequence, width, height, flags[0] == 1, flags[1] == 1, flags[2] == 1, canary)
}

/** Fixed primitive rejection notice; this is never a worker-death acknowledgement. */
internal data class AnimationStatusInfo(
    val session: Int,
    val reason: Int,
    val isolated: Boolean,
    val internetGranted: Boolean,
    val runtimeInitialized: Boolean,
)

/** Worker side: sends a one-way rejection notice for [AnimationStatusInfo.session] on the status transaction. */
internal fun writeAnimationStatus(
    callback: IBinder,
    status: AnimationStatusInfo,
) {
    // A one-way callback cannot return the host's rejection of an unsupported status reason.
    require(status.reason == AnimationFrameProtocol.CODEC_REJECTED)
    val parcel = Parcel.obtain()
    try {
        parcel.writeInterfaceToken(AnimationFrameProtocol.DESCRIPTOR)
        parcel.writeInt(status.session)
        parcel.writeInt(status.reason)
        parcel.writeInt(if (status.isolated) 1 else 0)
        parcel.writeInt(if (status.internetGranted) 1 else 0)
        parcel.writeInt(if (status.runtimeInitialized) 1 else 0)
        check(callback.transact(AnimationFrameProtocol.STATUS_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY))
    } finally {
        parcel.recycle()
    }
}

/**
 * Host side: parses a status reply, rejecting oversized or malformed parcels, any reason other than
 * [AnimationFrameProtocol.CODEC_REJECTED], non-boolean flags and trailing bytes.
 */
internal fun readAnimationStatus(parcel: Parcel): AnimationStatusInfo {
    require(parcel.dataAvail() <= AnimationFrameProtocol.MAX_REPLY_BYTES)
    parcel.enforceInterface(AnimationFrameProtocol.DESCRIPTOR)
    require(parcel.dataAvail() == AnimationFrameProtocol.STATUS_PRIMITIVE_BYTES)
    val session = parcel.readInt()
    val reason = parcel.readInt()
    require(reason == AnimationFrameProtocol.CODEC_REJECTED)
    val flags = IntArray(3) { parcel.readInt().also { require(it in 0..1) } }
    require(parcel.dataAvail() == 0)
    return AnimationStatusInfo(session, reason, flags[0] == 1, flags[1] == 1, flags[2] == 1)
}
