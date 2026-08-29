package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationLocalIdentityReaderTest {
    @Test
    fun successfulLocalReadReturnsWithinTheFirstPostBudget() =
        runTest {
            val reader =
                NotificationLocalIdentityReader(
                    scope = this,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    timeoutMillis = 100L,
                    readLocalDisplayName = { "Alice" },
                )

            assertEquals("Alice", reader.read("sender"))
        }

    @Test
    fun timedOutBlockingReadCannotQueueMoreBindingWork() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val reader =
                NotificationLocalIdentityReader(
                    scope = this,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    timeoutMillis = 100L,
                ) {
                    if (calls.incrementAndGet() == 1) {
                        withContext(NonCancellable) { release.await() }
                    }
                    "Alice"
                }

            val first = async { reader.read("sender-a") }
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()

            assertNull(first.await())
            assertNull(reader.read("sender-b"))
            assertEquals(1, calls.get())

            release.complete(Unit)
            runCurrent()

            assertEquals("Alice", reader.read("sender-c"))
            assertEquals(2, calls.get())
        }
}
