package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for #1835 and #1840's reaction window during roster hydration. */
class CanAcceptReactionTest {
    @Test
    fun acceptsPositiveMembershipSeedBeforeLiveRosterHydrates() {
        assertTrue(
            acceptsReaction(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun rejectsVerifiedRemovalDespitePositiveSeed() {
        assertFalse(
            acceptsReaction(
                membersVerified = true,
                isSelfMember = false,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun rejectsLocalSelfLeaveDespitePositiveSeed() {
        assertFalse(
            acceptsReaction(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = true,
                selfLeft = true,
            ),
        )
    }

    @Test
    fun rejectsUnknownMembershipAndMissingAccount() {
        assertFalse(
            acceptsReaction(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = false,
            ),
        )
        assertFalse(acceptsReaction(accountRef = null))
    }

    @Test
    fun rejectsTerminalGroupStates() {
        assertFalse(acceptsReaction(unrecoverable = true))
        assertFalse(acceptsReaction(disbanding = true))
        assertFalse(acceptsReaction(disbanded = true))
    }

    @Test
    fun blockedCommitDoesNotDelayOptimisticReaction() =
        runTest {
            val releaseCommit = CompletableDeferred<Unit>()
            val optimistic = linkedMapOf<String, OptimisticReactionChange>()
            var renderedTallies = emptyList<ReactionTally>()
            var rollbackCount = 0

            val mutation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runOptimisticReactionMutation(
                        applyOptimistic = {
                            optimistic["pending"] = OptimisticReactionChange(TARGET, "👍", add = true)
                            renderedTallies = renderTallies(optimistic)
                        },
                        commit = {
                            releaseCommit.await()
                            true
                        },
                        rollback = {
                            optimistic.remove("pending")
                            renderedTallies = renderTallies(optimistic)
                            rollbackCount += 1
                        },
                    )
                }

            assertEquals(listOf("👍"), renderedTallies.map { it.emoji })
            assertTrue("the optimistic chip must belong to the active account", renderedTallies.single().mine)
            assertFalse("the engine commit should still be waiting", mutation.isCompleted)
            releaseCommit.complete(Unit)

            assertTrue(mutation.await().getOrThrow())
            assertEquals("a successful commit keeps the overlay until its echo", setOf("pending"), optimistic.keys)
            assertEquals(0, rollbackCount)
        }

    @Test
    fun failedCommitRollsBackOptimisticReaction() =
        runTest {
            val optimistic = linkedMapOf<String, OptimisticReactionChange>()
            var renderedTallies = emptyList<ReactionTally>()
            var rollbackCount = 0

            val mutation =
                runOptimisticReactionMutation(
                    applyOptimistic = {
                        optimistic["pending"] = OptimisticReactionChange(TARGET, "👍", add = true)
                        renderedTallies = renderTallies(optimistic)
                    },
                    commit = { error("relay unavailable") },
                    rollback = {
                        optimistic.remove("pending")
                        renderedTallies = renderTallies(optimistic)
                        rollbackCount += 1
                    },
                )

            assertTrue(mutation.isFailure)
            assertTrue(renderedTallies.isEmpty())
            assertEquals(1, rollbackCount)
        }

    @Test
    fun confirmedEchoPrunesOnlyItsMatchingOptimisticOverlay() {
        val optimistic =
            linkedMapOf(
                "mine" to OptimisticReactionChange(TARGET, "👍", add = true),
                "other" to OptimisticReactionChange(TARGET, "🔥", add = true),
            )
        val confirmed =
            mapOf(
                TARGET to
                    mapOf(
                        "👍" to setOf(ACCOUNT.uppercase()),
                        "🔥" to setOf("another-account"),
                    ),
            )

        val confirmedKeys =
            confirmedOptimisticReactionKeys(
                activeAccountIdHex = ACCOUNT,
                optimisticChanges = optimistic,
                confirmedSendersByTarget = confirmed,
            )

        assertEquals(setOf("mine"), confirmedKeys)
        confirmedKeys.forEach(optimistic::remove)
        assertEquals(setOf("other"), optimistic.keys)
        val tallies =
            reactionTalliesForSenders(
                activeAccountIdHex = ACCOUNT,
                confirmedSendersByEmoji = confirmed.getValue(TARGET),
                optimisticChanges = optimistic.values,
            )
        val confirmedTally = tallies.single { it.emoji == "👍" }
        assertEquals(1, confirmedTally.count)
        assertTrue("the authoritative tally remains after overlay pruning", confirmedTally.mine)
    }

    private fun acceptsReaction(
        accountRef: String? = "acct",
        membersVerified: Boolean = true,
        isSelfMember: Boolean = true,
        seededSelfMember: Boolean = false,
        selfLeft: Boolean = false,
        unrecoverable: Boolean = false,
        disbanding: Boolean = false,
        disbanded: Boolean = false,
    ): Boolean =
        canAcceptReaction(
            accountRef = accountRef,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            selfLeft = selfLeft,
            unrecoverable = unrecoverable,
            disbanding = disbanding,
            disbanded = disbanded,
        )

    private fun renderTallies(optimistic: Map<String, OptimisticReactionChange>) =
        reactionTalliesForSenders(
            activeAccountIdHex = ACCOUNT,
            confirmedSendersByEmoji = emptyMap(),
            optimisticChanges = optimistic.values,
        )

    private companion object {
        const val ACCOUNT = "abcdef"
        const val TARGET = "message-id"
    }
}
