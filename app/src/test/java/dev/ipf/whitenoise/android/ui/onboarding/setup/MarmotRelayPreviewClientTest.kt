package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.state.AppAccountSessionActivation
import dev.ipf.whitenoise.android.state.AppAccountSessionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Exercises preview acquisition and cancellation at the concrete native adapter boundary. */
class MarmotRelayPreviewClientTest {
    /** Even an unexpected checkpoint without a proposal must not trigger automatic follow-list advancement. */
    @Test fun editorPreviewNeverAdvancesDefaults() =
        runTest {
            val calls = mutableListOf<String>()
            val snapshot = setupSnapshot(OnboardingStepFfi.FOLLOWS, listOf(OnboardingActionFfi.CONTINUE_WITHOUT))
            val native =
                nativeBoundary { method ->
                    calls += method
                    snapshot
                }
            val result =
                MarmotAccountSetupClient(native, SETUP_TEST_ACCOUNT) {}.previewRelayRepair(OnboardingStepFfi.RELAYS)
            assertEquals(snapshot, result)
            assertEquals(listOf("proposeOnboardingRelayRepair"), calls)
        }

    /** Cancellation between native completion and caller delivery releases the otherwise orphaned proposal. */
    @Test fun cancelledDeliveryCleansUpWithoutMaskingCancellation() =
        runTest {
            for (failCleanup in listOf(false, true)) {
                val calls = mutableListOf<String>()
                val preview = relayPreviewSnapshot()
                lateinit var owner: Job
                var cancellation: CancellationException? = null
                val native =
                    nativeBoundary { method ->
                        calls += method
                        when (method) {
                            "proposeOnboardingRelayRepair" -> {
                                owner.cancel()
                                preview
                            }
                            "onboardingSnapshot" -> preview
                            "cancelOnboardingRepair" -> {
                                check(!failCleanup) { "cleanup failed" }
                                relayDecision().copy(revision = 5uL)
                            }
                            else -> error("Unexpected native call: $method")
                        }
                    }
                owner =
                    launch(start = CoroutineStart.LAZY) {
                        try {
                            MarmotAccountSetupClient(native, SETUP_TEST_ACCOUNT) {}
                                .previewRelayRepair(OnboardingStepFfi.RELAYS)
                        } catch (cancel: CancellationException) {
                            cancellation = cancel
                            throw cancel
                        }
                    }
                owner.start()
                owner.join()
                assertNotNull(cancellation)
                assertEquals(if (failCleanup) 1 else 0, cancellation!!.suppressed.size)
                assertEquals(
                    listOf("proposeOnboardingRelayRepair", "onboardingSnapshot", "cancelOnboardingRepair"),
                    calls,
                )
            }
        }

