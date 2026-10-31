package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Controlled state-machine assertions, never kernel identity/death or platform qualification. */
internal fun checkAvBindingWiring(context: Context) {
    checkAvDiagnosticAdmission()
    checkAvBoundaryReport()
    val fixture = File.createTempFile("av-wiring-", ".bin", context.cacheDir)
    try {
        fixture.writeBytes(byteArrayOf(1))
        for (mode in AvWiringMode.entries) checkAvBindingMode(context, fixture, mode)
    } finally {
        check(fixture.delete())
    }
}

private fun checkAvDiagnosticAdmission() {
    val token = "a".repeat(32)
    val begin = "$token|video_construct|1|4242|99001|retriever_construct|begin|none|0|1"
    val terminal = "$token|video_construct|1|4242|99001|retriever_construct|success|none|0|2"

    fun admit(
        text: String,
        deadline: Boolean,
    ) = AvCapabilityLogs.admit(text, AvProbeTuple(token, "video_construct", 1), 99001, 4242, deadline)
    check(admit(begin, true).length() == 1)
    check(admit("$begin\n$terminal", true).length() == 2)
    check(admit("$begin\n$terminal", false).length() == 2)
    check(runCatching { admit(begin, false) }.isFailure)
    check(admit(terminal, true).length() == 1)
    check(runCatching { admit("$begin\n$terminal\n$terminal", true) }.isFailure)
    check(runCatching { admit(List(33) { begin }.joinToString("\n"), true) }.isFailure)
    check(runCatching { admit(begin.replace("|99001|", "|99002|"), true) }.isFailure)
    check(runCatching { admit(begin.replace("|4242|", "|4243|"), true) }.isFailure)
    check(runCatching { admit(begin.replace("retriever_construct", "untrusted"), true) }.isFailure)
    check(runCatching { admit(begin.replace("video_construct", "another_case"), true) }.isFailure)
    var visited = 0
    val campaign =
        runCatching {
            runAvCapabilityCases(2) { index ->
                visited++
                admit(if (index == 0) begin else "$begin\n$terminal", index == 0)
                index == 0
            }
        }
    check(visited == 2) { "A typed deadline must not skip the next case" }
    check(campaign.exceptionOrNull()?.message == "A/V deadline observations remain unqualified")
}

private fun checkAvBindingMode(
    context: Context,
    fixture: File,
    mode: AvWiringMode,
) {
    val clock = AtomicLong(0)
    val endpoint = AvWiringEndpoint(mode, clock)
    val owner = AvWiringContext(context, endpoint)
    val binding = MediaAvCapabilityBinding(owner, { endpoint.identity }, clock::get)
    val request =
        Bundle().apply {
            putString("runToken", "a".repeat(32))
            putString("caseId", "video_construct")
            putString("probeStage", "retriever_construct")
            putInt("requestId", 1)
        }
    try {
        if (mode == AvWiringMode.SOFT_TIMEOUT) {
            val result = binding.run(fixture, request)
            check(result is AvCapabilityCase.Terminal)
            check(result.observation.getBoolean("worker_soft_timeout"))
            check(!result.observation.getBoolean("success") && !result.observation.getBoolean("output_read"))
            check(result.observation.getBoolean("death_observed") && owner.unbinds == 1)
        } else if (mode == AvWiringMode.SOFT_EXPIRED_CALLBACK) {
            val result = binding.run(fixture, request)
            check(result is AvCapabilityCase.ReplyDeadline)
            check(!result.observation.getBoolean("output_read"))
            check(!result.observation.getBoolean("terminal_reply_seen"))
            check(result.observation.getBoolean("death_observed"))
            check(owner.unbinds == 1)
        } else if (mode == AvWiringMode.DEADLINE_DEATH) {
            val result = binding.run(fixture, request)
            check(result is AvCapabilityCase.ReplyDeadline)
            check(!result.observation.getBoolean("output_read"))
            check(owner.unbinds == 1)
        } else {
            val result = runCatching { binding.run(fixture, request) }
            check(result.isFailure) { "Controlled protocol/cleanup failure was continued" }
            if (mode == AvWiringMode.DEADLINE_HELD) {
                check(owner.unbinds == 0)
                check(runCatching { binding.run(fixture, request) }.isFailure)
                check(owner.binds == 1)
            } else {
                val expected =
                    if (mode == AvWiringMode.SOFT_LATE) {
                        "Late reply remains unqualified"
                    } else {
                        "Current terminal protocol or identity rejected"
                    }
                check(result.exceptionOrNull()?.message == expected) {
                    "Mode=$mode expected=$expected actual=${result.exceptionOrNull()}"
                }
                check(owner.unbinds == 1)
            }
        }
    } finally {
        binding.close()
        endpoint.join()
    }
}

