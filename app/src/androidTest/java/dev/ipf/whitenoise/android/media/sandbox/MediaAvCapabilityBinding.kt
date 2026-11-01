package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Each case is either complete or a typed failed observation; neither grants production support. */
internal sealed interface AvCapabilityCase {
    val observation: JSONObject

    data class Terminal(
        override val observation: JSONObject,
    ) : AvCapabilityCase

    data class ReplyDeadline(
        override val observation: JSONObject,
    ) : AvCapabilityCase
}

private class AvReplyDeadline : Exception("Terminal reply deadline")

/** One owner retains uncertain startup, handles or cleanup; close cannot create another generation. */
internal class MediaAvCapabilityBinding(
    private val context: Context,
    private val callerIdentity: () -> AvProbeIdentity = {
        AvProbeIdentity(Binder.getCallingUid(), Binder.getCallingPid())
    },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val lock = Any()
    private val callbacks = Executors.newSingleThreadExecutor()
    private var selected: AvCapabilityWorker? = null

    @Volatile private var closed = false

    private companion object {
        const val DEATH_GRACE_MS = 2_000L
    }

    fun run(
        file: File,
        request: Bundle,
    ): AvCapabilityCase {
        val started = now()
        val worker = reserve(request, started)
        val connection = connection(worker)
        worker.dispose = { dispose(worker, connection) }
        var deadline = false
        var retirementStarted: Long? = null
        var operationElapsed = 0L
        try {
            bind(worker, connection)
            val connected = worker.connected.await(worker.remaining(), TimeUnit.MILLISECONDS)
            check(connected) { "Unobserved startup remains held" }
            checkNotNull(worker.remote)
            withHandles(worker, file) { input, output ->
                exchange(worker, input, output, request)
                check(worker.remaining() > 0) { "Late reply remains unqualified" }
                operationElapsed = now() - started
                retirementStarted = now()
                retire(worker)
                check(worker.dead.await(DEATH_GRACE_MS, TimeUnit.MILLISECONDS)) { "Exact Binder death missing" }
                AvCapabilityEvidence.validate(checkNotNull(worker.result), output)
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (_: AvReplyDeadline) {
            deadline = true
            retirementStarted = now()
            operationElapsed = now() - started
        } finally {
            worker.revoke()
            retire(worker)
            worker.cleanup.finished()
            worker.releaseIfFinished()
        }
        awaitCleanup(worker, retirementStarted)
        return observation(worker, started, deadline, operationElapsed, retirementStarted)
    }

    private fun reserve(
        request: Bundle,
        started: Long,
    ): AvCapabilityWorker {
        val ceiling =
            if (request.getString("probeStage") == "waveform") {
                MediaDecodePolicy.WAVEFORM_DEADLINE_MS
            } else {
                MediaDecodePolicy.DEADLINE_MS
            }
        val tuple =
            AvProbeTuple(
                checkNotNull(request.getString("runToken")),
                checkNotNull(request.getString("caseId")),
                request.getInt("requestId"),
            )
        return synchronized(lock) {
            check(!closed && selected == null) { "Prior worker lifetime is unresolved" }
            AvCapabilityWorker(tuple, started + ceiling, ::withCurrent, callerIdentity, now).also { selected = it }
        }
    }

    private fun awaitCleanup(
        worker: AvCapabilityWorker,
        retirementStarted: Long?,
    ) {
        retirementStarted?.let {
            val remaining = (it + DEATH_GRACE_MS - now()).coerceAtLeast(0)
            check(worker.cleanupDone.await(remaining, TimeUnit.MILLISECONDS)) { heldState(worker) }
        }
        check(
            worker.control.canReleaseCase(
                worker.cleanup.state(),
                worker.deathRegistered,
                worker.dead.count == 0L,
                worker.cleanupDone.count == 0L,
            ),
        ) {
            heldState(worker)
        }
    }

    private fun heldState(worker: AvCapabilityWorker): String =
        "ready=${worker.control.identity != null}, protocolAbort=${worker.protocolAborted}, " +
            "deathRegistered=${worker.deathRegistered}, dead=${worker.dead.count == 0L}, " +
            "cleanupAttemptFinished=${worker.cleanupDone.count == 0L}, handleCleanup=${worker.cleanup.holdReason()}"

    private fun observation(
        worker: AvCapabilityWorker,
        started: Long,
        deadline: Boolean,
        operationElapsed: Long,
        retirementStarted: Long?,
    ): AvCapabilityCase {
        val identity = checkNotNull(worker.control.identity) { "READY identity missing" }
        check(worker.deathRegistered && worker.dead.count == 0L) { "Exact Binder death missing" }
        val retired = retirementStarted?.let { now() - it } ?: 0L
        val observation =
            if (deadline) {
                JSONObject()
                    .put("request", worker.tuple.request)
                    .put("timely_terminal_reply", false)
                    .put("terminal_reply_seen", worker.result != null)
                    .put("output_read", false)
                    .put("elapsed_ms", now() - started)
                    .put("operation_elapsed_ms", operationElapsed)
                    .put("retirement_elapsed_ms", retired)
            } else {
                AvCapabilityEvidence.row(
                    worker.tuple.request,
                    checkNotNull(worker.result),
                    now() - started,
                    operationElapsed,
                    retired,
                )
            }
        observation
            .put("kernel_ready_uid", identity.uid)
            .put("kernel_ready_pid", identity.pid)
            .put("death_observed", true)
            .put("capability_revoked", true)
            .put("selected_scopes_closed", true)
            .put("unbind_succeeded", true)
            .put("cleanup_succeeded", true)
        return if (deadline) AvCapabilityCase.ReplyDeadline(observation) else AvCapabilityCase.Terminal(observation)
    }

    private fun withCurrent(
        worker: AvCapabilityWorker,
        accept: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            val currentOwner = !closed && selected === worker
            val currentEndpoint = worker.remote != null && worker.deathRegistered
            val live = worker.dead.count != 0L && worker.remaining() > 0
            if (currentOwner && currentEndpoint && live) accept() else false
        }

    private fun withHandles(
        worker: AvCapabilityWorker,
        file: File,
        action: (ParcelFileDescriptor, SharedMemory) -> Unit,
    ) {
        val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        worker.input = input
        worker.cleanup.inputScopeClosed(false)
        try {
            val mode = Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
            check(mode == OsConstants.O_RDONLY)
            val output = SharedMemory.create("av-capability", checkNotNull(MediaDecodePolicy.outputCapacity(128)))
            worker.output = output
            worker.cleanup.outputScopeClosed(false)
            try {
                action(input, output)
            } finally {
                output.close()
                worker.cleanup.outputScopeClosed(true)
                worker.output = null
            }
        } finally {
            input.close()
            worker.cleanup.inputScopeClosed(true)
            worker.input = null
        }
    }

    private fun bind(
        worker: AvCapabilityWorker,
        connection: ServiceConnection,
    ) {
        val intent = Intent().setClassName(context, "dev.ipf.whitenoise.android.media.sandbox.MediaAvCapabilityService")
        try {
            val accepted =
                context.bindIsolatedService(
                    intent,
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName("av", worker.tuple.request),
                    callbacks,
                    connection,
                )
            synchronized(lock) {
                worker.bound = worker.bound || accepted
                if (!accepted && !worker.bound) worker.cleanup.lifetimeProven()
            }
            check(accepted) { "Binding refused; no capability result" }
        } finally {
            worker.cleanup.bindComplete()
        }
    }

    private fun connection(worker: AvCapabilityWorker): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(lock) {
                    if (selected !== worker || worker.remote != null) return
                    worker.bound = true
                    worker.remote = service
                }
                worker.deathRegistered =
                    try {
                        service.linkToDeath(worker.death, 0)
                        true
                    } catch (_: RemoteException) {
                        false
                    }
                worker.connected.countDown()
                if (!worker.deathRegistered || closed) retire(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }

    private fun exchange(
        worker: AvCapabilityWorker,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: Bundle,
    ) {
        check(worker.deathRegistered) { "No registered exact death witness" }
        request.putInt("version", MediaDecodePolicy.VERSION)
        request.putParcelable("input", input)
        request.putParcelable("output", output)
        check(worker.dead.count != 0L) { "Worker is already retired" }
        val message =
            Message.obtain(null, MediaDecodePolicy.DECODE, worker.tuple.request, 0).apply {
                data = request
                replyTo = Messenger(worker.reply)
            }
        synchronized(lock) {
            val currentOwner = !closed && selected === worker
            check(currentOwner && worker.control.canDispatch() && worker.dead.count != 0L) { "Revoked request" }
            Messenger(checkNotNull(worker.remote)).send(message)
        }
        if (!worker.replied.await(worker.remaining(), TimeUnit.MILLISECONDS)) {
            worker.revoke()
            throw AvReplyDeadline()
        }
        check(!worker.protocolAborted) { "Current terminal protocol or identity rejected" }
        worker.revoke()
    }

    private fun retire(worker: AvCapabilityWorker) {
        worker.revoke()
        worker.remote?.let {
            try {
                Messenger(it).send(Message.obtain(null, MediaDecodePolicy.RETIRE))
            } catch (_: RemoteException) {
                // A failed send does not establish death or successful cleanup.
            }
        }
    }

    private fun dispose(
        worker: AvCapabilityWorker,
        connection: ServiceConnection,
    ) {
        var success = false
        try {
            context.unbindService(connection)
            success = true
        } catch (_: IllegalArgumentException) {
            // A failed exact unbind is a held lane, never a successful disposal.
        } catch (_: SecurityException) {
            // Preserve the selected generation and its handles for diagnosis.
        } finally {
            worker.cleanup.unbound(success)
            if (!success) worker.cleanupDone.countDown()
        }
        if (worker.cleanup.state() != AvProbeCleanup.State.SUCCESS) {
            worker.cleanupDone.countDown()
            return
        }
        worker.remote?.let {
            try {
                it.unlinkToDeath(worker.death, 0)
            } catch (_: NoSuchElementException) {
                // A dead endpoint may already have removed this recipient.
            }
        }
        val terminal =
            synchronized(lock) {
                if (selected === worker) selected = null
                closed
            }
        worker.cleanupDone.countDown()
        if (terminal) callbacks.shutdown()
    }

    override fun close() {
        val worker =
            synchronized(lock) {
                closed = true
                selected
            }
        if (worker == null) callbacks.shutdown() else retire(worker)
    }
}

private class AvCapabilityWorker(
    val tuple: AvProbeTuple,
    private val deadline: Long,
    private val withCurrent: (AvCapabilityWorker, () -> Boolean) -> Boolean,
    private val callerIdentity: () -> AvProbeIdentity,
    private val now: () -> Long,
) {
    val connected = CountDownLatch(1)
    val replied = CountDownLatch(1)
    val dead = CountDownLatch(1)
    val cleanupDone = CountDownLatch(1)
    val control = AvProbeControl(tuple)
    val cleanup = AvProbeCleanup()
    var dispose: (() -> Unit)? = null
    var input: ParcelFileDescriptor? = null
    var output: SharedMemory? = null

    @Volatile var remote: IBinder? = null

    @Volatile var bound = false

    @Volatile var deathRegistered = false

    @Volatile var result: Bundle? = null

    @Volatile var protocolAborted = false
    private var readyEndpoint: IBinder? = null
    val death =
        IBinder.DeathRecipient {
            cleanup.lifetimeProven()
            dead.countDown()
            releaseIfFinished()
        }
    val reply =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                response: Parcel?,
                flags: Int,
            ): Boolean =
                when (code) {
                    AV_READY_TRANSACTION -> ready(data, response, flags)
                    IBinder.FIRST_CALL_TRANSACTION -> terminal(data)
                    AV_SOFT_TIMEOUT_TRANSACTION -> softTimeout(data, response, flags)
                    else -> super.onTransact(code, data, response, flags)
                }

            private fun ready(
                data: Parcel,
                response: Parcel?,
                flags: Int,
            ): Boolean {
                val frame =
                    try {
                        require(response != null && flags and IBinder.FLAG_ONEWAY == 0)
                        readAvProbeReady(data)
                    } catch (_: RuntimeException) {
                        return rejectTerminal()
                    }
                val identity = callerIdentity()
                val accepted =
                    withCurrent(this@AvCapabilityWorker) {
                        control
                            .ready(
                                frame,
                                identity.uid,
                                identity.pid,
                                android.os.Process.myUid(),
                            ).also {
                                if (it) readyEndpoint = remote else abortTerminal()
                            }
                    }
                if (accepted) {
                    checkNotNull(response).writeNoException()
                    response.writeInt(1)
                }
                return accepted
            }

            private fun rejectTerminal(): Boolean {
                withCurrent(this@AvCapabilityWorker) { abortTerminal() }
                return false
            }

            private fun abortTerminal(): Boolean {
                val aborted = control.abortProtocol()
                if (aborted) {
                    protocolAborted = true
                    replied.countDown()
                }
                return aborted
            }

            private fun softTimeout(
                data: Parcel,
                response: Parcel?,
                flags: Int,
            ): Boolean {
                val frame =
                    try {
                        require(response != null && flags and IBinder.FLAG_ONEWAY == 0)
                        readAvProbeSoftTimeout(data).also { require(it.tuple == tuple) }
                    } catch (_: RuntimeException) {
                        return rejectTerminal()
                    }
                val identity = callerIdentity()
                val accepted =
                    withCurrent(this@AvCapabilityWorker) {
                        if (identity.pid != control.identity?.pid) {
                            abortTerminal()
                            false
                        } else {
                            acceptTerminal(frame.evidence(), identity.uid)
                        }
                    }
                if (accepted) {
                    checkNotNull(response).writeNoException()
                    response.writeInt(1)
                }
                return accepted
            }

            private fun terminal(data: Parcel): Boolean {
                val parsed =
                    try {
                        require(!data.hasFileDescriptors() && data.dataAvail() <= MediaDecodePolicy.MAX_REPLY_BYTES)
                        data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                        require(data.readInt() == tuple.request)
                        readMediaDecodeReply(data)
                    } catch (_: RuntimeException) {
                        return rejectTerminal()
                    }
                val uid = callerIdentity().uid
                return withCurrent(this@AvCapabilityWorker) { acceptTerminal(parsed, uid) }
            }

            private fun acceptTerminal(
                parsed: Bundle,
                uid: Int,
            ): Boolean {
                if (readyEndpoint !== remote || !control.terminal(tuple.request, uid)) {
                    abortTerminal()
                    return false
                }
                parsed.putInt("uid", uid)
                result = parsed
                replied.countDown()
                return true
            }
        }

    fun revoke() {
        control.revoke()
        cleanup.revoked()
    }

    fun releaseIfFinished() {
        if (cleanup.beginUnbind()) dispose?.invoke()
    }

    fun remaining(): Long = (deadline - now()).coerceAtLeast(0)
}

