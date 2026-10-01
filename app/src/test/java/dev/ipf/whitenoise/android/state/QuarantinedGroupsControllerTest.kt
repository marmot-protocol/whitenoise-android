package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantinedGroupsControllerTest {
    @Test fun sortedLoadAndConfirmedEmptyAreDifferentFromUnavailable() =
        runTest {
            val access = Access().apply { rows = listOf(row("b"), row("a")) }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            assertEquals(
                listOf("a", "b"),
                controller.state.value.rows
                    .map { it.groupId },
            )
            assertTrue(controller.state.value.loaded)
            access.rows = emptyList()
            controller.refresh()
            assertTrue(controller.state.value.loaded)
            assertTrue(
                controller.state.value.rows
                    .isEmpty(),
            )
            controller.close()
            val unavailable = QuarantinedGroupsController(null, this)
            unavailable.refresh()
            assertFalse(unavailable.state.value.available)
            assertFalse(unavailable.state.value.loaded)
            unavailable.close()
        }

    @Test fun loadFailureDoesNotBecomeConfirmedEmpty() =
        runTest {
            val access = Access().apply { loadFailure = true }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            assertTrue(controller.state.value.loadFailed)
            assertFalse(controller.state.value.loaded)
            assertFalse(controller.state.value.busy)
            controller.close()
        }

    @Test fun recoveredOutcomeSurvivesFailedReload() =
        runTest {
            val access = Access().apply { rows = listOf(row("a")) }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            access.loadFailure = true
            controller.recover("a")
            assertEquals(listOf("a"), access.retries)
            assertEquals(QuarantineRecoveryOutcome.Recovered, controller.state.value.outcome)
            assertTrue(
                controller.state.value.rows
                    .isEmpty(),
            )
            assertTrue(controller.state.value.loadFailed)
            controller.close()
        }

    @Test fun falseRecoveryKeepsRowAndReloadsNativeState() =
        runTest {
            val access =
                Access().apply {
                    rows = listOf(row("a"))
                    recovered = false
                }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            controller.recover("a")
            assertEquals(QuarantineRecoveryOutcome.StillQuarantined, controller.state.value.outcome)
            assertEquals(2, access.loads)
            assertEquals(listOf(row("a")), controller.state.value.rows)
            controller.close()
        }

    @Test fun retryFailureIsSanitizedAndStillReloads() =
        runTest {
            val access =
                Access().apply {
                    rows = listOf(row("a"))
                    retryFailure = true
                }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            controller.recover("a")
            assertEquals(QuarantineRecoveryOutcome.Failed, controller.state.value.outcome)
            assertEquals(2, access.loads)
            assertFalse(controller.state.value.busy)
            controller.close()
        }

    @Test fun pendingRetryRejectsDuplicatesAndCoalescesRefreshes() =
        runTest {
            val access = Access().apply { rows = listOf(row("a")) }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            access.retryGate = CompletableDeferred()
            controller.recover("a")
            controller.recover("a")
            repeat(20) { controller.refresh() }
            assertEquals(listOf("a"), access.retries)
            assertEquals(1, access.loads)
            access.retryGate!!.complete(Unit)
            runCurrent()
            assertEquals(2, access.loads)
            assertEquals(QuarantineRecoveryOutcome.Recovered, controller.state.value.outcome)
            controller.close()
        }

    @Test fun replacementAndDisposalCannotPublishLateLoad() =
        runTest {
            val access =
                Access().apply {
                    rows = listOf(row("private"))
                    loadGate = CompletableDeferred()
                }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            access.current = false
            access.loadGate!!.complete(Unit)
            runCurrent()
            assertFalse(controller.state.value.loaded)
            assertFalse(controller.state.value.available)
            assertFalse(controller.state.value.busy)
            assertTrue(
                controller.state.value.rows
                    .isEmpty(),
            )
            controller.close()
            assertTrue(access.closed)
            controller.refresh()
            controller.recover("private")
            assertEquals(1, access.loads)
            assertTrue(access.retries.isEmpty())
        }

    @Test fun lostOwnerEndsRetryProgressWithoutPublishingItsOutcome() =
        runTest {
            val access = Access().apply { rows = listOf(row("private")) }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            access.retryGate = CompletableDeferred()
            controller.recover("private")
            assertTrue(controller.state.value.busy)
            access.current = false
            access.retryGate!!.complete(Unit)
            runCurrent()
            assertFalse(controller.state.value.available)
            assertFalse(controller.state.value.busy)
            assertTrue(
                controller.state.value.rows
                    .isEmpty(),
            )
            assertEquals(null, controller.state.value.outcome)
            assertEquals(1, access.loads)
            controller.close()
        }

    @Test fun repeatedRefreshDuringLoadAddsOnlyOneRead() =
        runTest {
            val access = Access().apply { loadGate = CompletableDeferred() }
            val controller = QuarantinedGroupsController(access, this)
            controller.refresh()
            repeat(20) { controller.refresh() }
            assertEquals(1, access.loads)
            access.loadGate!!.complete(Unit)
            runCurrent()
            assertEquals(2, access.loads)
            controller.close()
        }

    private fun row(id: String) = QuarantinedGroupRow(id, QuarantinedGroupReason.StoredState)

    private class Access : QuarantinedGroupsAccess {
        var current = true
        var closed = false
        var rows = emptyList<QuarantinedGroupRow>()
        var loads = 0
        var loadFailure = false
        var retryFailure = false
        var recovered = true
        var loadGate: CompletableDeferred<Unit>? = null
        var retryGate: CompletableDeferred<Unit>? = null
        val retries = mutableListOf<String>()

        override fun isCurrent() = current && !closed

        override suspend fun load(): List<QuarantinedGroupRow> {
            loads++
            loadGate?.await()
            if (loadFailure) error("private native details")
            return rows
        }

        override suspend fun retry(groupId: String): Boolean {
            retries.add(groupId)
            retryGate?.await()
            if (retryFailure) error("private native details")
            if (recovered) rows = rows.filterNot { it.groupId == groupId }
            return recovered
        }

        override fun close() {
            closed = true
        }
    }
}