private enum class AvWiringMode {
    SOFT_TIMEOUT,
    SOFT_WRONG_PID,
    SOFT_WRONG_TUPLE,
    SOFT_DUPLICATE,
    SOFT_LATE,
    SOFT_EXPIRED_CALLBACK,
    BAD_READY,
    DUPLICATE_READY,
    BAD_TERMINAL,
    FD_TERMINAL,
    WRONG_UID,
    DEADLINE_DEATH,
    DEADLINE_HELD,
}

private class AvWiringContext(
    context: Context,
    private val endpoint: AvWiringEndpoint,
) : ContextWrapper(context) {
    var binds = 0
    var unbinds = 0
    private var connection: ServiceConnection? = null

    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection,
    ): Boolean {
        binds++
        check(connection == null)
        connection = conn
        conn.onServiceConnected(ComponentName(packageName, "ControlledAvFixture"), endpoint)
        return true
    }

    override fun unbindService(conn: ServiceConnection) {
        check(conn === connection && unbinds == 0)
        unbinds++
    }
}

private class AvWiringEndpoint(
    private val mode: AvWiringMode,
    private val clock: AtomicLong,
) : Binder() {
    var identity = AvProbeIdentity(99_001, 4_242)
    private var recipient: IBinder.DeathRecipient? = null
    private var deathThread: Thread? = null
    private var retired = false

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        check(this.recipient == null)
        this.recipient = recipient
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean {
        check(this.recipient === recipient)
        this.recipient = null
        return true
    }

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        data.enforceInterface("android.os.IMessenger")
        check(data.readInt() == 1)
        val message = Message.CREATOR.createFromParcel(data)
        try {
            if (message.what == MediaDecodePolicy.DECODE) receive(message) else retire()
        } finally {
            message.recycle()
        }
        return true
    }

    @Suppress("DEPRECATION") // API 30-compatible selected-handle access matches the debug worker.
    private fun receive(message: Message) {
        val request = message.data
        try {
            val callback = checkNotNull(message.replyTo).binder
            val tuple = AvProbeTuple(checkNotNull(request.getString("runToken")), "video_construct", 1)
            if (mode == AvWiringMode.BAD_READY) {
                malformed(callback, AV_READY_TRANSACTION)
            } else {
                sendAvProbeReady(callback, tuple)
                when (mode) {
                    AvWiringMode.SOFT_TIMEOUT, AvWiringMode.SOFT_LATE -> {
                        sendAvProbeSoftTimeout(callback, timeoutFrame(tuple))
                        if (mode == AvWiringMode.SOFT_LATE) clock.set(MediaDecodePolicy.DEADLINE_MS + 1)
                    }
                    AvWiringMode.SOFT_EXPIRED_CALLBACK -> {
                        clock.set(MediaDecodePolicy.DEADLINE_MS + 1)
                        check(runCatching { sendAvProbeSoftTimeout(callback, timeoutFrame(tuple)) }.isFailure)
                    }
                    AvWiringMode.SOFT_WRONG_PID -> {
                        identity = identity.copy(pid = 4_243)
                        check(runCatching { sendAvProbeSoftTimeout(callback, timeoutFrame(tuple)) }.isFailure)
                    }
                    AvWiringMode.SOFT_WRONG_TUPLE -> {
                        val foreign = timeoutFrame(tuple.copy(request = 2))
                        check(runCatching { sendAvProbeSoftTimeout(callback, foreign) }.isFailure)
                    }
                    AvWiringMode.SOFT_DUPLICATE -> {
                        sendAvProbeSoftTimeout(callback, timeoutFrame(tuple))
                        check(runCatching { sendAvProbeSoftTimeout(callback, timeoutFrame(tuple)) }.isFailure)
                    }
                    AvWiringMode.DUPLICATE_READY -> check(runCatching { sendAvProbeReady(callback, tuple) }.isFailure)
                    AvWiringMode.BAD_TERMINAL -> malformed(callback, IBinder.FIRST_CALL_TRANSACTION)
                    AvWiringMode.FD_TERMINAL -> descriptorTerminal(callback)
                    AvWiringMode.WRONG_UID -> wrongUid(callback)
                    else -> clock.set(MediaDecodePolicy.DEADLINE_MS + 1)
                }
            }
        } finally {
            request.getParcelable<ParcelFileDescriptor>("input")?.close()
            request.getParcelable<SharedMemory>("output")?.close()
        }
    }

    private fun timeoutFrame(tuple: AvProbeTuple): AvProbeSoftTimeout =
        AvProbeSoftTimeout(
            tuple,
            true,
            false,
            false,
            android.system.OsConstants.EACCES,
            android.system.OsConstants.EBADF,
        )

    private fun wrongUid(callback: IBinder) {
        identity = identity.copy(uid = 99_002)
        sendMediaDecodeReply(callback, 1, Bundle())
    }

    private fun descriptorTerminal(callback: IBinder) {
        val pipe = ParcelFileDescriptor.createPipe()
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MEDIA_REPLY_DESCRIPTOR)
            data.writeInt(1)
            data.writeFileDescriptor(pipe[0].fileDescriptor)
            check(!callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY))
        } finally {
            data.recycle()
            pipe.forEach { it.close() }
        }
    }

    private fun malformed(
        callback: IBinder,
        code: Int,
    ) {
        val data = Parcel.obtain()
        val response = if (code == AV_READY_TRANSACTION) Parcel.obtain() else null
        try {
            data.writeInterfaceToken("invalid.controlled.fixture")
            check(!callback.transact(code, data, response, 0))
        } finally {
            response?.recycle()
            data.recycle()
        }
    }

    private fun retire() {
        if (retired || mode == AvWiringMode.DEADLINE_HELD) return
        retired = true
        val exact = checkNotNull(recipient)
        if (mode == AvWiringMode.DEADLINE_DEATH) {
            deathThread =
                Thread {
                    Thread.sleep(25)
                    exact.binderDied()
                }.also { it.start() }
        } else {
            exact.binderDied()
        }
    }

    fun join() {
        deathThread?.let {
            it.join(2_000)
            check(!it.isAlive)
        }
    }
}

/** Reports retain boundary facts so offline consumers can reject incomplete or contradictory captures. */
private fun checkAvBoundaryReport() {
    val result =
        Bundle().apply {
            putInt("uid", 99_001)
            putBoolean("isolated", true)
            putBoolean("internetGranted", false)
            putBoolean("runtimeInitialized", false)
            putBoolean("privateCanaryDenied", true)
            putInt("privateCanaryErrno", android.system.OsConstants.EACCES)
            putInt("inputWriteErrno", android.system.OsConstants.EBADF)
        }
    val row = AvCapabilityEvidence.row(1, result, 7, 5, 2)
    check(row.getInt("kernel_reply_uid") == 99_001)
    check(row.getBoolean("isolated") && row.getBoolean("private_canary_denied"))
    check(!row.getBoolean("internet_granted") && !row.getBoolean("runtime_initialized"))
    check(row.getInt("private_canary_errno") == android.system.OsConstants.EACCES)
    check(row.getInt("input_write_errno") == android.system.OsConstants.EBADF)
    check(!row.getBoolean("success") && !row.getBoolean("output_read"))
}
