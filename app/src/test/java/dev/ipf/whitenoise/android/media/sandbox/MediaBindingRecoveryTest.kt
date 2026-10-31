package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentResolver
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
import android.os.CancellationSignal
import android.os.HandlerThread
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Controlled callbacks exercise the real owner; these tests do not attest Android process death. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, shadows = [BorrowedDescriptorStatShadow::class])
@Suppress("LargeClass") // Binding and automatic-lane regressions share one exact process/descriptor lifetime fixture.
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
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val fields =
            listOf(
                "transport",
                "mediaTransport",
                "installedContext",
                "imageOwner",
                "mediaOwner",
                "lastIsolation",
                "lastAvIsolation",
            ).associateWith { IsolatedMediaDecoder::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val previous = fields.mapValues { (_, field) -> field.get(IsolatedMediaDecoder) }
        val file = java.io.File.createTempFile("av-binding-", ".bin")
        var owner: IsolatedMediaTransport? = null
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            IsolatedMediaDecoder.install(context)
            val lane = fields.getValue("mediaOwner").get(IsolatedMediaDecoder) as IsolatedMediaTransport
            owner = lane
            lane.javaClass
                .getDeclaredField("processWitnessFactory")
                .apply { isAccessible = true }
                .set(lane, context::processWitness)
            val firstCall = startAutomatic { lane.probe(file, 1, false) }.also { calls += it }
            val first = context.next()
            assertEquals(MediaAvDecodeService::class.java.name, context.serviceClasses.single())
            deliverAutomaticReply(first, success = true, processGone = false)
            assertTrue(firstCall.thread.isAlive)
            IsolatedMediaDecoder.clear()
            IsolatedMediaDecoder.install(context)
            IsolatedMediaDecoder.resetForAccount()
            assertTrue(lane === fields.getValue("mediaOwner").get(IsolatedMediaDecoder))
            firstCall.interruptAndJoin()
            assertNull(firstCall.result.get())
            assertNull(lane.probe(file, 1, false))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            assertTrue(awaitAutomaticRelease(lane))
            val next = startAutomatic { lane.probe(file, 1, false) }.also { calls += it }
            val second = context.next()
            deliverAutomaticReply(second, success = true)
            next.join()
            assertTrue(next.result.get() is MediaProbe)
        } finally {
            calls.forEach { it.interruptAndJoin() }
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
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            ) {
                clock
            }
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
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
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
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-reset-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()

        fun request() = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
        try {
            file.writeBytes(byteArrayOf(1))
            val unknown = request()
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            // Same-UID fake reply is deliberately untrusted; no source failure is established.
            assertFalse(first.remote.deliverHeldReply(Bundle()))
            assertTrue(unknown.thread.isAlive)
            unknown.interruptAndJoin()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertNull(transport.probe(file, 1, false)) // Held worker admission, never a source refusal.
            assertEquals(1, context.bindings.size)
            first.remote.die()
            assertTrue(awaitAutomaticRelease(transport))
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

    @Test
    fun automaticFinalReplyRequiresSynchronousExactKernelPid() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-final-identity-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val call = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val worker = context.next()
            assertTrue(worker.remote.decoded.await(2, TimeUnit.SECONDS))
            val receipt = IsolatedMediaDecoder.lastAvIsolation
            for ((synchronous, pid) in listOf(
                false to worker.remote.identityPid,
                true to worker.remote.identityPid + 1,
            )) {
                val identity =
                    org.robolectric.shadows.ShadowBinder.CallingIdentity
                        .newBuilder()
                        .setUid(worker.remote.identityUid)
                        .setPid(pid)
                        .setUserHandle(android.os.Process.myUserHandle())
                        .build()
                val previous =
                    org.robolectric.shadows.ShadowBinder
                        .setCallingIdentityForCurrentThread(identity)
                try {
                    val outcome = runCatching { worker.remote.deliverHeldReply(Bundle(), synchronous = synchronous) }
                    assertTrue(outcome.isFailure || outcome.getOrNull() == false)
                    org.junit.Assert.assertSame(receipt, IsolatedMediaDecoder.lastAvIsolation)
                    assertTrue(call.thread.isAlive)
                } finally {
                    org.robolectric.shadows.ShadowBinder
                        .setCallingIdentityForCurrentThread(previous)
                }
            }
            deliverAutomaticReply(worker, success = true)
            call.join()
            assertTrue(call.result.get() is MediaProbe)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    @Test
    @Config(shadows = [BorrowedDescriptorStatShadow::class, SelectedAssetCloseResolverShadow::class])
    fun selectedUriCloseMustFinishBeforeAnotherAutomaticSourceCanBind() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-held-uri-close-", ".bin")
        val provider =
            HeldSourceProvider(file).apply {
                holdClose = true
                release.countDown()
            }
        val authority = "held.automatic.close"
        provider.attachInfo(
            context,
            android.content.pm
                .ProviderInfo()
                .apply { this.authority = authority },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first =
                startAutomatic {
                    transport.probeUri(context.contentResolver, Uri.parse("content://$authority/source"), 1)
                }.also { calls += it }
            assertTrue(provider.closeEntered.await(2, TimeUnit.SECONDS))
            val second = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            assertNull("Selected URI cleanup still owns automatic admission", context.poll(250))
            provider.closeRelease.countDown()
            deliverAutomaticReply(context.next(), success = true)
            first.join()
            deliverAutomaticReply(context.next(), success = true)
            second.join()
            assertTrue(first.result.get() is MediaProbe)
            assertTrue(second.result.get() is MediaProbe)
        } finally {
            provider.closeRelease.countDown()
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    @Test
    @Config(shadows = [BorrowedDescriptorStatShadow::class, SelectedAssetCloseResolverShadow::class])
    @Suppress("LongMethod") // Failure, account replacement and exact synthetic capability teardown are one fixture.
    fun selectedUriCloseFailureHoldsAcrossAccountResetWithoutAnotherSourceOpen() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-failed-uri-close-", ".bin")
        val provider =
            HeldSourceProvider(file).apply {
                failClose = true
                release.countDown()
            }
        val authority = "failed.automatic.close"
        provider.attachInfo(
            context,
            android.content.pm
                .ProviderInfo()
                .apply { this.authority = authority },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first =
                startAutomatic {
                    transport.probeUri(context.contentResolver, Uri.parse("content://$authority/source"), 1)
                }.also { calls += it }
            val failure = first.joinFailure()
            assertTrue(failure.stackTraceToString(), failure is java.io.IOException)
            transport.activateSession()
            val held =
                startAutomatic {
                    transport.probeUri(context.contentResolver, Uri.parse("content://$authority/source"), 1)
                }.also { calls += it }
            held.join()
            assertNull(held.result.get())
            assertEquals(1, provider.openCount)
            assertEquals(0, context.bindings.size)
        } finally {
            provider.failClose = false
            // Teardown closes only synthetic capabilities; the production ledger remains failed, never reset.
            val field =
                IsolatedMediaTransport::class.java.getDeclaredField("parentResources").apply {
                    isAccessible =
                        true
                }
            val resources = field.get(transport) as MediaParentResources
            val held = MediaParentResources::class.java.getDeclaredField("held").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val owners = synchronized(resources) { (held.get(resources) as Set<MediaParentResource<*>>).toList() }
            owners.forEach { runCatching { it.value.close() } }
            calls.forEach {
                it.thread.interrupt()
                it.thread.join(2000)
            }
            transport.close()
            file.delete()
        }
    }

    @Test
    fun lateAccountReadyCannotHandOffInputAndNewUidWaitsForOldProcessAbsence() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), reply = false).apply {
                holdIdentity =
                    true
            }
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-late-ready-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val old = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val worker = context.next()
            assertTrue(worker.remote.identityRequested.await(2, TimeUnit.SECONDS))
            transport.activateSession()
            old.join() // Revoking the READY wait must not spend the old caller's full deadline.
            assertNull(old.result.get())
            assertFalse(worker.remote.deliverIdentity()) // Late identity is usable only for exact old-process cleanup.
            assertEquals(0, worker.remote.requests)
            val held = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            held.join()
            assertNull(held.result.get())
            assertEquals(1, context.bindings.size)
            worker.remote.die()
            assertTrue(awaitAutomaticRelease(transport))
            val next = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val replacement = context.next()
            assertTrue(replacement.remote.identityRequested.await(2, TimeUnit.SECONDS))
            replacement.remote.identityUid += 1
            replacement.remote.identityPid += 1
            assertTrue(replacement.remote.deliverIdentity())
            deliverAutomaticReply(replacement, success = true)
            next.join()
            assertTrue(next.result.get() is MediaProbe)
            assertEquals(replacement.remote.identityUid, IsolatedMediaDecoder.lastAvIsolation?.uid)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    @Test
    fun automaticFalseBindNeedsOneSuccessfulUnbindBeforeReplacement() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.acceptBind = false
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-false-bind-", ".bin")
        try {
            file.writeBytes(byteArrayOf(1))
            repeat(2) {
                val call = startAutomatic { transport.probe(file, 1, false) }
                call.join()
                assertNull(call.result.get())
                assertTrue(awaitAutomaticRelease(transport))
            }
            assertEquals(2, context.bindings.size)
            assertEquals(2, context.unbound.size)
            assertTrue(context.bindings.all { it.remote.requests == 0 })
        } finally {
            transport.close()
            file.delete()
        }
    }

    @Test
    fun automaticUnbindFailureHoldsEvenAfterConfirmedProcessAbsence() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.failUnbind = true
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-unbind-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            deliverAutomaticReply(context.next(), success = true)
            assertTrue(first.thread.isAlive)
            first.interruptAndJoin()
            assertNull(first.result.get())
            val held = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            held.join()
            assertNull(held.result.get())
            assertEquals(1, context.bindings.size)
            assertEquals(1, context.unbound.size)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            transport.close()
            stopAutomaticObserver(transport)
            file.delete()
        }
    }

    @Test
    fun missingReadyRemainsHeldAcrossBinderDeathAndResetUntilLateExactCalibrationAndProcessAbsence() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.holdIdentity = true
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-no-ready-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val worker = context.next()
            assertTrue(worker.remote.identityRequested.await(2, TimeUnit.SECONDS))
            assertEquals(0, worker.remote.requests)
            worker.remote.dieBinderOnly()
            first.join()
            assertNull(first.result.get())
            assertEquals("An already-dead endpoint receives no RETIRE RPC", 1L, worker.remote.retired.count)
            assertAutomaticCleanupAcknowledged(transport)
            assertEquals(1, context.unbound.size)
            assertFalse(transport.isRequestDispatchedForTest())
            assertAutomaticRequestHeld(transport, file, calls)
            transport.activateSession()
            context.kernelErrnoOverride = android.system.OsConstants.ESRCH
            assertAutomaticRequestHeld(transport, file, calls)
            assertEquals(1, context.bindings.size)
            assertEquals(0, worker.remote.requests)
            // With no calibrated identity, even an injected ESRCH cannot prove which worker disappeared.
            context.kernelErrnoOverride = null
            assertTrue(worker.remote.processAlive)
            assertFalse(worker.remote.deliverIdentity())
            assertEquals(0, worker.remote.requests)
            assertFalse(transport.isRequestDispatchedForTest())
            assertAutomaticRequestHeld(transport, file, calls)
            assertEquals(1, context.bindings.size)
            worker.remote.processAlive = false // The already-dead Binder sends no second death notification.
            assertTrue(awaitAutomaticRelease(transport))
            val next = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val replacement = context.next()
            assertTrue(replacement.remote.identityRequested.await(2, TimeUnit.SECONDS))
            replacement.remote.identityUid += 1
            replacement.remote.identityPid += 1
            assertTrue(replacement.remote.deliverIdentity())
            deliverAutomaticReply(replacement, success = true)
            next.join()
            assertTrue(next.result.get() is MediaProbe)
            assertEquals(2, context.bindings.size)
            assertEquals(1, replacement.remote.requests)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            stopAutomaticObserver(transport)
            file.delete()
        }
    }

    private fun assertAutomaticRequestHeld(
        transport: IsolatedMediaTransport,
        file: java.io.File,
        calls: MutableList<AutomaticCall>,
    ) {
        val held = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
        held.join()
        assertNull(held.result.get())
    }

    private fun assertAutomaticCleanupAcknowledged(transport: IsolatedMediaTransport) {
        val type = IsolatedMediaTransport::class.java
        val lock = type.getDeclaredField("bindingLock").apply { isAccessible = true }.get(transport)
        val worker = type.getDeclaredField("worker").apply { isAccessible = true }
        val resources = type.getDeclaredField("parentResources").apply { isAccessible = true }.get(transport)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var acknowledged = false
        while (!acknowledged && System.nanoTime() < deadline) {
            acknowledged =
                synchronized(lock) {
                    val selected = checkNotNull(worker.get(transport))

                    fun flag(name: String) =
                        selected.javaClass
                            .getDeclaredField(name)
                            .apply { isAccessible = true }
                            .getBoolean(selected)
                    flag("unbound") &&
                        !flag("operationActive") &&
                        !flag("retireRpcActive") &&
                        (resources as MediaParentResources).available()
                }
            if (!acknowledged) Thread.sleep(10)
        }
        assertTrue("All cleanup ACKs must precede the unknown-process admission assertions", acknowledged)
    }

    @Test
    fun failedLiveCalibrationCannotHandOffInputOrReleaseAnUnknownProcess() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.kernelErrnoOverride = android.system.OsConstants.ESRCH
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-calibration-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val worker = context.next()
            first.join()
            assertNull(first.result.get())
            assertEquals(0, worker.remote.requests)
            assertFalse(transport.isRequestDispatchedForTest())
            worker.remote.die()
            val held = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            held.join()
            assertNull(held.result.get())
            assertEquals(1, context.bindings.size)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            transport.close()
            // The synthetic always-ESRCH vendor fault deliberately leaves production ownership held.
            // End only this fixture's private observer thread; do not manufacture admission release.
            stopAutomaticObserver(transport)
            file.delete()
        }
    }

    @Test
    fun automaticBinderDeathCannotCopyOrReplaceWhileKernelProcessIsAlive() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedMediaTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-process-witness-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val worker = context.next()
            deliverAutomaticReply(worker, success = true, processGone = false)
            // A successful primitive reply and Binder death still cannot publish output.
            assertTrue(first.thread.isAlive)
            first.interruptAndJoin()
            assertNull(first.result.get())
            val held = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            held.join()
            assertNull(held.result.get())
            assertEquals(1, context.bindings.size)
            assertTrue(worker.remote.processAlive)
            worker.remote.die() // Only injected ESRCH can retire this exact generation.
            assertTrue(awaitAutomaticRelease(transport))
            val next = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            deliverAutomaticReply(context.next(), success = true)
            next.join()
            assertTrue(next.result.get() is MediaProbe)
            assertEquals(2, context.bindings.size)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            file.delete()
        }
    }

    private fun awaitAutomaticRelease(transport: IsolatedMediaTransport): Boolean {
        val owner = IsolatedMediaTransport::class.java.getDeclaredField("bindingLock").apply { isAccessible = true }
        val worker = IsolatedMediaTransport::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val field = IsolatedMediaTransport::class.java.getDeclaredField("callbackThread").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val observer = field.get(transport) as Lazy<HandlerThread>
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (synchronized(owner.get(transport)) { worker.get(transport) == null }) return true
            if (observer.isInitialized()) {
                shadowOf(observer.value.looper).idleFor(Duration.ofMillis(AutomaticAvIdentity.HELD_POLL_MS))
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun stopAutomaticObserver(transport: IsolatedMediaTransport) {
        val field = IsolatedMediaTransport::class.java.getDeclaredField("callbackThread").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val observer = field.get(transport) as Lazy<android.os.HandlerThread>
        if (observer.isInitialized()) observer.value.quitSafely()
        val retirementField =
            IsolatedMediaTransport::class.java.getDeclaredField("retirementThread").apply {
                isAccessible =
                    true
            }

        @Suppress("UNCHECKED_CAST")
        val retirement = retirementField.get(transport) as Lazy<java.util.concurrent.ExecutorService>
        if (retirement.isInitialized()) retirement.value.shutdown()
    }

    /** Simulated kernel identity only; real process/death qualification remains an instrumentation gate. */
    private fun deliverAutomaticReply(
        worker: Bound,
        success: Boolean,
        processGone: Boolean = true,
    ) {
        assertTrue(worker.remote.decoded.await(2, TimeUnit.SECONDS))
        val identity =
            org.robolectric.shadows.ShadowBinder.CallingIdentity
                .newBuilder()
                .setUid(worker.remote.identityUid)
                .setPid(worker.remote.identityPid)
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
                    synchronous = true,
                ),
            )
        } finally {
            org.robolectric.shadows.ShadowBinder
                .setCallingIdentityForCurrentThread(previous)
        }
        assertTrue(worker.remote.retired.await(2, TimeUnit.SECONDS))
        if (processGone) worker.remote.die() else worker.remote.dieBinderOnly()
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

        fun joinFailure(): Throwable {
            thread.join(2000)
            assertFalse("Automatic failure caller must finish", thread.isAlive)
            return checkNotNull(failed.get())
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

    /**
     * Injects a close-controlled parent resource for two synthetic authorities. Framework ContentResolver
     * rebuilds provider AFDs, so a provider subclass close override is not a client close hook.
     */
    @Implements(ContentResolver::class)
    class SelectedAssetCloseResolverShadow : ShadowContentResolver() {
        @Implementation
        protected fun openAssetFileDescriptor(
            uri: Uri,
            mode: String,
            cancellationSignal: CancellationSignal?,
        ): AssetFileDescriptor? {
            require(uri.scheme == "content" && uri.authority in setOf("held.automatic.close", "failed.automatic.close"))
            cancellationSignal?.throwIfCanceled()
            val provider = checkNotNull(ShadowContentResolver.getProvider(uri))
            return provider.openAssetFile(uri, mode, cancellationSignal)
        }
    }

    /** Ignores cancellation while acquisition is held, then supplies the original selected bytes. */
    private class HeldSourceProvider(
        private val file: java.io.File,
    ) : ContentProvider() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        var holdClose = false
        var failClose = false
        var openCount = 0

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "image/png"

        override fun openAssetFile(
            uri: Uri,
            mode: String,
        ): AssetFileDescriptor {
            openCount++
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return object : AssetFileDescriptor(input, 0, file.length()) {
                override fun close() {
                    if (holdClose) {
                        closeEntered.countDown()
                        check(closeRelease.await(5, TimeUnit.SECONDS))
                    }
                    if (failClose) throw java.io.IOException("Synthetic provider close failure")
                    super.close()
                }
            }
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
        var holdIdentity = false
        var kernelErrnoOverride: Int? = null
        var failUnbind = false
        var acceptBind = true
        val bindings = mutableListOf<Bound>()
        val instanceNames = mutableListOf<String>()
        val serviceClasses = mutableListOf<String?>()
        val unbound = mutableListOf<ServiceConnection>()
        val component = ComponentName(base, MediaDecodeService::class.java)
        private val arrivals = LinkedBlockingQueue<Bound>()

        fun processWitness(): KernelProcessWitness =
            KernelProcessWitness { _, signal ->
                check(signal == 0)
                val alive = synchronized(bindings) { bindings.lastOrNull()?.remote?.processAlive }
                val errno =
                    kernelErrnoOverride ?: when (alive) {
                        true -> android.system.OsConstants.EPERM
                        false -> android.system.OsConstants.ESRCH
                        null -> android.system.OsConstants.ENOSYS
                    }
                throw android.system.ErrnoException("kill", errno)
            }

        fun poll(timeoutMs: Long): Bound? = arrivals.poll(timeoutMs, TimeUnit.MILLISECONDS)

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
            val bound = Bound(conn, ReplyingBinder(reply, failLink, holdIdentity))
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
            if (failUnbind) throw IllegalStateException("Synthetic unbind failure")
        }
    }

    /** Same-UID replies complete IPC but deliberately fail production isolation attestation. */
    private class ReplyingBinder(
        private val reply: Boolean,
        private val failLink: Boolean,
        private val holdIdentity: Boolean = false,
    ) : Binder() {
        private val recipients = mutableListOf<IBinder.DeathRecipient>()

        @Volatile private var heldReply: Pair<IBinder, Int>? = null

        @Volatile private var heldIdentity: Pair<IBinder, Int>? = null
        var identityUid = android.os.Process.myUid() + 1
        var identityPid = android.os.Process.myPid() + 1
        val identityRequested = CountDownLatch(1)
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

        @Volatile var processAlive = true

        fun dieBinderOnly() = synchronized(recipients) { recipients.toList() }.forEach { it.binderDied() }

        fun die() {
            processAlive = false
            dieBinderOnly()
        }

        fun deliverIdentity(): Boolean {
            val (callback, request) = checkNotNull(heldIdentity)
            val identity =
                org.robolectric.shadows.ShadowBinder.CallingIdentity
                    .newBuilder()
                    .setUid(identityUid)
                    .setPid(identityPid)
                    .setUserHandle(android.os.Process.myUserHandle())
                    .build()
            val previous =
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(identity)
            return try {
                sendAutomaticAvIdentity(callback, request)
            } finally {
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(previous)
            }
        }

        fun deliverHeldReply(
            result: Bundle = Bundle(),
            synchronous: Boolean = false,
        ): Boolean {
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
            sendMediaDecodeReply(recording, id, result, synchronous = synchronous)
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
                if (message.what == AutomaticAvIdentity.MESSAGE) {
                    heldIdentity = message.replyTo.binder to message.arg1
                    identityRequested.countDown()
                    if (!holdIdentity) deliverIdentity()
                }
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
