package dev.ipf.whitenoise.android.media.sandbox

import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Controlled callbacks exercise the real owner, these tests do not attest Android process death.
 * Owners that must report a held lane quickly use a short deadline, spares are off unless a test
 * is about pre-warming, so binding counts describe exactly one generation per input.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class MediaBindingRecoveryTest {
    /** A retired generation that only disconnects keeps the next input out until its own Binder death. */
    @Test
    fun terminalReplyAndDisconnectCannotAdmitAnotherInputBeforeDeath() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        val call = startDecode(transport, 1)
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertTrue(call.thread.isAlive)
            first.connection.onServiceDisconnected(context.component)
            call.interruptAndJoin()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            val next = startDecode(transport, 3)
            val second = context.next()
            assertTrue(second.remote.retired.await(2, TimeUnit.SECONDS))
            // A duplicated old death must not release the second generation.
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

    /** Session reset revokes the old reply but keeps the uncooperative generation's admission hold. */
    @Test
    fun sessionInvalidationAndActivationRetainTheUncooperativeGeneration() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        val call = startDecode(transport, 1)
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            transport.invalidateSession()
            transport.activateSession()
            call.interruptAndJoin()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            val next = startDecode(transport, 3)
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

    /** An accepted bind that reports nothing holds its slot across reset, and a late connection is then retired. */
    @Test
    fun acceptedStartupWithoutBinderRemainsHeldAcrossResetAndLateConnection() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), connect = false)
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        val call = startDecode(transport, 1)
        try {
            val first = context.next()
            transport.invalidateSession()
            transport.activateSession()
            call.interruptAndJoin()
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

    /** Production install keeps the reusable worker; only the qualification seam selects per-input generations. */
    @Test
    fun defaultFacadeReusesItsWorkerAndAccountResetKeepsTheDefault() =
        withFacadeFields { context ->
            IsolatedMediaDecoder.install(context)
            val owner = privateField(IsolatedMediaDecoder, "transport") as ReusableMediaTransport
            val imageOwner = IsolatedMediaDecoder::class.java.getDeclaredField("imageOwner")
            assertNull(imageOwner.apply { isAccessible = true }.get(IsolatedMediaDecoder))
            startDecode(owner, 1).join()
            context.next()
            startDecode(owner, 2).join()
            assertEquals(1, context.bindings.size)
            val reused = context.bindings.single().remote
            assertEquals(2, reused.requests)
            assertEquals(1L, reused.retired.count)
            IsolatedMediaDecoder.resetForAccount()
            val replacement = privateField(IsolatedMediaDecoder, "transport")
            assertTrue(replacement is ReusableMediaTransport)
            assertFalse(owner === replacement)
            IsolatedMediaDecoder.installForLifetimeQualification(context)
            val candidate = privateField(IsolatedMediaDecoder, "transport") as IsolatedMediaTransport
            val call = startDecode(candidate, 4)
            try {
                val qualification = context.next()
                assertTrue(qualification.remote.retired.await(2, TimeUnit.SECONDS))
                assertTrue(context.instanceNames[1].startsWith("lifetime_images_"))
                assertFalse(context.instanceNames[0] == context.instanceNames[1])
                qualification.remote.die()
                call.join()
            } finally {
                call.interruptAndJoin()
            }
        }

    /** Clearing, reinstalling and resetting the facade keeps one owner, so an unconfirmed death still holds input. */
    @Test
    fun facadeClearInstallAndAccountResetKeepTheSameDeathWitness() =
        withFacadeFields { context ->
            val owner = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
            IsolatedMediaDecoder::class.java
                .getDeclaredField("imageOwner")
                .apply { isAccessible = true }
                .set(IsolatedMediaDecoder, owner)
            IsolatedMediaDecoder.installForLifetimeQualification(context)
            val call = startDecode(owner, 1)
            try {
                val first = context.next()
                assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
                IsolatedMediaDecoder.clear()
                IsolatedMediaDecoder.installForLifetimeQualification(context)
                IsolatedMediaDecoder.resetForAccount()
                assertSame(owner, privateField(IsolatedMediaDecoder, "imageOwner"))
                call.interruptAndJoin()
                assertNull(owner.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
                assertEquals(1, context.bindings.size)
                first.remote.die()
            } finally {
                call.interruptAndJoin()
            }
        }

    /** A slow old-session source close cannot retire a generation started for the new session. */
    @Test
    fun oldSourceCloseCannotRetireTheNewlyPublishedSessionWorker() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context)
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
        var call: DecodeCall? = null
        try {
            val session = privateField(transport, "session")
            val sources = privateField(session, "sources")
            sources.javaClass
                .getDeclaredField("sourceTimer")
                .apply { isAccessible = true }
                .set(sources, lazyOf(scheduler))
            reset.start()
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            call = startDecode(transport, 1)
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

    /** A URI source still being acquired for an old session never reaches any generation. */
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
        val transport = singleLane(context)
        val outcome = AtomicReference<Result<android.graphics.Bitmap?>>()
        val uri = Uri.parse("content://$authority/old")
        val old =
            Thread {
                outcome.set(runCatching { transport.decodeUri(context.contentResolver, uri, 1) })
            }
        var next: DecodeCall? = null
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
            next = startDecode(transport, 2)
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

    /** A throwing bind holds its slot, a late connection is stopped without input and released once. */
    @Test
    fun lateConnectionAfterThrowingBindIsStoppedButHeldUntilItsDeath() {
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                connect = false,
                throwAfterBind = true,
            )
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val first = context.next()
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.connection.onServiceConnected(context.component, first.remote)
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            // Android is asked to stop it at once, but only its death releases the slot.
            assertEquals(listOf(first.connection), context.unbound)
            assertEquals(0, first.remote.requests)
            assertNull(transport.decode(byteArrayOf(3), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            first.remote.die()
            assertEquals(listOf(first.connection), context.unbound)
        } finally {
            context.dieAll()
            transport.close()
        }
    }

    /** A reset revokes the reply capability, so the worker's held reply is refused by the transaction itself. */
    @Test
    fun replyCapabilityIsRevokedByResetBeforeTheHeldReplyArrives() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        val old = startDecode(transport, 1)
        var next: DecodeCall? = null
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
            next = startDecode(transport, 2)
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

    /** A definite bind refusal fails that input, releases its connection once and allows a later generation. */
    @Test
    fun definiteBindRefusalWithoutCallbackAllowsTheNextGeneration() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        context.acceptBind = false
        val transport = singleLane(context)
        var next: DecodeCall? = null
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val refused = context.next()
            assertEquals(0, refused.remote.requests)
            assertEquals(listOf(refused.connection), context.unbound)
            context.acceptBind = true
            next = startDecode(transport, 2)
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

    /** A live worker whose death cannot be registered is stopped without input and holds its slot forever. */
    @Test
    fun liveBinderDeathRegistrationFailureRetiresWithoutSendingAnyInput() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), failLink = true)
        val transport = singleLane(context, deadlineMs = HELD_DEADLINE_MS)
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            val first = context.next()
            assertTrue(first.remote.isBinderAlive)
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            assertEquals(0, first.remote.requests)
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            transport.close()
            // Android is asked to stop the unwitnessed worker, but no death recipient can reopen the slot.
            assertEquals(listOf(first.connection), context.unbound)
            // This fake context never executes the callback executor and owns no OS binding/thread.
            val callbackThread = privateField(privateField(transport, "pool"), "callbackThread") as Lazy<*>
            assertFalse(callbackThread.isInitialized())
        } finally {
            transport.close()
        }
    }

    /** Close is terminal: it fails new input at once and cannot be reactivated, even after the death. */
    @Test
    fun terminalCloseCannotReactivateEvenAfterObservedDeath() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = singleLane(context)
        val call = startDecode(transport, 1)
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

    /** Time spent waiting for the lane is not charged to the admitted exchange's own deadline. */
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
        val transport = singleLane(context)
        val gate = privateField(transport, "gate") as java.util.concurrent.Semaphore
        gate.acquire()
        val call = startDecode(transport, 7)
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

    /** A death that misses the deadline after an accepted reply returns nothing and quarantines only that source. */
    @Test
    fun deathDeadlineAfterReplyQuarantinesTheSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport = singleLane(context)
        val firstCall = startDecode(transport, 8)
        var next: DecodeCall? = null
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            first.remote.beforeRetire.set {
                org.robolectric.shadows.ShadowSystemClock
                    .advanceBy(java.time.Duration.ofSeconds(9))
            }
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(successfulPixelReply()) })
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            firstCall.join()
            assertNull(firstCall.result.get())
            val session = privateField(transport, "session")
            assertEquals(1, (privateField(session, "refusals") as Map<*, *>).size)
            first.remote.die()
            // The same bytes are refused without another worker; other bytes still reach one.
            assertNull(transport.decode(byteArrayOf(8), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            next = startDecode(transport, 18)
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

    /** A same-UID terminal reply is no valid result, so its source is quarantined like any failed dispatch. */
    @Test
    fun unattestedTerminalReplyQuarantinesTheSource() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = singleLane(context)
        val firstCall = startDecode(transport, 9)
        var next: DecodeCall? = null
        try {
            val first = context.next()
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            first.remote.die()
            firstCall.join()
            val session = privateField(transport, "session")
            assertEquals(1, (privateField(session, "refusals") as Map<*, *>).size)
            assertNull(transport.decode(byteArrayOf(9), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            next = startDecode(transport, 19)
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
        val transport = singleLane(context)
        val firstCall = startDecode(transport, 10)
        var next: DecodeCall? = null
        try {
            val first = context.next()
            assertTrue(first.remote.decoded.await(2, TimeUnit.SECONDS))
            val negative =
                Bundle().apply {
                    putBoolean("success", false)
                    putBoolean("isolated", true)
                }
            assertTrue(withWorkerIdentity { first.remote.deliverHeldReply(negative) })
            assertTrue(first.remote.retired.await(2, TimeUnit.SECONDS))
            val session = privateField(transport, "session")
            assertTrue("No cooldown before exact death", (privateField(session, "refusals") as Map<*, *>).isEmpty())
            first.remote.die()
            firstCall.join()
            assertEquals(1, (privateField(session, "refusals") as Map<*, *>).size)
            assertNull(transport.decode(byteArrayOf(10), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            next = startDecode(transport, 11)
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

    /** Runs [block] on a cleared facade and restores every facade field afterwards. */
    private fun withFacadeFields(block: (BindingFixture) -> Unit) {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = true)
        val fields =
            listOf("transport", "mediaTransport", "installedContext", "imageOwner", "lastIsolation")
                .associateWith { name ->
                    IsolatedMediaDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }
                }
        val previous = fields.mapValues { (_, member) -> member.get(IsolatedMediaDecoder) }
        try {
            // Keep prior owners referenced and untouched, this fixture owns only its replacement.
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            block(context)
        } finally {
            val owned = fields.getValue("imageOwner").get(IsolatedMediaDecoder) as IsolatedMediaTransport?
            try {
                IsolatedMediaDecoder.clear()
                context.dieAll()
                owned?.close()
            } finally {
                fields.forEach { (name, member) -> member.set(IsolatedMediaDecoder, previous[name]) }
            }
        }
    }
}

/** Long enough for the 250 ms escalation, short enough that a held lane fails a test quickly. */
internal const val HELD_DEADLINE_MS = 600L

/** A one-generation lane without spares, so every binding corresponds to exactly one input. */
internal fun singleLane(
    context: BindingFixture,
    deadlineMs: Long = MediaDecodePolicy.DEADLINE_MS,
): IsolatedMediaTransport = IsolatedMediaTransport(context, "images", spares = 0, deadlineMs = deadlineMs)

/** Runs [block] as if a different (worker) UID made the call, then restores the caller identity. */
internal fun <T> withWorkerIdentity(block: () -> T): T {
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
    return try {
        block()
    } finally {
        org.robolectric.shadows.ShadowBinder
            .setCallingIdentityForCurrentThread(previous)
    }
}

/** A complete 1×1 success frame with every field the parent validates. */
internal fun successfulPixelReply(): Bundle =
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
    }
