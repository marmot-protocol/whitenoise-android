package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                yield()
                assertEquals("the resumed holder waits for a free slot", 2, events.size)
                secondDone.complete(Unit)
                listOf(paused, next).awaitAll()

                assertEquals(listOf("paused started", "next started", "paused resumed"), events)
            }
        }

    /** Work that fails during the pause ends with its error and leaves no permit held behind it. */
    @Test
    fun workFailingDuringThePauseHoldsNoPermit() =
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

    /** A paused holder still counts as admitted, so a tap on it still escalates the native transfer. */
    @Test
    fun aPausedHolderStillCountsAsAdmitted() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                var admittedDuringPause = false
                gate.withPermit("paused", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                    withDownloadSlotReleased { admittedDuringPause = gate.isAdmitted("paused") }
                }

                assertTrue(admittedDuringPause)
                assertFalse("the finished request is no longer admitted", gate.isAdmitted("paused"))
            }
        }

    /** A pause that ends with nothing left to transfer finishes at once instead of queuing behind other downloads. */
    @Test
    fun aPauseThatEndsTheWorkDoesNotQueueAgain() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val otherRunning = CompletableDeferred<Unit>()
                val otherDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            withDownloadSlotReleased(requeueAfter = { false }) { pauseEnds.await() }
                        }
                    }
                val other =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("other", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            otherRunning.complete(Unit)
                            otherDone.await()
                        }
                    }
                otherRunning.await()
                pauseEnds.complete(Unit)
                paused.await()

                assertTrue("the other download still holds the only slot", other.isActive)
                otherDone.complete(Unit)
                other.await()
            }
        }

    /**
     * A visible request that queues again after its pause keeps its original place: a newer visible request still
     * goes first, and background work still goes after it.
     */
    @Test
    fun aRequeuedVisibleRequestKeepsItsPlace() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val started = mutableListOf<String>()
                val pauseEnds = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val older =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("older", ACCOUNT, AUTOMATIC, foreground = true) {
                            withDownloadSlotReleased { pauseEnds.await() }
                            started += "older"
                        }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                val queued =
                    listOf(
                        startRecording(gate, "background", started),
                        startRecording(gate, "newer", started, foreground = true),
                    )
                pauseEnds.complete(Unit)
                yield()
                holderDone.complete(Unit)
                (queued + older + holder).awaitAll()

                assertEquals(listOf("newer", "older", "background"), started)
            }
        }

    /**
     * Work that fails during its pause drops a tap's promotion made meanwhile, so a later owner of the key does not
     * inherit it.
     */
    @Test
    fun workFailingDuringThePauseDropsATapMadeMeanwhile() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                runCatching {
                    gate.withPermit("image", ACCOUNT, AUTOMATIC) {
                        withDownloadSlotReleased<Unit> {
                            gate.promote("image")
                            throw IOException("pause outlasted its grace")
                        }
                    }
                }
                val started = mutableListOf<String>()
                val holderDone = CompletableDeferred<Unit>()
                val holder = startHolding(gate, "holder", holderDone)
                val queued = listOf(startRecording(gate, "background", started), startRecording(gate, "image", started))
                holderDone.complete(Unit)
                (queued + holder).awaitAll()

                assertEquals("the later owner queues as automatic work", listOf("background", "image"), started)
            }
        }

    /** Holds a slot under [key] until [done] completes. */
    private fun CoroutineScope.startHolding(
        gate: AttachmentDownloadGate,
        key: String,
        done: CompletableDeferred<Unit>,
    ): Deferred<Unit> =
        async(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(key, ACCOUNT, AUTOMATIC) { done.await() }
        }

    /** Queues a request under [key] that records its key in [started] once admitted. */
    private fun CoroutineScope.startRecording(
        gate: AttachmentDownloadGate,
        key: String,
        started: MutableList<String>,
        foreground: Boolean = false,
    ): Deferred<Unit> =
        async(start = CoroutineStart.UNDISPATCHED) {
            gate.withPermit(key, ACCOUNT, AUTOMATIC, foreground) { started += key }
        }

    /** Outside any gate permit, releasing a slot simply runs the work. */
    @Test
    fun withoutAHeldSlotTheWorkJustRuns() =
        runBlocking {
            assertEquals("ran", withDownloadSlotReleased { "ran" })
        }

    private companion object {
        const val ACCOUNT = "account-a"
        val AUTOMATIC = AttachmentDownloadPriority.Automatic
        const val TEST_TIMEOUT = 5_000L
    }
}
