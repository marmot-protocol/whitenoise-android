package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPushDrainTest {
    /** An update emitted synchronously during startup must reach the already-armed waiter. */
    @Test
    fun immediateStartupUpdateCompletesDrain() =
        runTest {
            val signals = MutableSharedFlow<Long>()
            val drained =
                awaitNotificationPushDrain(
                    sequenceBeforeStart = 3L,
                    notificationDrainSignals = signals,
                    timeoutMs = 100L,
                    startRuntime = {
                        signals.emit(3L)
                        signals.emit(4L)
                        true
                    },
                    keepConnected = { false },
                )

            assertTrue(drained)
            assertEquals(0, signals.subscriptionCount.value)
        }

    /** One-shot fetch failure must cancel its drain waiter before the supervisor retries. */
    @Test
    fun failedCatchUpReleasesDrainSubscription() =
        runTest {
            val signals = MutableSharedFlow<Long>()
            val failure =
                runCatching {
                    awaitNotificationPushDrain(
                        sequenceBeforeStart = 0L,
                        notificationDrainSignals = signals,
                        timeoutMs = 100L,
                        startRuntime = { false },
                        keepConnected = { false },
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(0, signals.subscriptionCount.value)
        }

    /** Cancellation remains cancellation and releases the waiter even in Keep connected mode. */
    @Test
    fun cancelledStartupReleasesDrainSubscription() =
        runTest {
            val signals = MutableSharedFlow<Long>()
            val cancellation = CancellationException("runtime stopped")
            val failure =
                runCatching {
                    awaitNotificationPushDrain(
                        sequenceBeforeStart = 0L,
                        notificationDrainSignals = signals,
                        timeoutMs = 100L,
                        startRuntime = { throw cancellation },
                        keepConnected = { true },
                    )
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(cancellation.message, failure?.message)
            assertEquals(0, signals.subscriptionCount.value)
        }
}
