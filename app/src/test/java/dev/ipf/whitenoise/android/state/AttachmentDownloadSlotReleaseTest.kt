package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A download that MDK holds paused gives its gate slot to other downloads until the pause ends (#3819). */
class AttachmentDownloadSlotReleaseTest {
    /** While the holder waits out a pause, the next request runs; afterwards the holder queues for a slot again. */
    @Test
    fun aPausedHolderLetsTheNextRequestRunThenQueuesAgain() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val events = mutableListOf<String>()
                val pauseEnds = CompletableDeferred<Unit>()
                val secondRunning = CompletableDeferred<Unit>()
                val secondDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            events += "paused started"
                            withDownloadSlotReleased { pauseEnds.await() }
                            events += "paused resumed"
                        }
                    }
                val next =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("next", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            events += "next started"
                            secondRunning.complete(Unit)
                            secondDone.await()
                        }
                    }
                secondRunning.await()
                pauseEnds.complete(Unit)
                kotlinx.coroutines.yield()
                assertEquals("the resumed holder waits for a free slot", 2, events.size)
                secondDone.complete(Unit)
                listOf(paused, next).awaitAll()

                assertEquals(listOf("paused started", "next started", "paused resumed"), events)
            }
        }

    /** Work that fails during the pause ends with its error and leaves the slot free for the next request. */
    @Test
    fun workEndingDuringThePauseLeavesTheSlotFree() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val blocker = CompletableDeferred<Unit>()
                val held =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            gate.withPermit("paused", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                                withDownloadSlotReleased<Unit> { throw IOException("pause outlasted its grace") }
                            }
                        }.exceptionOrNull()
                    }
                val failure = held.await()
                val next =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("next", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            blocker.complete(Unit)
                        }
                    }
                next.await()

                assertTrue("$failure", failure is IOException)
                assertTrue("the slot was free for the next request", blocker.isCompleted)
            }
        }

    /** Outside any gate permit, releasing a slot simply runs the work. */
    @Test
    fun withoutAHeldSlotTheWorkJustRuns() =
        runBlocking {
            assertEquals("ran", withDownloadSlotReleased { "ran" })
        }

    private companion object {
        const val ACCOUNT = "account-a"
        const val TEST_TIMEOUT = 5_000L
    }
}
