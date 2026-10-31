package dev.ipf.whitenoise.android.media.sandbox

import android.os.IBinder
import android.os.Parcel

/** Input-free READY precedes automatic AV descriptor handoff; pixel-v2 replies are unchanged. */
internal object AutomaticAvIdentity {
    const val MESSAGE = 12
    const val CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1
    const val DESCRIPTOR = "dev.ipf.whitenoise.media.av.identity.v1"
    const val MAX_BYTES = 256
    const val POLL_MS = 100L
    const val QUICK_POLLS = 80
    const val HELD_POLL_MS = 1000L

    fun observationDelay(attempt: Int): Long = if (attempt < QUICK_POLLS) POLL_MS else HELD_POLL_MS
}

internal fun sendAutomaticAvIdentity(
    callback: IBinder,
    request: Int,
): Boolean {
    if (request <= 0) return false
    val data = Parcel.obtain()
    val response = Parcel.obtain()
    return try {
        data.writeInterfaceToken(AutomaticAvIdentity.DESCRIPTOR)
        data.writeInt(request)
        callback.transact(AutomaticAvIdentity.CALLBACK, data, response, 0) &&
            !response.hasFileDescriptors() &&
            response.dataAvail() == Int.SIZE_BYTES &&
            response.readInt() == 1
    } finally {
        response.recycle()
        data.recycle()
    }
}