    /** Actual controller close drains the concrete native preview cleanup through the production lifetime. */
    @Test
    fun closedMountStillDrainsOnlyItsOwnedNativePreview() =
        runBlocking {
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val unusedActivation =
                Proxy.newProxyInstance(
                    AppAccountSessionActivation::class.java.classLoader,
                    arrayOf(AppAccountSessionActivation::class.java),
                ) { _, method, _ -> error("Unexpected activation: ${method.name}") } as AppAccountSessionActivation
            val owner = AppAccountSessionCoordinator(parent, Dispatchers.Default, { error("unused") }, unusedActivation)
            val mounted = AtomicBoolean(true)
            val finishing = AtomicBoolean(false)
            val cleanup = AtomicBoolean(false)
            val nativeCurrent = AtomicReference(relayDecision())
            val preview = relayPreviewSnapshot()
            val previewReply = AtomicReference<Continuation<Any?>?>(null)
            val cleanupReply = AtomicReference<Continuation<Any?>?>(null)
            val previewEntered = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val cancels = AtomicInteger(0)
            val native =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, args ->
                    when (method.name.substringBefore('-')) {
                        "runOnboarding" -> relayDecision()
                        "proposeOnboardingRelayRepair" -> {
                            nativeCurrent.set(preview)
                            if (finishing.get()) {
                                preview
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                val continuation = args!!.last() as Continuation<Any?>
                                previewReply.set(continuation)
                                previewEntered.complete(Unit)
                                COROUTINE_SUSPENDED
                            }
                        }
                        "onboardingSnapshot" -> {
                            if (cleanup.get() && !finishing.get()) {
                                @Suppress("UNCHECKED_CAST")
                                val continuation = args!!.last() as Continuation<Any?>
                                cleanupReply.set(continuation)
                                cleanupEntered.complete(Unit)
                                COROUTINE_SUSPENDED
                            } else {
                                nativeCurrent.get()
                            }
                        }
                        "cancelOnboardingRepair" -> {
                            cancels.incrementAndGet()
                            relayDecision().copy(revision = 5uL).also(nativeCurrent::set)
                        }
                        else -> error("Unexpected native call: ${method.name}")
                    }
                } as MarmotInterface
            val nativeClient =
                MarmotAccountSetupClient(
                    native,
                    SETUP_TEST_ACCOUNT,
                    withLifetime = { command ->
                        owner.withLifetime {
                            if (!mounted.get()) throw CancellationException("stale mount")
                            command()
                        }
                    },
                    withOwnedPreviewCleanup = { command -> owner.withLifetime { command() } },
                ) {}
            val stream = FakeSetupClient(relayDecision())
            val client =
                object : AccountSetupClient by nativeClient {
                    override suspend fun subscribe() = stream.subscribe()

                    override suspend fun discardOwnedRelayPreview(preview: OnboardingSnapshotFfi) =
                        nativeClient.discardOwnedRelayPreview(
                            preview,
                        )
                }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, parent, mounted::get, {}, {})
            try {
                controller.reconnect()
                withTimeout(5_000) { controller.state.first { it.snapshot != null && !it.busy } }
                controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
                withTimeout(5_000) { previewEntered.await() }
                mounted.set(false)
                cleanup.set(true)
                val close = async { controller.close() }
                previewReply.getAndSet(null)!!.resumeWith(Result.success(preview))
                withTimeout(5_000) { cleanupEntered.await() }
                assertFalse(close.isCompleted)
                cleanupReply.getAndSet(null)!!.resumeWith(Result.success(preview))
                withTimeout(5_000) { close.await() }
                assertEquals(1, cancels.get())
                assertNull(nativeCurrent.get().proposal)
                assertEquals(5uL, nativeCurrent.get().revision)
            } finally {
                finishing.set(true)
                previewReply.getAndSet(null)?.resumeWith(Result.success(preview))
                cleanupReply.getAndSet(null)?.resumeWith(Result.success(preview))
                withTimeout(5_000) { controller.close() }
                parent.cancel()
            }
        }

    /** The native owned-cleanup path keeps replacement revisions and proposals intact. */
    @Test
    fun ownedCleanupRejectsReplacementProposal() =
        runBlocking {
            val preview = relayPreviewSnapshot()
            val newer = preview.copy(revision = preview.revision + 1uL)
            val calls = mutableListOf<String>()
            val native =
                nativeBoundary { method ->
                    calls += method
                    check(method == "onboardingSnapshot")
                    newer
                }
            val client = MarmotAccountSetupClient(native, SETUP_TEST_ACCOUNT) {}
            assertNull(client.discardRelayPreview(preview))
            assertEquals(listOf("onboardingSnapshot"), calls)
        }

    /** Returns scripted native snapshots while rejecting accidental automatic setup commands. */
    private fun nativeBoundary(onCall: (String) -> OnboardingSnapshotFfi): MarmotInterface {
        val nativeType = MarmotInterface::class.java
        return Proxy.newProxyInstance(nativeType.classLoader, arrayOf(nativeType)) { _, method, _ ->
            onCall(method.name.substringBefore('-'))
        } as MarmotInterface
    }
}
