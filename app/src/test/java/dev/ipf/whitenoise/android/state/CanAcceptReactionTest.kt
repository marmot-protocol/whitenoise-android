package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
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

    /** Replacing one overlay makes every tap visible before any native mutation completes. */
    @Test
    fun latestReactionIntentRendersImmediately() {
        val optimistic = linkedMapOf<String, OptimisticReactionChange>()

        optimistic["intent"] = OptimisticReactionChange(TARGET, "👍", add = true)
        assertTrue(renderTallies(optimistic).single().mine)

        optimistic["intent"] = OptimisticReactionChange(TARGET, "👍", add = false)
        assertTrue(renderTallies(optimistic).isEmpty())
    }

    /** A rapid opposite tap returns to the original state without sending either native mutation. */
    @Test
    fun rapidOppositeReactionIntentsConflateBeforeCommit() =
        runTest {
            val key = TARGET to "👍"
            val conflator = ReactionIntentConflator()
            val commits = mutableListOf<Boolean>()
            val first = conflator.submit(key, desiredMine = true)
            val drain =
                async(start = CoroutineStart.UNDISPATCHED) {
                    drainReactionIntent(
                        key = key,
                        conflator = conflator,
                        initialMine = false,
                        settleDelayMillis = 1L,
                    ) { desiredMine ->
                        commits += desiredMine
                    }
                }

            assertTrue(first.shouldDrain)
            assertFalse(conflator.submit(key, desiredMine = false).shouldDrain)
            val outcome = drain.await() as ReactionIntentDrainOutcome.Settled

            assertTrue(commits.isEmpty())
            assertFalse(outcome.mutated)
            assertFalse(outcome.finalMine)
        }

    /** A failed operation that a newer tap superseded cannot surface a stale reaction error. */
    @Test
    fun supersededReactionFailureSettlesLatestIntentWithoutError() =
        runTest {
            val key = TARGET to "👍"
            val conflator = ReactionIntentConflator()
            val commitStarted = CompletableDeferred<Unit>()
            val releaseFailure = CompletableDeferred<Unit>()
            conflator.submit(key, desiredMine = true)
            val drain =
                async(start = CoroutineStart.UNDISPATCHED) {
                    drainReactionIntent(
                        key = key,
                        conflator = conflator,
                        initialMine = false,
                        settleDelayMillis = 0L,
                    ) {
                        commitStarted.complete(Unit)
                        releaseFailure.await()
                        error("superseded")
                    }
                }

            commitStarted.await()
            assertFalse(conflator.submit(key, desiredMine = false).shouldDrain)
            releaseFailure.complete(Unit)
            val outcome = drain.await() as ReactionIntentDrainOutcome.Settled

            assertFalse(outcome.finalMine)
            assertFalse(outcome.mutated)
        }

    /** A tap during an accepted add converges afterward without overlapping native mutations. */
    @Test
    fun inFlightReactionCommitConvergesToTheNewestIntent() =
        runTest {
            val key = TARGET to "👍"
            val conflator = ReactionIntentConflator()
            val addStarted = CompletableDeferred<Unit>()
            val releaseAdd = CompletableDeferred<Unit>()
            val commits = mutableListOf<Boolean>()
            conflator.submit(key, desiredMine = true)
            val drain =
                async(start = CoroutineStart.UNDISPATCHED) {
                    drainReactionIntent(
                        key = key,
                        conflator = conflator,
                        initialMine = false,
                        settleDelayMillis = 0L,
                    ) { desiredMine ->
                        commits += desiredMine
                        if (desiredMine) {
                            addStarted.complete(Unit)
                            releaseAdd.await()
                        }
                    }
                }

            addStarted.await()
            assertFalse(conflator.submit(key, desiredMine = false).shouldDrain)
            releaseAdd.complete(Unit)
            val outcome = drain.await() as ReactionIntentDrainOutcome.Settled

            assertEquals(listOf(true, false), commits)
            assertFalse(outcome.finalMine)
            assertTrue(outcome.mutated)
        }

    /** Native back-pressure retries while the same intent remains current. */
    @Test
    fun busyReactionMutationRetriesWithoutSurfacingFailure() =
        runTest {
            var attempts = 0

            val result =
                retryBusyReactionMutation(retryDelayMillis = 0L) {
                    attempts += 1
                    if (attempts < 3) throw MarmotKitException.RuntimeBusy()
                    "committed"
                }

            assertEquals("committed", result)
            assertEquals(3, attempts)
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
                references = null,
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
