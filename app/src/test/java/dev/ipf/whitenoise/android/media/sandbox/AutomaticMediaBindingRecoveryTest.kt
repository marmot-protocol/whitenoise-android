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
@Suppress("LargeClass") // Automatic-lane regressions share one exact process/descriptor lifetime fixture.
class AutomaticMediaBindingRecoveryTest {
    /** Automatic Media Owner Survives Reset Until Its Exact Worker Dies. */
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
        var owner: IsolatedAutomaticAvTransport? = null
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(IsolatedMediaDecoder, null) }
            IsolatedMediaDecoder.install(context)
            val lane = fields.getValue("mediaOwner").get(IsolatedMediaDecoder) as IsolatedAutomaticAvTransport
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

    /** Automatic Source Refusals Are Bounded By Revision And Expiry And Healthy Source Recovers. */
    @Test
    fun automaticSourceRefusalsAreBoundedByRevisionAndExpiryAndHealthySourceRecovers() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        var clock = 1L
        val transport =
            IsolatedAutomaticAvTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            ) {
                clock
            }
        val file = java.io.File.createTempFile("av-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()

        /** Start one scripted automatic probe and retain its thread so cleanup can join the exact request. */
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

    /** Automatic Byte And Waveform Refusals Do Not Create A Second Worker For The Same Revision. */
    @Test
    fun automaticByteAndWaveformRefusalsDoNotCreateASecondWorkerForTheSameRevision() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Missing Attestation And Contention Do Not Quarantine And Account Reset Clears Confirmed Refusal. */
    @Test
    fun missingAttestationAndContentionDoNotQuarantineAndAccountResetClearsConfirmedRefusal() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedAutomaticAvTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-reset-refusal-", ".bin")
        val calls = mutableListOf<AutomaticCall>()

        /** Start one scripted automatic probe and retain its thread so cleanup can join the exact request. */
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

    /** Automatic Final Reply Requires Synchronous Exact Kernel Pid. */
    @Test
    fun automaticFinalReplyRequiresSynchronousExactKernelPid() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Selected Uri Close Must Finish Before Another Automatic Source Can Bind. */
    @Test
    @Config(shadows = [BorrowedDescriptorStatShadow::class, SelectedAssetCloseResolverShadow::class])
    fun selectedUriCloseMustFinishBeforeAnotherAutomaticSourceCanBind() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Selected Uri Close Failure Holds Across Account Reset Without Another Source Open. */
    @Test
    @Config(shadows = [BorrowedDescriptorStatShadow::class, SelectedAssetCloseResolverShadow::class])
    @Suppress("LongMethod") // Failure, account replacement and exact synthetic capability teardown are one fixture.
    fun selectedUriCloseFailureHoldsAcrossAccountResetWithoutAnotherSourceOpen() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedAutomaticAvTransport(
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
                IsolatedAutomaticAvTransport::class.java.getDeclaredField("parentResources").apply {
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

    /** Late Account Ready Cannot Hand Off Input And New Uid Waits For Old Process Absence. */
    @Test
    fun lateAccountReadyCannotHandOffInputAndNewUidWaitsForOldProcessAbsence() {
        val context =
            BindingFixture(ApplicationProvider.getApplicationContext(), reply = false).apply {
                holdIdentity =
                    true
            }
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Automatic False Bind Needs One Successful Unbind Before Replacement. */
    @Test
    fun automaticFalseBindNeedsOneSuccessfulUnbindBeforeReplacement() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.acceptBind = false
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Automatic Unbind Failure Holds Even After Confirmed Process Absence. */
    @Test
    fun automaticUnbindFailureHoldsEvenAfterConfirmedProcessAbsence() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.failUnbind = true
        val transport =
            IsolatedAutomaticAvTransport(
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
            // Drain asynchronous unbinding while requiring the failed cleanup to retain ownership.
            assertFalse(awaitAutomaticRelease(transport))
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

    /** Missing Ready Remains Held Across Binder Death And Reset Until Late Exact Calibration And Process Absence. */
    @Test
    fun missingReadyRemainsHeldAcrossBinderDeathAndResetUntilLateExactCalibrationAndProcessAbsence() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.holdIdentity = true
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Driver Flags Permit Synchronous Identity While Oneway Cannot Release An Uncalibrated Worker. */
    @Test
    fun driverFlagsPermitSynchronousIdentityWhileOnewayCannotReleaseAnUncalibratedWorker() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.holdIdentity = true
        val transport =
            IsolatedAutomaticAvTransport(
                context,
                "automatic_av",
                MediaAvDecodeService::class.java,
                processWitnessFactory = context::processWitness,
            )
        val file = java.io.File.createTempFile("av-identity-flags-", ".bin")
        val calls = mutableListOf<AutomaticCall>()
        try {
            file.writeBytes(byteArrayOf(1))
            val first = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
            val worker = context.next()
            assertTrue(worker.remote.identityRequested.await(2, TimeUnit.SECONDS))
            assertFalse(worker.remote.deliverIdentity(0x10 or IBinder.FLAG_ONEWAY))
            first.join()
            assertNull(first.result.get())
            assertEquals(0, worker.remote.requests)
            worker.remote.dieBinderOnly()
            context.kernelErrnoOverride = android.system.OsConstants.ESRCH
            assertAutomaticRequestHeld(transport, file, calls)
            assertEquals(1, context.bindings.size)
            assertFalse(transport.isRequestDispatchedForTest())
            // A late synchronous callback calibrates only cleanup; it never revives the retired request.
            context.kernelErrnoOverride = null
            assertFalse(worker.remote.deliverIdentity(0x10))
            worker.remote.processAlive = false
            assertTrue(awaitAutomaticRelease(transport))
            assertEquals(0, worker.remote.requests)
        } finally {
            calls.forEach { it.interruptAndJoin() }
            context.dieAll()
            transport.close()
            stopAutomaticObserver(transport)
            file.delete()
        }
    }

    /** Require the outstanding request to retain its admission instead of completing a replacement generation. */
    private fun assertAutomaticRequestHeld(
        transport: IsolatedAutomaticAvTransport,
        file: java.io.File,
        calls: MutableList<AutomaticCall>,
    ) {
        val held = startAutomatic { transport.probe(file, 1, false) }.also { calls += it }
        held.join()
        assertNull(held.result.get())
    }

    /** Inspect the exact automatic owner to ensure every required cleanup flag is acknowledged. */
    private fun assertAutomaticCleanupAcknowledged(transport: IsolatedAutomaticAvTransport) {
        val type = IsolatedAutomaticAvTransport::class.java
        val lock = type.getDeclaredField("bindingLock").apply { isAccessible = true }.get(transport)
        val worker = type.getDeclaredField("worker").apply { isAccessible = true }
        val resources = type.getDeclaredField("parentResources").apply { isAccessible = true }.get(transport)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var acknowledged = false
        while (!acknowledged && System.nanoTime() < deadline) {
            acknowledged =
                synchronized(lock) {
                    val selected = checkNotNull(worker.get(transport))

                    /**
                     * Read one lifetime flag from the selected fixture owner without substituting another
                     * generation.
                     */
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

    /** Failed Live Calibration Cannot Hand Off Input Or Release An Unknown Process. */
    @Test
    fun failedLiveCalibrationCannotHandOffInputOrReleaseAnUnknownProcess() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        context.kernelErrnoOverride = android.system.OsConstants.ESRCH
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Automatic Binder Death Cannot Copy Or Replace While Kernel Process Is Alive. */
    @Test
    fun automaticBinderDeathCannotCopyOrReplaceWhileKernelProcessIsAlive() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val transport =
            IsolatedAutomaticAvTransport(
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

    /** Wait within the fixture deadline for the exact owner to satisfy all admission-release predicates. */
    private fun awaitAutomaticRelease(transport: IsolatedAutomaticAvTransport): Boolean {
        val owner =
            IsolatedAutomaticAvTransport::class.java.getDeclaredField("bindingLock").apply {
                isAccessible = true
            }
        val worker = IsolatedAutomaticAvTransport::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val field =
            IsolatedAutomaticAvTransport::class.java.getDeclaredField("callbackThread").apply {
                isAccessible = true
            }

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

    /** Stop only the fixture observation executor after the assertion has finished. */
    private fun stopAutomaticObserver(transport: IsolatedAutomaticAvTransport) {
        val field =
            IsolatedAutomaticAvTransport::class.java.getDeclaredField("callbackThread").apply {
                isAccessible = true
            }

        @Suppress("UNCHECKED_CAST")
        val observer = field.get(transport) as Lazy<android.os.HandlerThread>
        if (observer.isInitialized()) observer.value.quitSafely()
        val retirementField =
            IsolatedAutomaticAvTransport::class.java.getDeclaredField("retirementThread").apply {
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

    /** Run one automatic operation on a fixture thread and retain its result or original failure. */
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
        /** Join the fixture request and require successful completion before reading its result. */
        fun join() {
            thread.join(2000)
            assertFalse("Automatic media caller must finish", thread.isAlive)
            failed.get()?.let { throw AssertionError("Automatic media fixture failed", it) }
        }

        /** Join the fixture request and return its retained failure rather than inferring an operation result. */
        fun joinFailure(): Throwable {
            thread.join(2000)
            assertFalse("Automatic failure caller must finish", thread.isAlive)
            return checkNotNull(failed.get())
        }

        /** Interrupt and join the exact request so the fixture cannot leak an active caller into the next test. */
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
    open class SelectedAssetCloseResolverShadow : ShadowContentResolver() {
        /** Admit only the selected fixture authorities, honor cancellation and forward to that exact provider. */
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

        /** Admit creation of this task-owned provider fixture without initializing unrelated application state. */
        override fun onCreate(): Boolean = true

        /** Return the fixed MIME type used by the selected descriptor fixture. */
        override fun getType(uri: Uri): String = "image/png"

        /** Return only the fixture descriptor whose close behavior is controlled by this test. */
        override fun openAssetFile(
            uri: Uri,
            mode: String,
        ): AssetFileDescriptor {
            openCount++
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return object : AssetFileDescriptor(input, 0, file.length()) {
                /**
                 * Apply the scripted selected-descriptor close behavior so admission cannot precede acknowledged
                 * cleanup.
                 */
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

        /** Reject provider queries outside the selected-descriptor fixture contract. */
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        /** Reject provider writes outside the selected-descriptor fixture contract. */
        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        /** Reject provider deletion outside the selected-descriptor fixture contract. */
        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        /** Reject provider updates outside the selected-descriptor fixture contract. */
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

        /** Create the scripted UID/PID witness whose kernel-presence observations the test controls. */
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

        /** Observe the next admitted binding within the supplied finite timeout. */
        fun poll(timeoutMs: Long): Bound? = arrivals.poll(timeoutMs, TimeUnit.MILLISECONDS)

        /** Require the next admitted binding within the fixture deadline. */
        fun next(): Bound = checkNotNull(arrivals.poll(2, TimeUnit.SECONDS)) { "Binding was not admitted" }

        /** Deliver scripted death to every binding retained by this fixture context. */
        fun dieAll() = bindings.forEach { it.remote.die() }

        /** Record the exact requested instance and service connection before applying the scripted bind outcome. */
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

        /** Return the fixture context so the transport cannot escape into the real application binding path. */
        override fun getApplicationContext(): Context = this

        /** Record or refuse the exact connection release according to the selected fixture scenario. */
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

        /** Register the selected death recipient unless the fixture deliberately refuses a live endpoint. */
        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            if (failLink) throw android.os.RemoteException("Death registration unavailable")
            synchronized(recipients) { recipients += recipient }
            linkCount += 1
        }

        /** Remove only the supplied fixture death recipient and report whether it was registered. */
        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean = true

        @Volatile var processAlive = true

        /**
         * Signal Binder death while deliberately retaining kernel process presence for the separate witness
         * assertion.
         */
        fun dieBinderOnly() = synchronized(recipients) { recipients.toList() }.forEach { it.binderDied() }

        /** Signal both the scripted Binder death and the matching kernel process disappearance. */
        fun die() {
            processAlive = false
            dieBinderOnly()
        }

        /** Deliver the selected input-free identity callback with explicit Binder transaction flags. */
        fun deliverIdentity(transportFlags: Int = 0x10): Boolean {
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
                val transport =
                    object : IBinder by callback {
                        /**
                         * Carry the scripted Binder call through the exact callback endpoint without a different
                         * caller identity.
                         */
                        override fun transact(
                            code: Int,
                            data: Parcel,
                            reply: Parcel?,
                            flags: Int,
                        ): Boolean = callback.transact(code, data, reply, flags or transportFlags)
                    }
                sendAutomaticAvIdentity(transport, request)
            } finally {
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(previous)
            }
        }

        /** Deliver the retained reply capability after the test has controlled its account and worker lifetime. */
        fun deliverHeldReply(
            result: Bundle = Bundle(),
            synchronous: Boolean = false,
        ): Boolean {
            val (callback, id) = checkNotNull(heldReply)
            var accepted: Boolean? = null
            val recording =
                object : IBinder by callback {
                    /**
                     * Carry the scripted Binder call through the exact callback endpoint without a different
                     * caller identity.
                     */
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

        /** Decode the scripted worker transaction and emit only the reply selected by the fixture scenario. */
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
