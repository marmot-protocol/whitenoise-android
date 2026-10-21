package dev.ipf.whitenoise.android.media.sandbox

import android.os.IBinder
import android.os.Parcel

internal const val AV_READY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1
internal const val AV_READY_DESCRIPTOR = "dev.ipf.whitenoise.media.av.ready.v1"
internal const val AV_READY_MAX_BYTES = 512

internal data class AvProbeTuple(
    val token: String,
    val case: String,
    val request: Int,
) {
    init {
        require(token.matches(Regex("[0-9a-f]{32}")))
        require(case.matches(Regex("[a-z_]{1,40}")) && request > 0)
    }
}

internal data class AvProbeIdentity(
    val uid: Int,
    val pid: Int,
)

/** The owner additionally checks the exact endpoint/generation while accepting this capability. */
internal class AvProbeControl(
    private val tuple: AvProbeTuple,
) {
    private var revoked = false
    private var replied = false
    private var protocolAborted = false

    @Volatile var identity: AvProbeIdentity? = null
        private set

    @Synchronized
    fun ready(
        frame: AvProbeTuple,
        uid: Int,
        pid: Int,
        callerUid: Int,
    ): Boolean {
        val current = !revoked && !protocolAborted && identity == null
        val validIdentity = uid > 0 && uid != callerUid && pid > 0
        val accepted = current && frame == tuple && validIdentity
        if (accepted) identity = AvProbeIdentity(uid, pid)
        return accepted
    }

    @Synchronized
    fun terminal(
        request: Int,
        uid: Int,
    ): Boolean {
        val current = !revoked && !protocolAborted && !replied
        val matches = request == tuple.request && identity?.uid == uid
        val accepted = current && matches
        if (accepted) replied = true
        return accepted
    }

    @Synchronized
    fun canDispatch(): Boolean = !revoked && !protocolAborted

    @Synchronized
    fun canReleaseCase(
        cleanup: AvProbeCleanup.State,
        deathRegistered: Boolean,
        dead: Boolean,
        disposed: Boolean,
    ): Boolean {
        val identifiedAndRevoked = identity != null && revoked && !protocolAborted
        val exactDeath = deathRegistered && dead
        val clean = disposed && cleanup == AvProbeCleanup.State.SUCCESS
        return identifiedAndRevoked && exactDeath && clean
    }

    @Synchronized
    fun abortProtocol(): Boolean {
        val current = !revoked
        if (current) protocolAborted = true
        return current
    }

    @Synchronized
    fun revoke() {
        revoked = true
    }
}

internal fun sendAvProbeReady(
    callback: IBinder,
    tuple: AvProbeTuple,
) {
    val data = Parcel.obtain()
    val response = Parcel.obtain()
    try {
        data.writeInterfaceToken(AV_READY_DESCRIPTOR)
        data.writeInt(1)
        data.writeString(tuple.token)
        data.writeString(tuple.case)
        data.writeInt(tuple.request)
        check(callback.transact(AV_READY_TRANSACTION, data, response, 0))
        response.readException()
        check(response.readInt() == 1 && response.dataAvail() == 0)
    } finally {
        response.recycle()
        data.recycle()
    }
}

internal fun readAvProbeReady(data: Parcel): AvProbeTuple {
    require(data.dataSize() <= AV_READY_MAX_BYTES && !data.hasFileDescriptors())
    data.enforceInterface(AV_READY_DESCRIPTOR)
    require(data.readInt() == 1)
    val frame = AvProbeTuple(checkNotNull(data.readString()), checkNotNull(data.readString()), data.readInt())
    require(data.dataAvail() == 0)
    return frame
}
