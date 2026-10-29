package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertFalse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Starts one decode of a one-byte synthetic source on its own thread. */
internal fun startDecode(
    transport: MediaDecodeTransport,
    value: Int,
): DecodeCall {
    val result = AtomicReference<android.graphics.Bitmap?>()
    val thread =
        Thread {
            try {
                result.set(transport.decode(byteArrayOf(value.toByte()), 1, false, MediaDecodePolicy.DECODE))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    return DecodeCall(thread, result).also { thread.start() }
}

/** Reads one private field by reflection, so fixtures observe owners without production hooks. */
internal fun privateField(
    instance: Any,
    name: String,
): Any =
    checkNotNull(
        instance.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(instance),
    )

/** A decode running on its own thread, with its result. */
internal class DecodeCall(
    val thread: Thread,
    val result: AtomicReference<android.graphics.Bitmap?>,
) {
    /** Waits for the caller to finish and fails if it is still blocked. */
    fun join() {
        thread.join(2_000)
        assertFalse("Decoder caller must finish", thread.isAlive)
    }

    /** Interrupts the caller, then waits for it to finish. */
    fun interruptAndJoin() {
        thread.interrupt()
        join()
    }
}

/** One recorded binding: its connection and the controllable fake worker endpoint. */
internal class Bound(
    val connection: ServiceConnection,
    val remote: ReplyingBinder,
)

/** A context whose isolated bindings are fake endpoints the test drives explicitly. */
internal class BindingFixture(
    base: Context,
    @Volatile var connect: Boolean = true,
    private val reply: Boolean = true,
    private val throwAfterBind: Boolean = false,
    private val failLink: Boolean = false,
    private val beforeBind: () -> Unit = {},
    private val acceptAt: (Int) -> Boolean = { true },
    private val connectAt: (Int) -> Boolean = { true },
) : ContextWrapper(base) {
    @Volatile var acceptBind = true
    val bindings = mutableListOf<Bound>()
    val instanceNames = mutableListOf<String>()
    val unbound = mutableListOf<ServiceConnection>()
    val component = ComponentName(base, MediaDecodeService::class.java)
    private val arrivals = LinkedBlockingQueue<Bound>()

    /** The next binding the transport attempted, in order. */
    fun next(): Bound = checkNotNull(arrivals.poll(2, TimeUnit.SECONDS)) { "Binding was not admitted" }

    /** Delivers a Binder death to every recorded endpoint. */
    fun dieAll() = synchronized(bindings) { bindings.toList() }.forEach { it.remote.die() }

    /** True once the transport released this binding. */
    fun isUnbound(bound: Bound): Boolean = synchronized(unbound) { unbound.any { it === bound.connection } }

    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection,
    ): Boolean {
        beforeBind()
        val bound = Bound(conn, ReplyingBinder(reply, failLink))
        val index =
            synchronized(bindings) {
                bindings += bound
                instanceNames += instanceName
                bindings.size - 1
            }
        val accepted = acceptBind && acceptAt(index)
        if (accepted && connect && connectAt(index)) conn.onServiceConnected(component, bound.remote)
        arrivals.put(bound)
        if (!accepted) return false
        if (throwAfterBind) throw IllegalStateException("Binding outcome unknown")
        return true
    }

    override fun getApplicationContext(): Context = this

    override fun unbindService(conn: ServiceConnection) {
        synchronized(unbound) { unbound += conn }
    }
}

/** Same-UID replies complete IPC but deliberately fail production isolation attestation. */
internal class ReplyingBinder(
    private val reply: Boolean,
    private val failLink: Boolean,
) : Binder() {
    private val recipients = mutableListOf<IBinder.DeathRecipient>()

    @Volatile private var heldReply: Pair<IBinder, Int>? = null
    val beforeRetire = AtomicReference<(() -> Unit)?>(null)
    val retired = CountDownLatch(1)
    val decoded = CountDownLatch(1)

    @Volatile var requests = 0

    @Volatile var linkCount = 0

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        if (failLink) throw android.os.RemoteException("Death registration unavailable")
        synchronized(recipients) { recipients += recipient }
        linkCount += 1
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean = true

    /** Delivers this endpoint's Binder death to every linked recipient, as the kernel would. */
    fun die() = synchronized(recipients) { recipients.toList() }.forEach { it.binderDied() }

    /** Sends a reply on the last request's capability and returns whether that capability accepted it. */
    fun deliverHeldReply(result: Bundle = Bundle()): Boolean {
        val (callback, id) = checkNotNull(heldReply)
        var accepted: Boolean? = null
        val recording =
            object : IBinder by callback {
                override fun transact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean = callback.transact(code, data, reply, flags).also { accepted = it }
            }
        sendMediaDecodeReply(recording, id, result)
        return checkNotNull(accepted)
    }

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
        data.enforceInterface("android.os.IMessenger")
        if (data.readInt() != 0) {
            val message = Message.CREATOR.createFromParcel(data)
            if (message.what == MediaDecodePolicy.RETIRE) {
                beforeRetire.getAndSet(null)?.invoke()
                retired.countDown()
            }
            if (message.what == MediaDecodePolicy.DECODE) {
                requests += 1
                heldReply = message.replyTo.binder to message.arg1
                decoded.countDown()
                if (this.reply) sendMediaDecodeReply(message.replyTo.binder, message.arg1, Bundle())
            }
        }
        return true
    }
}

/** Ignores cancellation while acquisition is held, then supplies the original selected bytes. */
internal class HeldSourceProvider(
    private val file: java.io.File,
) : ContentProvider() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor {
        entered.countDown()
        check(release.await(2, TimeUnit.SECONDS))
        val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(input, 0, file.length())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
