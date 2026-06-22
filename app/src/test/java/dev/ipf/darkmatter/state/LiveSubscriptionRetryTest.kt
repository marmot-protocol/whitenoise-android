package dev.ipf.darkmatter.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

class LiveSubscriptionRetryTest {
    @Test
    fun doublesEachStepUntilCap() {
        assertEquals(1_000L, nextLiveSubscriptionRetryDelayMillis(500L))
        assertEquals(2_000L, nextLiveSubscriptionRetryDelayMillis(1_000L))
        assertEquals(4_000L, nextLiveSubscriptionRetryDelayMillis(2_000L))
        assertEquals(8_000L, nextLiveSubscriptionRetryDelayMillis(4_000L))
    }

    @Test
    fun capsAtMaximum() {
        assertEquals(8_000L, nextLiveSubscriptionRetryDelayMillis(8_000L))
        assertEquals(8_000L, nextLiveSubscriptionRetryDelayMillis(16_000L))
    }

    @Test
    fun accountScopedRetryStopsWhenControllerUnbinds() {
        assertEquals(true, shouldRetryLiveSubscriptionForAccount("alice", "alice"))
        assertEquals(false, shouldRetryLiveSubscriptionForAccount("alice", null))
        assertEquals(false, shouldRetryLiveSubscriptionForAccount("alice", "bob"))
    }

    @Test
    fun accountTeardownMatchesEitherControllerAccountField() {
        assertTrue(shouldTeardownLiveSubscriptionsForAccount("alice", controllerAccountRef = "alice", controllerBoundAccountRef = null))
        assertTrue(shouldTeardownLiveSubscriptionsForAccount("alice", controllerAccountRef = null, controllerBoundAccountRef = "alice"))
        assertTrue(shouldTeardownLiveSubscriptionsForAccount("alice", controllerAccountRef = "alice", controllerBoundAccountRef = "bob"))
    }

    @Test
    fun accountTeardownIgnoresUnrelatedOrAlreadyUnboundController() {
        assertFalse(shouldTeardownLiveSubscriptionsForAccount("alice", controllerAccountRef = "bob", controllerBoundAccountRef = "bob"))
        assertFalse(shouldTeardownLiveSubscriptionsForAccount("alice", controllerAccountRef = null, controllerBoundAccountRef = null))
    }

    @Test
    fun accountTeardownCancelsOnlyForeignLiveSubscriptionJob() {
        runBlocking {
            val current = coroutineContext[Job]
            val other = Job()
            try {
                assertFalse(shouldCancelLiveSubscriptionJob(null, current))
                assertFalse(shouldCancelLiveSubscriptionJob(current, current))
                assertTrue(shouldCancelLiveSubscriptionJob(other, current))
            } finally {
                other.cancel()
            }
        }
    }

    @Test
    fun destructiveWipePrepareAndFailedRestoreAdvanceRuntimeWithoutRestoringConversation() {
        val initial =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = "alice",
                activeConversationAccountRef = "alice",
                activeConversationGroupIdHex = "group-1",
                runtimeGeneration = 10,
            )

        val prepared = prepareDestructiveAccountWipeRuntimeState(initial)
        assertEquals(null, prepared.activeAccountRef)
        assertEquals(null, prepared.activeConversationAccountRef)
        assertEquals(null, prepared.activeConversationGroupIdHex)
        assertEquals(11, prepared.runtimeGeneration)

        val restored = restoreFailedDestructiveAccountWipeRuntimeState(prepared, "alice")
        assertEquals("alice", restored.activeAccountRef)
        assertEquals(null, restored.activeConversationAccountRef)
        assertEquals(null, restored.activeConversationGroupIdHex)
        assertEquals(12, restored.runtimeGeneration)
    }

    @Test
    fun nativePushWipeSerializationWaitsForExistingHolder() {
        runBlocking {
            val mutex = Mutex()
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            mutex.lock()

            val result =
                async {
                    entered.complete(Unit)
                    mutex.withSerializedNativePushWipe {
                        finished.complete(Unit)
                        "wiped"
                    }
                }
            entered.await()
            delay(50L)
            assertFalse(finished.isCompleted)

            mutex.unlock()
            assertEquals("wiped", result.await())
            assertTrue(finished.isCompleted)
        }
    }
}
