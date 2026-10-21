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
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Controlled callbacks exercise the real owner; these tests do not attest Android process death. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class MediaBindingRecoveryTest {
    @Test
    fun terminalReplyAndDisconnectCannotAdmitAnotherInputBeforeDeath() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context)
        val call = start(transport, 1)
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertTrue(call.thread.isAlive)
            first.connection.onServiceDisconnected(context.component)
            call.interruptAndJoin()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            val next = start(transport, 3)
            val second = context.next()
            assertTrue(second.remote.retired.await(2, TimeUnit.SECONDS))
            // A duplicated old death callback must not release the second generation.
            first.remote.die()
            next.interruptAndJoin()
            assertNull(transport.decode(byteArrayOf(4), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(2, context.bindings.size)
            assertEquals(1, second.remote.requests)
            second.remote.die()
        } finally {
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun sessionInvalidationAndActivationRetainTheUncooperativeGeneration() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context)
        val call = start(transport, 1)
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            transport.invalidateSession()
            transport.activateSession()
            call.interruptAndJoin()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            val next = start(transport, 3)
            val second = context.next()
            assertTrue(second.remote.retired.await(2, TimeUnit.SECONDS))
            second.remote.die()
            next.join()
            assertNull(next.result.get()) // Same-UID fixture cannot attest an isolated worker.
        } finally {
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun acceptedStartupWithoutBinderRemainsHeldAcrossResetAndLateConnection() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), connect = false)
        val transport = IsolatedMediaTransport(context)
        val call = start(transport, 1)
        try {
            val first = context.next()
            transport.invalidateSession()
            transport.activateSession()
            call.interruptAndJoin()
            first.connection.onNullBinding(context.component)
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.connection.onServiceConnected(context.component, first.remote)
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(0, first.remote.requests)
            assertEquals(1, first.remote.linkCount)
            assertNull(transport.decode(byteArrayOf(3), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
        } finally {
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun facadeClearInstallAndAccountResetKeepTheSameDeathWitness() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val fields =
            listOf("transport", "mediaTransport", "installedContext", "imageOwner", "lastIsolation")
                .associateWith { name ->
                    IsolatedMediaDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }
                }
        val previous = fields.mapValues { (_, member) -> member.get(IsolatedMediaDecoder) }
        var owned: IsolatedMediaTransport? = null
        var call: Call? = null
        try {
            // Keep prior owners referenced and untouched; this fixture owns only the replacement below.
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            IsolatedMediaDecoder.install(context)
            val owner = field(IsolatedMediaDecoder, "imageOwner") as IsolatedMediaTransport
            owned = owner
            call = start(owner, 1)
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            IsolatedMediaDecoder.clear()
            IsolatedMediaDecoder.install(context)
            IsolatedMediaDecoder.resetForAccount()
            assertTrue(owner === field(IsolatedMediaDecoder, "imageOwner"))
            call.interruptAndJoin()
            assertNull(owner.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
        } finally {
            try {
                try {
                    call?.interruptAndJoin()
                } finally {
                    try {
                        context.dieAll()
                    } finally {
                        owned?.close()
                    }
                }
            } finally {
                fields.forEach { (name, member) -> member.set(IsolatedMediaDecoder, previous[name]) }
            }
        }
    }

    @Test
    fun oldSourceCloseCannotRetireTheNewlyPublishedSessionWorker() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context)
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scheduler =
            object : java.util.concurrent.ScheduledThreadPoolExecutor(1) {
                override fun execute(command: Runnable) {
                    closing.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    command.run()
                }
            }
        // Hold the real old-source close at its existing executor boundary, without a production hook.
        val session = field(transport, "session")
        val sources = field(session, "sources")
        for (name in listOf("sourceTimer", "sourceWatchdog\$delegate")) {
            sources.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(sources, lazyOf(scheduler))
        }
        val reset = Thread { transport.activateSession() }
        reset.start()
        var call: Call? = null
        try {
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            call = start(transport, 1)
            val successor = context.next()
            assertTrue(successor.remote.decoded.await(2, TimeUnit.SECONDS))
            release.countDown()
            reset.join(2_000)
            assertFalse(reset.isAlive)
            assertEquals(1L, successor.remote.retired.count)
            successor.remote.die()
            call.join()
        } finally {
            release.countDown()
            reset.join(2_000)
            call?.interruptAndJoin()
            context.dieAll()
            transport.close()
            scheduler.shutdownNow()
        }
    }

    private fun field(
        instance: Any,
        name: String,
    ): Any =
        checkNotNull(
            instance.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(instance),
        )

    @Test
    fun heldUriAcquisitionCannotAdoptTheReplacementSession() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val source = java.io.File.createTempFile("old-session-", ".bin", context.cacheDir)
        source.writeBytes(byteArrayOf(1))
        val provider = HeldSourceProvider(source)
        val authority = "dev.ipf.whitenoise.test.held-session-source"
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val transport = IsolatedMediaTransport(context)
        val outcome = AtomicReference<Result<android.graphics.Bitmap?>>()
        val uri = Uri.parse("content://$authority/old")
        val old =
            Thread {
                outcome.set(runCatching { transport.decodeUri(context.contentResolver, uri, 1) })
            }
        var next: Call? = null
        old.start()
        try {
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS))
            transport.activateSession()
            provider.release.countDown()
            old.join(2_000)
            assertFalse("Old URI acquisition must finish", old.isAlive)
            assertNull(checkNotNull(outcome.get()).getOrThrow())
            assertTrue("Old source must never reach a worker", context.bindings.isEmpty())
            next = start(transport, 2)
            val current = context.next()
            assertTrue(current.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(1, current.remote.requests)
            current.remote.die()
            next.join()
        } finally {
            provider.release.countDown()
            old.interrupt()
            old.join(2_000)
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
            ShadowContentResolver.reset()
            source.delete()
        }
    }

    @Test
    fun lateConnectionAfterThrowingBindIsUnboundOnlyAfterItsDeath() {
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                connect = false,
                throwAfterBind = true,
            )
        val transport = IsolatedMediaTransport(context)
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val first = context.next()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.connection.onServiceConnected(context.component, first.remote)
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertTrue(context.unbound.isEmpty())
            assertEquals(0, first.remote.requests)
            first.remote.die()
            assertEquals(listOf(first.connection), context.unbound)
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun replyCapabilityIsRevokedByResetBeforeTheHeldReplyArrives() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context)
        val old = start(transport, 1)
        var next: Call? = null
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            transport.activateSession()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertFalse("Revoked capability must reject the transaction itself", first.remote.deliverHeldReply())
            assertEquals(1, first.remote.requests)
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            old.join()
            next = start(transport, 2)
            val current = context.next()
            assertTrue(current.remote.decoded.await(2, TimeUnit.SECONDS))
            // Same payload and same-UID attestation: a live capability still accepts this frame.
            assertTrue(current.remote.deliverHeldReply())
            assertTrue(current.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(2, context.bindings.size)
            current.remote.die()
            next.join()
        } finally {
            old.interruptAndJoin()
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun definiteBindRefusalWithoutCallbackAllowsTheNextGeneration() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        context.acceptBind = false
        val transport = IsolatedMediaTransport(context)
        var next: Call? = null
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val refused = context.next()
            assertEquals(0, refused.remote.requests)
            assertTrue(context.unbound.isEmpty())
            context.acceptBind = true
            next = start(transport, 2)
            val current = context.next()
            assertTrue(current.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(2, context.bindings.size)
            assertEquals(1, current.remote.requests)
            current.remote.die()
            next.join()
        } finally {
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun liveBinderDeathRegistrationFailureRetiresWithoutSendingAnyInput() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), failLink = true)
        val transport = IsolatedMediaTransport(context)
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val first = context.next()
            assertTrue(first.remote.isBinderAlive)
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(0, first.remote.requests)
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            transport.close()
            assertTrue(context.unbound.isEmpty())
            // This fake context never executes the callback executor and owns no OS binding/thread.
            val callbackThread = field(transport, "callbackThread") as Lazy<*>
            assertFalse(callbackThread.isInitialized())
        } finally {
            // There is deliberately no registered death recipient to invoke as a fake recovery.
            transport.close()
        }
    }

    @Test
    fun terminalCloseCannotReactivateEvenAfterObservedDeath() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context)
        val call = start(transport, 1)
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            transport.close()
            call.interruptAndJoin()
            first.remote.die()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            val rejected = runCatching { transport.activateSession() }.exceptionOrNull()
            assertTrue(rejected is IllegalStateException)
            assertEquals(1, context.bindings.size)
        } finally {
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    private fun start(
        transport: IsolatedMediaTransport,
        value: Int,
    ): Call {
        val result = AtomicReference<android.graphics.Bitmap?>()
        val thread =
            Thread {
                try {
                    result.set(transport.decode(byteArrayOf(value.toByte()), 1, false, MediaDecodePolicy.DECODE))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        return Call(thread, result).also { thread.start() }
    }

    private class Call(
        val thread: Thread,
        val result: AtomicReference<android.graphics.Bitmap?>,
    ) {
        fun join() {
            thread.join(2_000)
            assertFalse("Decoder caller must finish", thread.isAlive)
        }

        fun interruptAndJoin() {
            thread.interrupt()
            join()
        }
    }

    /** Ignores cancellation while acquisition is held, then supplies the original selected bytes. */
    private class HeldSourceProvider(
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

    private class Bound(
        val connection: ServiceConnection,
        val remote: ReplyingBinder,
    )

    private class BindingFixture(
        base: Context,
        private val connect: Boolean = true,
        private val reply: Boolean = true,
        private val throwAfterBind: Boolean = false,
        private val failLink: Boolean = false,
    ) : ContextWrapper(base) {
        var acceptBind = true
        val bindings = mutableListOf<Bound>()
        val unbound = mutableListOf<ServiceConnection>()
        val component = ComponentName(base, MediaDecodeService::class.java)
        private val arrivals = LinkedBlockingQueue<Bound>()

        fun next(): Bound = checkNotNull(arrivals.poll(2, TimeUnit.SECONDS)) { "Binding was not admitted" }

        fun dieAll() = bindings.forEach { it.remote.die() }

        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: Executor,
            conn: ServiceConnection,
        ): Boolean {
            val bound = Bound(conn, ReplyingBinder(reply, failLink))
            synchronized(bindings) { bindings += bound }
            if (acceptBind && connect) conn.onServiceConnected(component, bound.remote)
            arrivals.put(bound)
            if (!acceptBind) return false
            if (throwAfterBind) throw IllegalStateException("Binding outcome unknown")
            return true
        }

        override fun getApplicationContext(): Context = this

        override fun unbindService(conn: ServiceConnection) {
            unbound += conn
        }
    }

    /** Same-UID replies complete IPC but deliberately fail production isolation attestation. */
    private class ReplyingBinder(
        private val reply: Boolean,
        private val failLink: Boolean,
    ) : Binder() {
        private val recipients = mutableListOf<IBinder.DeathRecipient>()

        @Volatile private var heldReply: Pair<IBinder, Int>? = null
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

        fun die() = synchronized(recipients) { recipients.toList() }.forEach { it.binderDied() }

        fun deliverHeldReply(): Boolean {
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
            sendMediaDecodeReply(recording, id, Bundle())
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
                if (message.what == MediaDecodePolicy.RETIRE) retired.countDown()
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
}
