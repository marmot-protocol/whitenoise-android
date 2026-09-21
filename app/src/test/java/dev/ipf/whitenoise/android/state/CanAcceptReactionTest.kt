package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
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

    /** An immediate removal remains suspended only until the preceding add returns its event id. */
    @Test
    fun immediateRemovalWaitsForReactionAddResult() =
        runTest {
            val addResult = CompletableDeferred<String?>()
            val removal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitImmediateReactionEventId(addResult) { null }
                }

            assertFalse("removal must not race the in-flight add", removal.isCompleted)
            addResult.complete("reaction-event")

            assertEquals("reaction-event", removal.await())
        }

    /** A completed add can hand off its cached id after leaving the in-flight registry. */
    @Test
    fun immediateRemovalUsesCachedReactionEventId() =
        runTest {
            assertEquals(
                "cached-reaction-event",
                awaitImmediateReactionEventId(precedingAdd = null) { "cached-reaction-event" },
            )
        }

    /** A failed add hands null to the queued removal so no unrelated event can be deleted. */
    @Test
    fun failedReactionAddDoesNotProduceRetractionEventId() =
        runTest {
            val addResult = CompletableDeferred<String?>()
            addResult.complete(null)

            assertEquals(null, awaitImmediateReactionEventId(addResult) { "stale-event" })
        }

    /** A projected reaction event keeps removal scoped to the tapped emoji. */
    @Test
    fun ownReactionRetractionDeletesKnownReactionEvent() {
        assertEquals(
            OwnReactionRetractionPlan.DeleteReactionMessage("reaction-event"),
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = mapOf("👍" to "reaction-event"),
                ownEmojisBeforeMutation = setOf("👍", "🔥"),
            ),
        )
    }

    /** A sole own reaction can use target-wide unreact while its event id is still missing. */
    @Test
    fun soleOwnReactionFallsBackToTargetUnreactBeforeProjectionEcho() {
        assertEquals(
            OwnReactionRetractionPlan.UnreactTarget,
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = emptyMap(),
                ownEmojisBeforeMutation = setOf("👍"),
            ),
        )
    }

    /** A sole own reaction uses the dedicated unreact API even after its event id projects. */
    @Test
    fun soleOwnReactionPrefersTargetUnreactWithKnownEventId() {
        assertEquals(
            OwnReactionRetractionPlan.UnreactTarget,
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = mapOf("👍" to "reaction-event"),
                ownEmojisBeforeMutation = setOf("👍"),
            ),
        )
    }

    /** An immediate removal deletes the exact just-created event without waiting for projection. */
    @Test
    fun immediateRemovalPrefersReturnedReactionEvent() {
        assertEquals(
            OwnReactionRetractionPlan.DeleteReactionMessage("new-reaction-event"),
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = emptyMap(),
                ownEmojisBeforeMutation = setOf("👍"),
                preferredEventId = "new-reaction-event",
            ),
        )
    }

    /** Multiple own reactions never use target-wide unreact without the tapped event id. */
    @Test
    fun multipleOwnReactionsWithoutEventIdRemainUnavailable() {
        assertEquals(
            OwnReactionRetractionPlan.Unavailable,
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = emptyMap(),
                ownEmojisBeforeMutation = setOf("👍", "🔥"),
            ),
        )
    }

    /** Raw history chooses the newest matching reaction while projection catches up. */
    @Test
    fun rawHistoryFindsNewestActiveOwnReaction() {
        val records =
            listOf(
                rawRecord(id = "older-reaction", kind = 7uL, plaintext = "👍", target = TARGET, recordedAt = 1uL),
                rawRecord(id = "other-emoji", kind = 7uL, plaintext = "🔥", target = TARGET, recordedAt = 2uL),
                rawRecord(id = "newer-reaction", kind = 7uL, plaintext = "👍", target = TARGET, recordedAt = 3uL),
            )

        assertEquals(
            "newer-reaction",
            activeOwnReactionEventId(records, ACCOUNT, TARGET, "👍"),
        )
    }

    /** An own delete suppresses its reaction event so stale ids are never retried. */
    @Test
    fun rawHistoryIgnoresDeletedOwnReaction() {
        val records =
            listOf(
                rawRecord(id = "reaction-event", kind = 7uL, plaintext = "👍", target = TARGET, recordedAt = 1uL),
                rawRecord(id = "delete-event", kind = 5uL, target = "reaction-event", recordedAt = 2uL),
            )

        assertEquals(null, activeOwnReactionEventId(records, ACCOUNT, TARGET, "👍"))
    }

    /** A different sender cannot forge deletion of the active account's reaction. */
    @Test
    fun rawHistoryIgnoresForgedReactionDelete() {
        val records =
            listOf(
                rawRecord(id = "reaction-event", kind = 7uL, plaintext = "👍", target = TARGET, recordedAt = 1uL),
                rawRecord(
                    id = "forged-delete",
                    sender = "another-account",
                    kind = 5uL,
                    target = "reaction-event",
                    recordedAt = 2uL,
                ),
            )

        assertEquals(
            "reaction-event",
            activeOwnReactionEventId(records, ACCOUNT, TARGET, "👍"),
        )
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

    /** Builds a minimal raw Marmot record for reaction-resolution tests. */
    private fun rawRecord(
        id: String,
        sender: String = ACCOUNT,
        kind: ULong,
        plaintext: String = "",
        target: String,
        recordedAt: ULong,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = "sent",
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = kind,
        tags = listOf(MessageTagFfi(listOf("e", target))),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )

    private companion object {
        const val ACCOUNT = "abcdef"
        const val TARGET = "message-id"
    }
}
