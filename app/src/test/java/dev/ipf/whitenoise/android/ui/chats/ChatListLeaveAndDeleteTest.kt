package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the accepted batch boundary; the native engine remains authoritative on every retry. */
class ChatListLeaveAndDeleteTest {
    private val member = AppGroupMemberRecordFfi(memberIdHex = "successor", account = "local", local = false)

    private fun target(
        id: String,
        dm: Boolean = false,
    ) = ChatDepartureTarget(id, id, dm)

    @Test
    fun allHandoverDecisionsPrecedeMutationAndCancellingOneDoesNotStopOthers() =
        runTest {
            val log = mutableListOf<String>()
            val result =
                runChatDepartureBatch(
                    listOf(target("ordinary"), target("admin"), target("skip"), target("dm", true)),
                    { true },
                    ChatDepartureCallbacks(
                        {
                            log += "check:$it"
                            if (it == "ordinary") emptyList() else listOf(member)
                        },
                        { item, _ ->
                            log += "choose:${item.groupId}"
                            if (item.groupId == "skip") null else member
                        },
                        { item, successor ->
                            log += "remove:${item.groupId}:${successor?.memberIdHex}"
                            true
                        },
                    ),
                )
            assertEquals(
                listOf(
                    "check:ordinary",
                    "check:admin",
                    "choose:admin",
                    "check:skip",
                    "choose:skip",
                    "remove:ordinary:null",
                    "remove:admin:successor",
                    "remove:dm:null",
                ),
                log,
            )
            assertEquals(3, result.completed)
            assertEquals(1, result.skipped)
            assertTrue(result.outstanding.isEmpty())
        }

    @Test
    fun rosterFailureCannotFallBackToOrdinaryLeaveAndUnconfirmedRemovalRemainsRetryable() =
        runTest {
            val removed = mutableListOf<String>()
            val result =
                runChatDepartureBatch(
                    listOf(
                        target("roster-error"),
                        target("unconfirmed"),
                        target("already-left"),
                        target("last-member"),
                    ),
                    { true },
                    ChatDepartureCallbacks(
                        { if (it == "roster-error") error("native roster unavailable") else emptyList() },
                        { _, _ -> error("no successor should be requested") },
                        { item, _ ->
                            removed += item.groupId
                            item.groupId != "unconfirmed"
                        },
                    ),
                )
            assertFalse("roster-error" in removed)
            assertEquals(setOf("roster-error", "unconfirmed"), result.outstanding)
            assertEquals(2, result.completed)
        }

    @Test
    fun staleAccountStopsUnstartedItemsAndNeverCountsTheLateResult() =
        runTest {
            var current = true
            val removed = mutableListOf<String>()
            val result =
                runChatDepartureBatch(
                    listOf(target("first"), target("second")),
                    { current },
                    ChatDepartureCallbacks(
                        { emptyList() },
                        { _, _ -> null },
                        { item, _ ->
                            removed += item.groupId
                            current = false
                            true
                        },
                    ),
                )
            assertEquals(listOf("first"), removed)
            assertEquals(0, result.completed)
            assertEquals(setOf("first", "second"), result.outstanding)
        }

    @Test
    fun retryReconcilesAcceptedHandoverInsteadOfReplayingTheGrant() =
        runTest {
            var granted = false
            var leaveConfirmed = false
            var cleanupComplete = false
            var grants = 0
            var leaves = 0
            val targets = listOf(target("group"))

            suspend fun attempt() =
                runChatDepartureBatch(
                    targets,
                    { true },
                    ChatDepartureCallbacks(
                        { if (granted || leaveConfirmed) emptyList() else listOf(member) },
                        { _, candidates -> candidates.single() },
                        { _, successor ->
                            if (successor != null) {
                                grants++
                                granted = true
                            }
                            if (!leaveConfirmed) {
                                leaves++
                                leaveConfirmed = true
                            }
                            cleanupComplete
                        },
                    ),
                )
            assertEquals(setOf("group"), attempt().outstanding)
            cleanupComplete = true
            assertEquals(1, attempt().completed)
            assertEquals(1, grants)
            assertEquals(1, leaves)
        }

    @Test
    fun ownTimeoutIsUnfinishedButStructuralCancellationIsPropagated() =
        runTest {
            val result =
                runChatDepartureBatch(
                    listOf(target("slow")),
                    { true },
                    ChatDepartureCallbacks(
                        { awaitCancellation() },
                        { _, _ -> null },
                        { _, _ -> error("must not mutate") },
                    ),
                )
            assertEquals(setOf("slow"), result.outstanding)
            var cancelled = false
            val job =
                launch {
                    try {
                        runChatDepartureBatch(
                            listOf(target("cancelled")),
                            { true },
                            ChatDepartureCallbacks(
                                { awaitCancellation() },
                                { _, _ -> null },
                                { _, _ -> false },
                            ),
                        )
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                }
            runCurrent()
            job.cancel()
            job.join()
            assertTrue(cancelled)
        }
}