/** Consume only the bounded primitive reply and copy pixels after the caller's exact-death barrier. */
internal object AvCapabilityEvidence {
    fun validate(
        result: Bundle,
        output: SharedMemory,
    ) {
        val uid = result.getInt("uid")
        check(uid > 0 && uid != android.os.Process.myUid())
        check(result.getBoolean("isolated") && !result.getBoolean("internetGranted"))
        check(!result.getBoolean("runtimeInitialized") && result.getBoolean("privateCanaryDenied"))
        check(result.getInt("inputWriteErrno") == OsConstants.EBADF)
        if (result.getBoolean("pixels")) {
            val width = result.getInt("width")
            val height = result.getInt("height")
            val count = checkNotNull(MediaDecodePolicy.pixelBytes(width, height, 128, output.size))
            val pixels = output.mapReadOnly()
            try {
                pixels.limit(count)
                val config = android.graphics.Bitmap.Config.ARGB_8888
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, config)
                try {
                    bitmap.copyPixelsFromBuffer(pixels)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                SharedMemory.unmap(pixels)
            }
        }
    }

    fun row(
        id: Int,
        result: Bundle,
        elapsed: Long,
        operationElapsed: Long,
        retirementElapsed: Long,
    ): JSONObject =
        JSONObject()
            .put("request", id)
            .put("kernel_reply_uid", result.getInt("uid"))
            .put("isolated", result.getBoolean("isolated"))
            .put("internet_granted", result.getBoolean("internetGranted"))
            .put("runtime_initialized", result.getBoolean("runtimeInitialized"))
            .put("private_canary_denied", result.getBoolean("privateCanaryDenied"))
            .put("private_canary_errno", result.getInt("privateCanaryErrno"))
            .put("success", result.getBoolean("success"))
            .put("death_observed_before_output_read", true)
            .put("elapsed_ms", elapsed)
            .put("operation_elapsed_ms", operationElapsed)
            .put("retirement_elapsed_ms", retirementElapsed)
            .put("pixels", result.getBoolean("pixels"))
            .put("worker_soft_timeout", result.getBoolean("avSoftTimeout"))
            .put("output_read", result.getBoolean("pixels"))
            .put("duration_ms", result.getLong("duration"))
            .put("waveform_bars", result.getFloatArray("waveform")?.size ?: 0)
            .put("input_write_errno", result.getInt("inputWriteErrno"))
}
