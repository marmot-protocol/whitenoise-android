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
    fun defaultFacadeReusesItsWorkerAndAccountResetKeepsTheDefault() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = true)
        val fields =
            listOf("transport", "mediaTransport", "installedContext", "imageOwner", "mediaOwner", "lastIsolation")
                .associateWith { name ->
                    IsolatedMediaDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }
                }
        val previous = fields.mapValues { (_, member) -> member.get(IsolatedMediaDecoder) }
        var experimental: IsolatedMediaTransport? = null
        try {
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            IsolatedMediaDecoder.install(context)
            val owner = field(IsolatedMediaDecoder, "transport") as ReusableMediaTransport
            assertNull(fields.getValue("imageOwner").get(IsolatedMediaDecoder))
            start(owner, 1).join()
            context.next()
            start(owner, 2).join()
            assertEquals(1, context.bindings.size)
            val reused = context.bindings.single().remote
            assertEquals(2, reused.requests)
            assertEquals(1L, reused.retired.count)
            assertNull(IsolatedMediaDecoder.decode(byteArrayOf(3), 1))
            assertEquals(2, reused.requests)
            IsolatedMediaDecoder.resetForAccount()
            val replacement = field(IsolatedMediaDecoder, "transport")
            assertTrue(replacement is ReusableMediaTransport)
            assertFalse(owner === replacement)
            assertNull(fields.getValue("imageOwner").get(IsolatedMediaDecoder))
            IsolatedMediaDecoder.installForLifetimeQualification(context)
            val candidate = field(IsolatedMediaDecoder, "transport") as IsolatedMediaTransport
            experimental = candidate
            val call = start(candidate, 4)
            try {
                val qualification = context.next()
                assertEquals(2, context.instanceNames.size)
                assertFalse(context.instanceNames[0] == context.instanceNames[1])
                assertTrue(context.instanceNames[1].startsWith("lifetime_images_"))
                qualification.remote.die()
                call.join()
            } finally {
                call.interruptAndJoin()
            }
        } finally {
            try {
                IsolatedMediaDecoder.clear()
                context.dieAll()
                experimental?.close()
            } finally {
                fields.forEach { (name, member) -> member.set(IsolatedMediaDecoder, previous[name]) }
            }
        }
    }

    @Test
    fun facadeClearInstallAndAccountResetKeepTheSameDeathWitness() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val fields =
            listOf("transport", "mediaTransport", "installedContext", "imageOwner", "mediaOwner", "lastIsolation")
                .associateWith { name ->
                    IsolatedMediaDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }
                }
        val previous = fields.mapValues { (_, member) -> member.get(IsolatedMediaDecoder) }
        var owned: IsolatedMediaTransport? = null
        var call: Call? = null
        try {
            // Keep prior owners referenced and untouched; this fixture owns only the replacement below.
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            IsolatedMediaDecoder.installForLifetimeQualification(context)
            val owner = field(IsolatedMediaDecoder, "imageOwner") as IsolatedMediaTransport
            owned = owner
            call = start(owner, 1)
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            IsolatedMediaDecoder.clear()
            IsolatedMediaDecoder.installForLifetimeQualification(context)
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
        val reset = Thread { transport.activateSession() }
        var call: Call? = null
        try {
            val session = field(transport, "session")
            val sources = field(session, "sources")
            sources.javaClass
                .getDeclaredField("sourceTimer")
                .apply { isAccessible = true }
                .set(sources, lazyOf(scheduler))
            reset.start()
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
        provider.attachInfo(
            context,
            android.content.pm
                .ProviderInfo()
                .also { it.authority = authority },
        )
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
            assertTrue(
                "Provider entry failed: ${outcome.get()?.exceptionOrNull()?.javaClass?.simpleName}",
                provider.entered.await(2, TimeUnit.SECONDS),
            )
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
            assertEquals(listOf(refused.connection), context.unbound)
            context.acceptBind = true
            next = start(transport, 2)
            val current = context.next()
            assertTrue(current.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(2, context.bindings.size)
            assertEquals(1, current.remote.requests)
            current.remote.die()
            next.join()
            transport.close()
            assertEquals(1, context.unbound.count { it === refused.connection })
            assertEquals(1, context.unbound.count { it === current.connection })
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

    @Test
    fun admissionWaitDoesNotSpendTheExecutionBudget() {
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                beforeBind = {
                    org.robolectric.shadows.ShadowSystemClock
                        .advanceBy(java.time.Duration.ofSeconds(6))
                },
            )
        val transport = IsolatedMediaTransport(context)
        val gate = field(transport, "gate") as java.util.concurrent.Semaphore
        gate.acquire()
        val call = start(transport, 7)
        try {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!gate.hasQueuedThreads() && System.nanoTime() < until) Thread.yield()
            assertTrue("Caller must be waiting for admission", gate.hasQueuedThreads())
            org.robolectric.shadows.ShadowSystemClock
                .advanceBy(java.time.Duration.ofSeconds(6))
            gate.release()
            val selected = context.next()
            assertTrue(selected.remote.decoded.await(2, TimeUnit.SECONDS))
            assertTrue(selected.remote.retired.await(2, TimeUnit.SECONDS))
            selected.remote.die()
            call.join()
        } finally {
            if (gate.availablePermits() == 0 && gate.hasQueuedThreads()) gate.release()
            call.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun deathDeadlineAfterReplyDoesNotQuarantineTheSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context)
        val firstCall = start(transport, 8)
        var next: Call? = null
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            first.remote.beforeRetire.set {
                org.robolectric.shadows.ShadowSystemClock
                    .advanceBy(java.time.Duration.ofSeconds(9))
            }
            val identity =
                org.robolectric.shadows.ShadowBinder.CallingIdentity
                    .newBuilder()
                    .setUid(android.os.Process.myUid() + 1)
                    .setPid(android.os.Process.myPid() + 1)
                    .setUserHandle(android.os.Process.myUserHandle())
                    .build()
            val previous =
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(identity)
            try {
                assertTrue(
                    first.remote.deliverHeldReply(
                        Bundle().apply {
                            putBoolean("success", true)
                            putBoolean("isolated", true)
                            putBoolean("pixels", true)
                            putInt("width", 1)
                            putInt("height", 1)
                            putInt("sourceWidth", 1)
                            putInt("sourceHeight", 1)
                            putInt("orientation", 1)
                            putString("mime", "image/png")
                        },
                    ),
                )
            } finally {
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(previous)
            }
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            firstCall.join()
            assertNull(firstCall.result.get())
            val session = checkNotNull(field(transport, "session"))
            assertTrue((field(session, "refusals") as Map<*, *>).isEmpty())
            first.remote.die()
            next = start(transport, 8)
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            assertEquals(2, context.bindings.size)
        } finally {
            firstCall.interruptAndJoin()
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun unattestedTerminalReplyDoesNotQuarantineTheSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context)
        val firstCall = start(transport, 9)
        var next: Call? = null
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            first.remote.die()
            firstCall.join()
            val session = checkNotNull(field(transport, "session"))
            assertTrue((field(session, "refusals") as Map<*, *>).isEmpty())
            next = start(transport, 9)
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            second.remote.die()
            next.join()
        } finally {
            firstCall.interruptAndJoin()
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    /** Simulated callback identity exercises provenance; this is not an OS-isolation attestation. */
    @Test
    fun attestedWorkerNegativeAfterExactDeathQuarantinesOnlyTheSameSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context)
        val firstCall = start(transport, 10)
        var next: Call? = null
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            val identity =
                org.robolectric.shadows.ShadowBinder.CallingIdentity
                    .newBuilder()
                    .setUid(android.os.Process.myUid() + 1)
                    .setPid(android.os.Process.myPid() + 1)
                    .setUserHandle(android.os.Process.myUserHandle())
                    .build()
            val previous =
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(identity)
            try {
                assertTrue(
                    first.remote.deliverHeldReply(
                        Bundle().apply {
                            putBoolean("success", false)
                            putBoolean("isolated", true)
                        },
                    ),
                )
            } finally {
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(previous)
            }
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            val session = field(transport, "session")
            assertTrue("No cooldown before exact death", (field(session, "refusals") as Map<*, *>).isEmpty())
            first.remote.die()
            firstCall.join()
            assertEquals(1, (field(session, "refusals") as Map<*, *>).size)
            assertNull(transport.decode(byteArrayOf(10), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            next = start(transport, 11)
            val second = context.next()
            assertTrue(second.remote.decoded.await(2, TimeUnit.SECONDS))
            assertEquals(2, context.bindings.size)
        } finally {
            firstCall.interruptAndJoin()
            next?.interruptAndJoin()
            context.dieAll()
            transport.close()
        }
    }

    @Test
    fun automaticMediaOwnerSurvivesResetUntilItsExactWorkerDies() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val fields =
            listOf("transport", "mediaTransport", "installedContext", "imageOwner", "mediaOwner", "lastIsolation")
                .associateWith { IsolatedMediaDecoder::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val previous = fields.mapValues { (_, field) -> field.get(IsolatedMediaDecoder) }
        val file = java.io.File.createTempFile("av-binding-", ".bin")
        var owner: IsolatedMediaTransport? = null
        var caller: Thread? = null
        try {
            file.writeBytes(byteArrayOf(1))
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            IsolatedMediaDecoder.install(context)
            val lane = fields.getValue("mediaOwner").get(IsolatedMediaDecoder) as IsolatedMediaTransport
            owner = lane
            caller =
                Thread {
                    try {
                        lane.probe(file, 1, false)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }.apply { start() }
            val first = context.next()
            assertEquals(MediaAvDecodeService::class.java.name, context.serviceClasses.single())
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            IsolatedMediaDecoder.clear()
            IsolatedMediaDecoder.install(context)
            IsolatedMediaDecoder.resetForAccount()
            assertTrue(lane === fields.getValue("mediaOwner").get(IsolatedMediaDecoder))
            caller.interrupt()
            caller.join(2000)
            assertFalse(caller.isAlive)
            assertNull(lane.probe(file, 1, false))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            val next =
                Thread {
                    try {
                        lane.probe(file, 1, false)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }.apply { start() }
            caller = next
            val second = context.next()
            assertTrue(second.remote.retired.await(2, TimeUnit.SECONDS))
            second.remote.die()
            next.join(2000)
            assertFalse(next.isAlive)
        } finally {
            caller?.interrupt()
            caller?.join(2000)
            context.dieAll()
            IsolatedMediaDecoder.clear()
            owner?.close()
            fields.forEach { (name, field) -> field.set(IsolatedMediaDecoder, previous[name]) }
            file.delete()
        }
    }

    @Test
    fun automaticSourceRefusalsAreBoundedByRevisionAndExpiryAndHealthySourceRecovers() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        var clock = 1L
        val transport = IsolatedMediaTransport(context, "automatic_av", MediaAvDecodeService::class.java) { clock }
        val file = java.io.File.createTempFile("av-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()

        fun request() = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
        try {
            file.writeBytes(byteArrayOf(1))
            val first = request()
            val firstWorker = context.next()
            deliverAutomaticReply(firstWorker, success = false)
            first.join()
            assertNull(first.result.get())
            assertNull(transport.probe(file, 1, false))
            assertEquals(1, context.bindings.size)
            file.writeBytes(byteArrayOf(1, 2))
            val revised = request()
            deliverAutomaticReply(context.next(), success = false)
            revised.join()
            assertNull(transport.probe(file, 1, false))
            assertEquals(2, context.bindings.size)
            clock += MediaDecodePolicy.REFUSAL_TTL_MS
            val recovered = request()
            deliverAutomaticReply(context.next(), success = true)
            recovered.join()
            assertTrue(recovered.result.get() is MediaProbe)
            assertEquals(3, context.bindings.size)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    @Test
    fun automaticByteAndWaveformRefusalsDoNotCreateASecondWorkerForTheSameRevision() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context, "automatic_av", MediaAvDecodeService::class.java)
        val file = java.io.File.createTempFile("waveform-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val bytes = byteArrayOf(7, 8)
            val probe = startAutomatic { transport.probeBytes(bytes, 1, false) }.also { calls += it }
            deliverAutomaticReply(context.next(), success = false)
            probe.join()
            assertNull(transport.probeBytes(bytes, 1, false))
            assertEquals(1, context.bindings.size)
            val waveform = startAutomatic { transport.waveform(file) }.also { calls += it }
            deliverAutomaticReply(context.next(), success = false)
            waveform.join()
            assertNull(transport.waveform(file))
            assertEquals(2, context.bindings.size)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    @Test
    fun missingAttestationAndContentionDoNotQuarantineAndAccountResetClearsConfirmedRefusal() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = IsolatedMediaTransport(context, "automatic_av", MediaAvDecodeService::class.java)
        val file = java.io.File.createTempFile("av-reset-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()

        fun request() = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
        try {
            file.writeBytes(byteArrayOf(1))
            val unknown = request()
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            // Same-UID fake reply is deliberately untrusted; no source failure is established.
            first.remote.deliverHeldReply(Bundle())
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            unknown.interruptAndJoin()
            assertNull(transport.probe(file, 1, false)) // Held worker admission, never a source refusal.
            assertEquals(1, context.bindings.size)
            first.remote.die()
            val confirmed = request()
            deliverAutomaticReply(context.next(), success = false)
            confirmed.join()
            assertNull(transport.probe(file, 1, false))
            assertEquals(2, context.bindings.size)
            transport.invalidateSession()
            transport.activateSession()
            val reset = request()
            deliverAutomaticReply(context.next(), success = true)
            reset.join()
            assertTrue(reset.result.get() is MediaProbe)
            assertEquals(3, context.bindings.size)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    /** Simulated kernel identity only; real process/death qualification remains an instrumentation gate. */
    private fun deliverAutomaticReply(
        worker: Bound,
        success: Boolean,
    ) {
        assertTrue(worker.remote.decoded.await(2, TimeUnit.SECONDS))
        val identity =
            org.robolectric.shadows.ShadowBinder.CallingIdentity
                .newBuilder()
                .setUid(android.os.Process.myUid() + 1)
                .setPid(android.os.Process.myPid() + 1)
                .setUserHandle(android.os.Process.myUserHandle())
                .build()
        val previous =
            org.robolectric.shadows.ShadowBinder
                .setCallingIdentityForCurrentThread(identity)
        try {
            assertTrue(
                worker.remote.deliverHeldReply(
                    Bundle().apply {
                        putBoolean("isolated", true)
                        putBoolean("success", success)
                        putLong("duration", if (success) 1000L else 0L)
                    },
                ),
            )
        } finally {
            org.robolectric.shadows.ShadowBinder
                .setCallingIdentityForCurrentThread(previous)
        }
        assertTrue(worker.remote.retired.await(2, TimeUnit.SECONDS))
        worker.remote.die()
    }

    private fun startAutomatic(block: () -> Any?): AutomaticCall {
        val result = AtomicReference<Any?>()
        val failed = AtomicReference<Throwable?>()
        val thread =
            Thread {
                try {
                    result.set(block())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (failure: Throwable) {
                    failed.set(failure)
                }
            }
        return AutomaticCall(thread, result, failed).also { thread.start() }
    }

    private class AutomaticCall(
        val thread: Thread,
        val result: AtomicReference<Any?>,
        private val failed: AtomicReference<Throwable?>,
    ) {
        fun join() {
            thread.join(2000)
            assertFalse("Automatic media caller must finish", thread.isAlive)
            failed.get()?.let { throw AssertionError("Automatic media fixture failed", it) }
        }

        fun interruptAndJoin() {
            thread.interrupt()
            join()
        }
    }

    private fun start(
        transport: MediaDecodeTransport,
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
        private val beforeBind: () -> Unit = {},
    ) : ContextWrapper(base) {
        var acceptBind = true
        val bindings = mutableListOf<Bound>()
        val instanceNames = mutableListOf<String>()
        val serviceClasses = mutableListOf<String?>()
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
            beforeBind()
            val bound = Bound(conn, ReplyingBinder(reply, failLink))
            synchronized(bindings) {
                bindings += bound
                instanceNames += instanceName
                serviceClasses += service.component?.className
            }
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

        fun die() = synchronized(recipients) { recipients.toList() }.forEach { it.binderDied() }

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
}
