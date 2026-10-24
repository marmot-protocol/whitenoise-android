package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

internal const val AV_SOFT_TIMEOUT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 2
internal const val AV_SOFT_TIMEOUT_DESCRIPTOR = "dev.ipf.whitenoise.media.av.soft-timeout.v1"
internal const val AV_SOFT_REFUSAL_MS = 5000L

/** A real worker refusal carries admission facts, never successful media or shared output. */
internal data class AvProbeSoftTimeout(
    val tuple: AvProbeTuple,
    val isolated: Boolean,
    val internetGranted: Boolean,
    val runtimeInitialized: Boolean,
    val privateCanaryErrno: Int,
    val inputWriteErrno: Int,
) {
    fun evidence(): Bundle =
        Bundle().apply {
            putBoolean("success", false)
            putBoolean("pixels", false)
            putBoolean("isolated", isolated)
            putBoolean("internetGranted", internetGranted)
            putBoolean("runtimeInitialized", runtimeInitialized)
            putInt("privateCanaryErrno", privateCanaryErrno)
            putBoolean("privateCanaryDenied", privateCanaryErrno in deniedCanaryErrors)
            putInt("inputWriteErrno", inputWriteErrno)
            putBoolean("avSoftTimeout", true)
        }

    companion object {
        fun observed(
            tuple: AvProbeTuple,
            result: Bundle,
        ): AvProbeSoftTimeout {
            val required =
                listOf(
                    "isolated",
                    "internetGranted",
                    "runtimeInitialized",
                    "privateCanaryErrno",
                    "inputWriteErrno",
                )
            require(required.all(result::containsKey))
            return AvProbeSoftTimeout(
                tuple,
                result.getBoolean("isolated"),
                result.getBoolean("internetGranted"),
                result.getBoolean("runtimeInitialized"),
                result.getInt("privateCanaryErrno"),
                result.getInt("inputWriteErrno"),
            )
        }
    }
}

private val deniedCanaryErrors =
    setOf(android.system.OsConstants.EACCES, android.system.OsConstants.EPERM, android.system.OsConstants.ENOENT)

/** Debug-only primitive transaction; production pixel-v2 replies are unchanged. */
internal fun writeAvProbeSoftTimeout(
    parcel: Parcel,
    frame: AvProbeSoftTimeout,
) {
    parcel.writeInterfaceToken(AV_SOFT_TIMEOUT_DESCRIPTOR)
    parcel.writeInt(1)
    parcel.writeString(frame.tuple.token)
    parcel.writeString(frame.tuple.case)
    parcel.writeInt(frame.tuple.request)
    parcel.writeInt(if (frame.isolated) 1 else 0)
    parcel.writeInt(if (frame.internetGranted) 1 else 0)
    parcel.writeInt(if (frame.runtimeInitialized) 1 else 0)
    parcel.writeInt(frame.privateCanaryErrno)
    parcel.writeInt(frame.inputWriteErrno)
}

internal fun readAvProbeSoftTimeout(parcel: Parcel): AvProbeSoftTimeout {
    require(parcel.dataSize() <= AV_READY_MAX_BYTES && !parcel.hasFileDescriptors())
    parcel.enforceInterface(AV_SOFT_TIMEOUT_DESCRIPTOR)
    require(parcel.readInt() == 1)
    val tuple = AvProbeTuple(checkNotNull(parcel.readString()), checkNotNull(parcel.readString()), parcel.readInt())

    fun boolean(): Boolean = parcel.readInt().also { require(it in 0..1) } == 1
    val frame = AvProbeSoftTimeout(tuple, boolean(), boolean(), boolean(), parcel.readInt(), parcel.readInt())
    require(parcel.dataAvail() == 0)
    require(frame.isolated && !frame.internetGranted && !frame.runtimeInitialized)
    require(frame.privateCanaryErrno in deniedCanaryErrors && frame.inputWriteErrno == android.system.OsConstants.EBADF)
    return frame
}

/** Synchronous debug callback preserves kernel caller PID; a blocked send cannot delay the separate hard kill. */
internal fun sendAvProbeSoftTimeout(
    callback: IBinder,
    frame: AvProbeSoftTimeout,
) {
    val data = Parcel.obtain()
    val response = Parcel.obtain()
    try {
        writeAvProbeSoftTimeout(data, frame)
        check(callback.transact(AV_SOFT_TIMEOUT_TRANSACTION, data, response, 0))
        response.readException()
        check(response.readInt() == 1 && response.dataAvail() == 0)
    } finally {
        response.recycle()
        data.recycle()
    }
}

/** Exactly one claimant owns both the terminal diagnostic and its Binder send attempt. */
internal class AvProbeTerminalGate {
    private val claimed = AtomicBoolean()

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

/** AV-only timers expose no per-request cancellation handle, even after a normal terminal send. */
internal class AvProbeDeadlines(
    private val kill: () -> Unit,
) : AutoCloseable {
    private val hard = Executors.newSingleThreadScheduledExecutor()
    private val soft = Executors.newSingleThreadScheduledExecutor()
    private val armed = AtomicBoolean()

    fun arm(
        hardMs: Long,
        softMs: Long?,
        notify: () -> Unit,
    ) {
        require(hardMs > 0 && (softMs == null || softMs in 1 until hardMs))
        check(armed.compareAndSet(false, true))
        hard.schedule(Runnable { kill() }, hardMs, TimeUnit.MILLISECONDS)
        if (softMs != null) soft.schedule(Runnable { notify() }, softMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        hard.shutdownNow()
        soft.shutdownNow()
    }
}
