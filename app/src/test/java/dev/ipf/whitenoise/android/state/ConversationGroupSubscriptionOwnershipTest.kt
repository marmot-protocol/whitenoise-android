package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.ui.share.group
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A native group subscription stays owned while the shared Android opener carries it back from the MDK
 * dispatcher (#4221). If the caller was cancelled during the open, nobody received the handle, so the opener
 * closes it exactly once. A handle that reached its caller is closed only by that caller's own cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationGroupSubscriptionOwnershipTest {
    /** Cancellation that wins the dispatch back to the caller closes the undelivered handle once. */
    @Test
    fun cancelledDeliveryClosesTheUndeliveredHandleOnce() =
        runTest {
            val handle = FakeGroupHandle()
            val nativeOpen = CompletableDeferred<Unit>()
            val subscriptions = subscriptions(StandardTestDispatcher(testScheduler), nativeOpen, handle)
            val caller =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { subscriptions.openGroupState(ACCOUNT, GROUP) }
                }
            runCurrent()

            caller.cancel()
            nativeOpen.complete(Unit)
            runCurrent()

            val outcome = runCatching { caller.await() }
            assertTrue(caller.isCancelled)
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertEquals(1, handle.closes)
        }

    /** A delivered handle belongs to the caller, so the opener leaves it open. */
    @Test
    fun deliveredHandleIsLeftToItsCaller() =
        runTest {
            val handle = FakeGroupHandle()
            val subscriptions =
                subscriptions(StandardTestDispatcher(testScheduler), CompletableDeferred(Unit), handle)

            val delivered = subscriptions.openGroupState(ACCOUNT, GROUP)

            assertSame(handle, delivered)
            assertEquals(0, handle.closes)
        }

    /** The invite confirmation read receives the handle and its own cleanup closes it exactly once. */
    @Test
    fun inviteConfirmationClosesItsDeliveredHandleOnce() =
        runTest {
            val handle = FakeGroupHandle(group(GROUP))
            val subscriptions =
                subscriptions(StandardTestDispatcher(testScheduler), CompletableDeferred(Unit), handle)

            val confirmed = subscriptions.readInviteConfirmation(ACCOUNT, GROUP)

            assertEquals(GROUP, confirmed.groupIdHex)
            assertEquals(1, handle.closes)
        }

    /** A failed open has no handle to release and surfaces its failure to the caller. */
    @Test
    fun failedOpenClosesNothing() =
        runTest {
            var closes = 0
            val dispatcher = StandardTestDispatcher(testScheduler)
            val subscriptions =
                ConversationLiveSubscriptions(
                    openTimeline = { _, _, _ -> error("unexpected timeline") },
                    openGroupState = { _, _ ->
                        val countClose: (ConversationGroupStateSubscriptionHandle) -> Unit = { closes += 1 }
                        openOwnedAcrossDispatch(close = countClose) { _ ->
                            withContext(dispatcher) { error("native open failed") }
                        }
                    },
                )

            val failure = runCatching { subscriptions.openGroupState(ACCOUNT, GROUP) }.exceptionOrNull()

            assertEquals("native open failed", failure?.message)
            assertEquals(0, closes)
        }

    /**
     * Builds the production opener shape around a fake: the open runs on [mdkDispatcher], waits for [nativeOpen]
     * without honouring cancellation as a blocking native call does, then hands [handle] back.
     */
    private fun subscriptions(
        mdkDispatcher: CoroutineDispatcher,
        nativeOpen: CompletableDeferred<Unit>,
        handle: FakeGroupHandle,
    ) = ConversationLiveSubscriptions(
        openTimeline = { _, _, _ -> error("unexpected timeline") },
        openGroupState = { _, _ ->
            openOwnedAcrossDispatch(close = ConversationGroupStateSubscriptionHandle::close) { deliver ->
                withContext(mdkDispatcher) {
                    withContext(NonCancellable) { nativeOpen.await() }
                    deliver(handle)
                }
            }
        },
    )

    /** Counts closes of a scripted group-state subscription. */
    private class FakeGroupHandle(
        private val record: AppGroupRecordFfi? = null,
    ) : ConversationGroupStateSubscriptionHandle {
        var closes = 0
            private set

        override fun snapshot(): AppGroupRecordFfi? = record

        override suspend fun next(): AppGroupRecordFfi? = null

        override fun close() {
            closes += 1
        }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
