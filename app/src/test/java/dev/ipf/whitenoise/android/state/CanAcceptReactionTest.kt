package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageTagFfi
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

    /** A blank immediate-add result waits for local history instead of failing on its first stale read. */
    @Test
    fun missingImmediateAddIdWaitsForAuthoritativeHistory() =
        runTest {
            var reads = 0

            val resolved =
                awaitReactionEventHistory(expectedEmoji = "👍", retryDelayMillis = 0L) {
                    reads += 1
                    if (reads < 3) emptyMap() else mapOf("👍" to "reaction-event")
                }

            assertEquals(mapOf("👍" to "reaction-event"), resolved)
            assertEquals(3, reads)
        }

    /** A projected reaction event keeps removal scoped to the tapped emoji. */
    @Test
    fun ownReactionRetractionDeletesKnownReactionEvent() {
        assertEquals(
            OwnReactionRetractionPlan.DeleteReactionMessage("reaction-event"),
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = mapOf("👍" to "reaction-event"),
                authoritativeOwnEmojis = setOf("👍", "🔥"),
            ),
        )
    }

    /** An authoritatively sole own reaction can use target-wide unreact while its event id is missing. */
    @Test
    fun authoritativelySoleOwnReactionFallsBackToTargetUnreactBeforeProjectionEcho() {
        assertEquals(
            OwnReactionRetractionPlan.UnreactTarget,
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = emptyMap(),
                authoritativeOwnEmojis = setOf("👍"),
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
                authoritativeOwnEmojis = setOf("👍"),
            ),
        )
    }

    /** A stale sole projection deletes only the tapped event when history contains another emoji. */
    @Test
    fun authoritativeSecondEmojiPreventsTargetWideUnreact() {
        assertEquals(
            OwnReactionRetractionPlan.DeleteReactionMessage("thumb-event"),
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = mapOf("👍" to "thumb-event", "🔥" to "fire-event"),
                authoritativeOwnEmojis = setOf("👍", "🔥"),
            ),
        )
    }

    /** Failed authoritative lookup still prefers a known tapped event over target-wide unreact. */
    @Test
    fun unknownAuthoritativeStateDeletesKnownTappedEvent() {
        assertEquals(
            OwnReactionRetractionPlan.DeleteReactionMessage("thumb-event"),
            planOwnReactionRetraction(
                emoji = "👍",
                knownEventIdByEmoji = mapOf("👍" to "thumb-event"),
                authoritativeOwnEmojis = null,
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
                authoritativeOwnEmojis = null,
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
                authoritativeOwnEmojis = setOf("👍", "🔥"),
            ),
        )
    }

    /** Raw history exposes every active own emoji so sole-ness never relies on projection alone. */
    @Test
    fun rawHistoryFindsAllActiveOwnReactions() {
        val records =
            listOf(
                rawRecord(id = "thumb-event", kind = 7uL, plaintext = "👍", target = TARGET, recordedAt = 1uL),
                rawRecord(id = "fire-event", kind = 7uL, plaintext = "🔥", target = TARGET, recordedAt = 2uL),
            )

        assertEquals(
            mapOf("🔥" to "fire-event", "👍" to "thumb-event"),
            activeOwnReactionEventIdsByEmoji(records, ACCOUNT, TARGET),
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
