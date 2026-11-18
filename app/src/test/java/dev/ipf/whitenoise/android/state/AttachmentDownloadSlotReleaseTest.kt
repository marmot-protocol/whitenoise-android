package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
    /**
     * While the holder waits out a pause, the next request runs. Afterwards the holder goes on with its work at once
     * and queues for a slot in the background, getting it back once the next request finishes.
     */
    @Test
    fun aPausedHolderLetsTheNextRequestRunThenQueuesInTheBackground() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val holderWorkDone = CompletableDeferred<Unit>()
                val nextDone = CompletableDeferred<Unit>()
                var resumedWithoutASlot = false
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) {
                            withDownloadSlotReleased { pauseEnds.await() }
                            resumedWithoutASlot = gate.isQueuedForTest("paused")
                            holderWorkDone.await()
                        }
                    }
                val next = startHolding(gate, "next", nextDone)
                assertTrue("the next request took the paused holder's slot", gate.isAdmitted("next"))
                pauseEnds.complete(Unit)
                yield()

                assertTrue("the holder went on while its slot request queued", resumedWithoutASlot)
                nextDone.complete(Unit)
                next.await()
                while (gate.isQueuedForTest("paused")) yield()
                assertTrue("the holder owns a slot again", gate.isAdmitted("paused"))
                holderWorkDone.complete(Unit)
                paused.await()
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

    /**
     * A transfer that resumed but is still queuing for a slot keeps counting as admitted, so a tap during that wait
     * still escalates it.
     */
    @Test
    fun aResumedHolderQueuingForASlotStillCountsAsAdmitted() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val workDone = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) {
                            withDownloadSlotReleased { pauseEnds.await() }
                            workDone.await()
                        }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                pauseEnds.complete(Unit)
                while (!gate.isQueuedForTest("paused")) yield()

                assertTrue("a queued resumed request still counts as admitted", gate.isAdmitted("paused"))
                holderDone.complete(Unit)
                holder.await()
                workDone.complete(Unit)
                paused.await()
                assertFalse(gate.isAdmitted("paused"))
            }
        }

    /**
     * Work that ends while its resumed request is still queuing returns at once: the queued request is withdrawn and
     * the key stops counting as admitted, while the other download keeps its slot.
     */
    @Test
    fun workEndingWhileItsSlotRequestQueuesWithdrawsTheRequest() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) { withDownloadSlotReleased { pauseEnds.await() } }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                pauseEnds.complete(Unit)
                paused.await()

                assertFalse("no request is left queuing", gate.isQueuedForTest("paused"))
                assertFalse(gate.isAdmitted("paused"))
                assertTrue("the other download still holds the only slot", holder.isActive)
                holderDone.complete(Unit)
                holder.await()
            }
        }

    /**
     * A transfer that pauses again while its slot request still queues stays admitted throughout, withdraws that
     * request, and queues again later with the tap's promotion it gained meanwhile, ahead of background work.
     */
    @Test
    fun aSecondPauseKeepsTheTapItsQueuedRequestGained() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val firstPauseEnds = CompletableDeferred<Unit>()
                val secondPauseEnds = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                var admittedDuringSecondPause = false
                var withdrawnDuringSecondPause = false
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) {
                            withDownloadSlotReleased { firstPauseEnds.await() }
                            gate.promote("paused")
                            withDownloadSlotReleased {
                                admittedDuringSecondPause = gate.isAdmitted("paused")
                                withdrawnDuringSecondPause = !gate.isQueuedForTest("paused")
                                secondPauseEnds.await()
                            }
                            while (gate.isQueuedForTest("paused")) yield()
                            started += "paused"
                        }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                firstPauseEnds.complete(Unit)
                yield()
                val background = startRecording(gate, "background", started)
                secondPauseEnds.complete(Unit)
                yield()
                holderDone.complete(Unit)
                listOf(paused, holder, background).awaitAll()

                assertTrue(admittedDuringSecondPause)
                assertTrue("the queued request was withdrawn for the second pause", withdrawnDuringSecondPause)
                assertEquals("the tapped request goes before background work", listOf("paused", "background"), started)
            }
        }

    /** Cancelling the work while its slot request queues leaks no permit and leaves the key unadmitted. */
    @Test
    fun cancellingWorkWhileItsSlotRequestQueuesLeaksNothing() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) {
                            withDownloadSlotReleased { pauseEnds.await() }
                            awaitCancellation()
                        }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                pauseEnds.complete(Unit)
                while (!gate.isQueuedForTest("paused")) yield()
                paused.cancelAndJoin()
                holderDone.complete(Unit)
                holder.await()
                val next = CompletableDeferred<Unit>()
                startHolding(gate, "next", next).also { next.complete(Unit) }.await()

                assertFalse(gate.isAdmitted("paused"))
                assertFalse(gate.isQueuedForTest("paused"))
            }
        }

    /**
     * Work cancelled as it hands over a second pause, while its first re-acquire still queues, stops counting the key
     * as admitted, so no later owner is mistaken for live native work.
     */
    @Test
    fun cancellingTheHandoverOfASecondPauseEndsIt() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) {
                            withDownloadSlotReleased { pauseEnds.await() }
                            while (!gate.isQueuedForTest("paused")) yield()
                            currentCoroutineContext().job.cancel()
                            withDownloadSlotReleased { error("a cancelled handover never runs the work") }
                        }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                pauseEnds.complete(Unit)
                runCatching { paused.await() }

                assertTrue(paused.isCancelled)
                assertFalse("the key no longer counts as admitted", gate.isAdmitted("paused"))
                assertFalse(gate.isQueuedForTest("paused"))
                holderDone.complete(Unit)
                holder.await()
            }
        }

    /**
     * Stopping automatic downloads withdraws a resumed transfer's queued slot request, but the transfer goes on, so
     * its key stays admitted until the work ends and a tap meanwhile still escalates it.
     */
    @Test
    fun stoppingAutomaticDownloadsKeepsAResumedTransferAdmitted() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val pauseEnds = CompletableDeferred<Unit>()
                val workDone = CompletableDeferred<Unit>()
                val holderDone = CompletableDeferred<Unit>()
                val paused =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("paused", ACCOUNT, AUTOMATIC) {
                            withDownloadSlotReleased { pauseEnds.await() }
                            workDone.await()
                        }
                    }
                val holder = startHolding(gate, "holder", holderDone)
                pauseEnds.complete(Unit)
                while (!gate.isQueuedForTest("paused")) yield()

                assertEquals("only the queued slot request is withdrawn", 1, gate.cancelQueuedAutomatic(ACCOUNT))
                yield()
                assertFalse(gate.isQueuedForTest("paused"))
                assertTrue("the transfer goes on, so its key stays admitted", gate.isAdmitted("paused"))

                workDone.complete(Unit)
                paused.await()
                assertFalse("the ended work no longer counts as admitted", gate.isAdmitted("paused"))
                holderDone.complete(Unit)
                holder.await()
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
                            // Its slot request queues in the background; record when it owns the slot again.
                            while (gate.isQueuedForTest("older")) yield()
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
