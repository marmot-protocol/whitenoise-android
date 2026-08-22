package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentOpenLifecycleTest {
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
