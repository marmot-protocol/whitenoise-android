package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation source changes retire receivers within the same account binding. */
class ChatFolderLiveSourceTest {
    @Test fun sourceChangeCancelsAndJoinsReceiverBeforeReturning() =
        runBlocking {
            val source = ChatFolderLiveSource()
            val entered = CompletableDeferred<Unit>()
            var retired = false
            val result =
                async {
                    source.receiveUntilChanged(false) {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            retired = true
                        }
                    }
                }
            entered.await()
            source.complete.value = true
            assertTrue(withTimeout(1000) { result.await() })
            assertTrue(retired)
            assertFalse(source.receiveUntilChanged(true) {})
        }

    @Test fun cancellationRetiresReceiverAndReopeningCannotConsumeOldAccountFrames() =
        runBlocking {
            val source = ChatFolderLiveSource()
            val entered = CompletableDeferred<Unit>()
            var retired = false
            val job =
                async {
                    source.receiveUntilChanged(false) {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            retired = true
                        }
                    }
                }
            entered.await()
            job.cancel()
            job.join()
            assertTrue(retired)
            assertFalse(source.receiveUntilChanged(false) {})
        }
}
