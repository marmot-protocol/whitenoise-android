package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AttachmentDownloadIntentStore
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentInstallerHandoffCoordinator
import dev.ipf.whitenoise.android.state.AttachmentInstallerHandoffRequest
import dev.ipf.whitenoise.android.state.AttachmentOpenRequest
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.attachmentOpenChatSelectionMatches
import dev.ipf.whitenoise.android.state.attachmentOpenDestinationVisible
import dev.ipf.whitenoise.android.state.newAttachmentOpenNavigationGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentOpenLifecycleTest {
    /** Route changes preserve APK intent; only app foreground gates its dispatch. */
    @Test
    fun installerHandoffSurvivesRouteChangeButDefersInTheBackground() =
        runTest {
            val preferences =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getSharedPreferences("attachment-installer-lifecycle-test", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val store = AttachmentDownloadIntentStore(preferences)
            val transfer =
                AttachmentTransferRequest(
                    accountRef = "account-a",
                    groupIdHex = "ab".repeat(16),
                    messageIdHex = "cd".repeat(32),
                    attachmentIndex = 0,
                )
            val request = AttachmentInstallerHandoffRequest(transfer, sourceEpoch = 7uL)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var foreground = false
            try {
                assertTrue(store.markInstallerHandoff(request))
                store.retainOpenIntentsForDestination(
                    AttachmentOpenRequest(transfer.copy(accountRef = "account-b"), 9L).destination,
                )
                val coordinator =
                    AttachmentInstallerHandoffCoordinator(
                        intentStore = store,
                        scope = scope,
                        enqueue = { _, priority -> assertEquals(AttachmentDownloadPriority.Interactive, priority) },
                        foregroundEligible = { foreground },
                        persistence = dispatcher,
                    )

                assertNull(coordinator.pending())
                testScheduler.runCurrent()
                assertEquals(request, coordinator.pending())
                assertFalse(coordinator.canDispatch(request))
                foreground = true
                assertTrue(coordinator.canDispatch(request))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun externalDispatchRequiresTheExactVisibleNavigationSession() {
        val transfer =
            AttachmentTransferRequest(
                accountRef = "account-a",
                groupIdHex = "ab".repeat(32),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        val request = AttachmentOpenRequest(transfer, navigationGeneration = 7L)

        assertTrue(
            attachmentOpenDestinationVisible(
                selectedDestination = request.destination,
                request = request,
                appInForeground = true,
                activeAccountRef = transfer.accountRef,
                activeGroupIdHex = transfer.groupIdHex,
            ),
        )
        assertFalse(
            attachmentOpenDestinationVisible(
                selectedDestination = request.destination.copy(navigationGeneration = 8L),
                request = request,
                appInForeground = true,
                activeAccountRef = transfer.accountRef,
                activeGroupIdHex = transfer.groupIdHex,
            ),
        )
        assertFalse(
            attachmentOpenDestinationVisible(
                selectedDestination = request.destination,
                request = request,
                appInForeground = true,
                activeAccountRef = "account-b",
                activeGroupIdHex = transfer.groupIdHex,
            ),
        )
        assertFalse(
            attachmentOpenDestinationVisible(
                selectedDestination = request.destination,
                request = request,
                appInForeground = true,
                activeAccountRef = transfer.accountRef,
                activeGroupIdHex = "ef".repeat(32),
            ),
        )
    }

    @Test
    fun coldNavigationSessionsCannotReuseLegacyOrAnotherSessionsGeneration() {
        val first = newAttachmentOpenNavigationGeneration(UUID(1L, 2L))
        val second = newAttachmentOpenNavigationGeneration(UUID(4L, 8L))

        assertTrue(first < 0L)
        assertTrue(second < 0L)
        assertNotEquals(first, second)
        assertNotEquals(0L, first)
        assertNotEquals(1L, first)
    }

    @Test
    fun attachmentOpenSelectionNeverTreatsMissingRouteSlotsAsAVisibleChat() {
        assertFalse(attachmentOpenChatSelectionMatches(null, null))
        assertFalse(attachmentOpenChatSelectionMatches("chat-a", null))
        assertFalse(attachmentOpenChatSelectionMatches(null, "chat-a"))
        assertFalse(attachmentOpenChatSelectionMatches("chat-a", "chat-b"))
        assertTrue(attachmentOpenChatSelectionMatches("chat-a", "chat-a"))
    }

    @Test
    fun pendingOpenWaitsUntilTheOwningLifecycleResumes() =
        runBlocking {
            val owner = AttachmentLifecycleOwner().apply { moveTo(Lifecycle.Event.ON_START) }
            val resumed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    owner.lifecycle.awaitResumedOrDestroyed()
                }

            assertFalse(resumed.isCompleted)
            owner.moveTo(Lifecycle.Event.ON_RESUME)
            assertTrue(resumed.await())
        }

    @Test
    fun destroyingTheOwningLifecycleDoesNotDispatchTheOpen() =
        runBlocking {
            val owner = AttachmentLifecycleOwner().apply { moveTo(Lifecycle.Event.ON_START) }
            val resumed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    owner.lifecycle.awaitResumedOrDestroyed()
                }

            owner.moveTo(Lifecycle.Event.ON_DESTROY)
            assertFalse(resumed.await())
        }

    @Test
    fun readyAttachmentDispatchesOnceAcrossRecreatedOwners() =
        runBlocking {
            var intentAvailable = true
            var dispatchCount = 0

            suspend fun dispatch(owner: AttachmentLifecycleOwner): Boolean =
                dispatchAttachmentOpenWhenReady(
                    lifecycle = owner.lifecycle,
                    awaitReady = {},
                    isReady = { true },
                    consume = {
                        if (intentAvailable) {
                            intentAvailable = false
                            true
                        } else {
                            false
                        }
                    },
                    dispatch = { dispatchCount++ },
                )

            val firstOwner =
                AttachmentLifecycleOwner().apply {
                    moveTo(Lifecycle.Event.ON_START)
                    moveTo(Lifecycle.Event.ON_RESUME)
                }
            val recreatedOwner =
                AttachmentLifecycleOwner().apply {
                    moveTo(Lifecycle.Event.ON_START)
                    moveTo(Lifecycle.Event.ON_RESUME)
                }

            assertTrue(dispatch(firstOwner))
            assertFalse(dispatch(recreatedOwner))
            assertEquals(1, dispatchCount)
        }

    @Test
    fun attachmentWaitsForReadinessAndResumeBeforeConsumingIntent() =
        runBlocking {
            val owner = AttachmentLifecycleOwner().apply { moveTo(Lifecycle.Event.ON_START) }
            val ready = CompletableDeferred<Unit>()
            var consumeCount = 0
            var dispatchCount = 0
            val dispatched =
                async(start = CoroutineStart.UNDISPATCHED) {
                    dispatchAttachmentOpenWhenReady(
                        lifecycle = owner.lifecycle,
                        awaitReady = { ready.await() },
                        isReady = { ready.isCompleted },
                        consume = {
                            consumeCount++
                            true
                        },
                        dispatch = { dispatchCount++ },
                    )
                }

            ready.complete(Unit)
            assertFalse(dispatched.isCompleted)
            assertEquals(0, consumeCount)
            owner.moveTo(Lifecycle.Event.ON_RESUME)

            assertTrue(dispatched.await())
            assertEquals(1, consumeCount)
            assertEquals(1, dispatchCount)
        }

    @Test
    fun cancellationAfterConsumptionCannotStrandTheOpenBeforeDispatchCompletes() =
        runBlocking {
            val dispatchStarted = CompletableDeferred<Unit>()
            val releaseDispatch = CompletableDeferred<Unit>()
            var consumeCount = 0
            var dispatchCount = 0
            val opening =
                async(start = CoroutineStart.UNDISPATCHED) {
                    consumeAndDispatchAttachmentOpen(
                        consume = {
                            consumeCount++
                            true
                        },
                        restore = {},
                        dispatch = {
                            dispatchStarted.complete(Unit)
                            releaseDispatch.await()
                            dispatchCount++
                        },
                    )
                }

            dispatchStarted.await()
            opening.cancel()
            assertEquals(1, consumeCount)
            assertEquals(0, dispatchCount)

            releaseDispatch.complete(Unit)
            opening.join()
            assertEquals(1, dispatchCount)
        }

    @Test
    fun failedDispatchRestoresTheConsumedIntentForALaterOwner() =
        runBlocking {
            var intentAvailable = true
            var restoreCount = 0

            val failure =
                runCatching {
                    consumeAndDispatchAttachmentOpen(
                        consume = {
                            intentAvailable.also { intentAvailable = false }
                        },
                        restore = {
                            restoreCount++
                            intentAvailable = true
                        },
                        dispatch = { error("viewer launch failed") },
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(intentAvailable)
            assertEquals(1, restoreCount)
        }

    @Test
    fun reportedDispatchFailureRestoresTheIntentWithoutEscapingTheEffect() =
        runBlocking {
            var intentAvailable = true
            var reportedFailure: Throwable? = null

            val dispatched =
                consumeAndDispatchAttachmentOpenReportingFailure(
                    consume = {
                        intentAvailable.also { intentAvailable = false }
                    },
                    restore = { intentAvailable = true },
                    dispatch = { error("viewer launch failed") },
                    onFailure = { reportedFailure = it },
                )

            assertFalse(dispatched)
            assertTrue(intentAvailable)
            assertTrue(reportedFailure is IllegalStateException)
        }

    @Test
    fun foregroundFailureThenDurableCompletionDispatchesThePendingOpenExactlyOnce() =
        runBlocking {
            val transferState = MutableStateFlow(AttachmentTransferState.Failed)
            val freshAvailability = Channel<Unit>(Channel.RENDEZVOUS)
            var materializeAttempts = 0
            var waitingForWorker = false
            var failureCount = 0
            var intentAvailable = true
            var dispatchCount = 0
            val opening =
                async(start = CoroutineStart.UNDISPATCHED) {
                    val artifact =
                        materializePersistedAttachmentOpen(
                            materialize = {
                                materializeAttempts++
                                if (materializeAttempts == 1) null else "cached-file"
                            },
                            durableAvailabilityExpected = true,
                            awaitNextDurableAvailability = { freshAvailability.receive() },
                            onWaitingForDurableAvailability = { waitingForWorker = true },
                            onTerminalFailure = { failureCount++ },
                        ) ?: return@async false
                    consumeAndDispatchAttachmentOpen(
                        consume = {
                            intentAvailable.also { intentAvailable = false }
                        },
                        restore = { intentAvailable = true },
                        dispatch = {
                            assertEquals("cached-file", artifact)
                            dispatchCount++
                        },
                    )
                }

            assertTrue(waitingForWorker)
            assertFalse(opening.isCompleted)
            assertEquals(1, materializeAttempts)

            transferState.value = AttachmentTransferState.Available
            freshAvailability.send(Unit)

            assertTrue(opening.await())
            assertEquals(2, materializeAttempts)
            assertEquals(0, failureCount)
            assertEquals(1, dispatchCount)
            assertFalse(intentAvailable)

            val duplicate =
                consumeAndDispatchAttachmentOpen(
                    consume = { intentAvailable.also { intentAvailable = false } },
                    restore = { intentAvailable = true },
                    dispatch = { dispatchCount++ },
                )
            assertFalse(duplicate)
            assertEquals(1, dispatchCount)
        }

    @Test
    fun staleAvailableStateDoesNotEndThePendingOpenBeforeAFreshCacheSignal() =
        runBlocking {
            val freshAvailability = Channel<Unit>(Channel.RENDEZVOUS)
            val secondAttempt = CompletableDeferred<Unit>()
            val thirdAttempt = CompletableDeferred<Unit>()
            var materializeAttempts = 0
            val opening =
                async(start = CoroutineStart.UNDISPATCHED) {
                    materializePersistedAttachmentOpen(
                        materialize = {
                            materializeAttempts++
                            if (materializeAttempts == 2) secondAttempt.complete(Unit)
                            if (materializeAttempts == 3) thirdAttempt.complete(Unit)
                            if (materializeAttempts == 3) "cached-file" else null
                        },
                        durableAvailabilityExpected = true,
                        awaitNextDurableAvailability = { freshAvailability.receive() },
                        onWaitingForDurableAvailability = {},
                        onTerminalFailure = {},
                    )
                }

            assertFalse(opening.isCompleted)
            assertEquals(1, materializeAttempts)

            freshAvailability.send(Unit)
            withTimeout(1_000) { secondAttempt.await() }
            assertFalse(opening.isCompleted)
            assertEquals(2, materializeAttempts)

            freshAvailability.send(Unit)
            withTimeout(1_000) { thirdAttempt.await() }
            assertEquals("cached-file", opening.await())
            assertEquals(3, materializeAttempts)
        }

    @Test
    fun durableCompletionRestartsVisualMaterializationBeforeThePendingOpenDispatches() =
        runBlocking {
            val owner =
                AttachmentLifecycleOwner().apply {
                    moveTo(Lifecycle.Event.ON_START)
                    moveTo(Lifecycle.Event.ON_RESUME)
                }
            val transferState = MutableStateFlow(AttachmentTransferState.Failed)
            val ready = MutableStateFlow(false)
            var ensureCount = 0
            var intentAvailable = true
            var dispatchCount = 0
            val opening =
                async(start = CoroutineStart.UNDISPATCHED) {
                    dispatchAttachmentOpenWhenReady(
                        lifecycle = owner.lifecycle,
                        awaitReady = {
                            awaitAttachmentReadyAfterDurableCompletion(
                                readiness = ready,
                                transferState = transferState,
                                ensureMaterialization = {
                                    ensureCount++
                                    ready.value = true
                                },
                            )
                        },
                        isReady = { ready.value },
                        consume = {
                            intentAvailable.also { intentAvailable = false }
                        },
                        dispatch = { dispatchCount++ },
                    )
                }

            assertFalse(opening.isCompleted)
            assertEquals(0, ensureCount)
            transferState.value = AttachmentTransferState.Available

            assertTrue(opening.await())
            assertEquals(1, ensureCount)
            assertEquals(1, dispatchCount)
            assertFalse(intentAvailable)
        }

    @Test(expected = CancellationException::class)
    fun dispatchCancellationStillPropagates() {
        runBlocking {
            consumeAndDispatchAttachmentOpenReportingFailure(
                consume = { true },
                restore = {},
                dispatch = { throw CancellationException("viewer closed") },
                onFailure = { error("cancellation must not be reported as a launch failure") },
            )
        }
    }
}

private class AttachmentLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_START) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        registry.handleLifecycleEvent(event)
    }
}
