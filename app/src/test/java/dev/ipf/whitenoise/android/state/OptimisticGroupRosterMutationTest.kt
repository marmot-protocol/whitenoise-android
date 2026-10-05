package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticGroupRosterMutationTest {
    @Test
    fun removalProjectsImmediatelyAndRollbackRevealsLatestAuthoritativeRoster() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val releaseCommit = CompletableDeferred<Unit>()
            var authoritative = listOf(member("alice"), member("bob"))

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        tracker.track(OptimisticGroupRosterMutation.Remove("BOB")) {
                            releaseCommit.await()
                            error("rejected")
                        }
                    }
                }

            assertEquals(listOf("alice"), projectedGroupMembers(authoritative, tracker.current).map { it.memberIdHex })
            assertEquals(1, projectedGroupMembers(authoritative, tracker.current).size)

            // MDK publishes a newer roster while the local commit is pending.
            // The overlay must apply to that roster rather than holding a stale
            // pre-mutation snapshot for rollback.
            authoritative = listOf(member("alice"), member("bob"), member("carol"))
            assertEquals(
                listOf("alice", "carol"),
                projectedGroupMembers(authoritative, tracker.current).map { it.memberIdHex },
            )

            releaseCommit.complete(Unit)
            assertTrue(result.await().isFailure)
            assertNull(tracker.current)
            assertEquals(
                listOf("alice", "bob", "carol"),
                projectedGroupMembers(authoritative, tracker.current).map { it.memberIdHex },
            )
        }

    @Test
    fun adminBadgeChangesWhileCommitIsPendingAndSettlesOnAuthoritativeState() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val releaseCommit = CompletableDeferred<Unit>()
            var authoritativeAdmin = false

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(OptimisticGroupRosterMutation.SetAdmin("bob", admin = true)) {
                        releaseCommit.await()
                        authoritativeAdmin = true
                    }
                }

            assertTrue(projectedGroupAdmin(authoritativeAdmin, "BOB", tracker.current))
            assertNull(tracker.pendingMembershipActivity)

            releaseCommit.complete(Unit)
            result.await()
            assertNull(tracker.current)
            assertTrue(projectedGroupAdmin(authoritativeAdmin, "bob", tracker.current))
        }

    @Test
    fun failedInviteShowsPendingTargetsOnlyUntilTheAttemptCompletes() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val releaseCommit = CompletableDeferred<Unit>()
            val refs = listOf("bob", "carol")

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        tracker.track(OptimisticGroupRosterMutation.Invite(refs)) {
                            releaseCommit.await()
                            error("offline")
                        }
                    }
                }

            assertEquals(refs, pendingGroupInviteRefs(emptyList(), tracker.current))
            assertFalse(projectedGroupAdmin(false, "bob", tracker.current))

            // Once detailed MDK state includes one target, that person renders
            // as a real member and must not also remain as a pending row.
            assertEquals(
                listOf("carol"),
                pendingGroupInviteRefs(listOf(member("BOB")), tracker.current),
            )

            releaseCommit.complete(Unit)
            assertTrue(result.await().isFailure)
            assertEquals(emptyList<String>(), pendingGroupInviteRefs(emptyList(), tracker.current))
        }

    @Test
    fun inviteReferencesAreCanonicalizedOnceAndDeduplicatedBeforeProjection() =
        runBlocking {
            val resolutions = mutableListOf<String>()
            val canonicalRefs =
                canonicalGroupInviteRefs(listOf(" npub-bob ", "bob-label", "npub-carol", "npub-carol")) { ref ->
                    resolutions += ref
                    when (ref) {
                        "npub-bob", "bob-label" -> "BOB"
                        "npub-carol" -> "carol"
                        else -> null
                    }
                }

            assertEquals(listOf("npub-bob", "bob-label", "npub-carol"), resolutions)
            assertEquals(listOf("BOB", "carol"), canonicalRefs)
            assertEquals(
                listOf("carol"),
                pendingGroupInviteRefs(
                    authoritativeMembers = listOf(member("bob")),
                    mutation = OptimisticGroupRosterMutation.Invite(canonicalRefs),
                ),
            )
        }

    @Test
    fun transcriptPendingInviteBelongsToTheSuspendedNativeAttempt() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val release = CompletableDeferred<Unit>()
            val mutation = OptimisticGroupRosterMutation.Invite(listOf("bob", "carol"))
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(mutation) { release.await() }
                }
            val activity = requireNotNull(tracker.pendingMembershipActivity)
            assertEquals(mutation, activity.mutation)
            assertTrue(activity.id.isNotBlank())
            assertEquals(activity.id, tracker.pendingMembershipActivity?.id)
            release.complete(Unit)
            result.await()
            assertNull(tracker.pendingMembershipActivity)
        }

    @Test
    fun anEarlierResultCannotClearOrSettleANewerMembershipAction() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val firstRelease = CompletableDeferred<Unit>()
            val nextRelease = CompletableDeferred<Unit>()
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(OptimisticGroupRosterMutation.Remove("bob")) { firstRelease.await() }
                }
            val oldId = requireNotNull(tracker.pendingMembershipActivity).id
            val next =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(OptimisticGroupRosterMutation.Remove("bob")) { nextRelease.await() }
                }
            val newId = requireNotNull(tracker.pendingMembershipActivity).id
            assertFalse(oldId == newId)
            tracker.settleMembershipActivity(oldId)
            firstRelease.complete(Unit)
            first.await()
            assertEquals(newId, tracker.pendingMembershipActivity?.id)
            nextRelease.complete(Unit)
            next.await()
            assertNull(tracker.pendingMembershipActivity)
        }

    @Test
    fun acceptedInviteStopsShowingPendingWhileOptionalPromotionIsStillSuspended() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            val promotion = CompletableDeferred<Unit>()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(OptimisticGroupRosterMutation.Invite(listOf("bob"))) {
                        tracker.settleMembershipActivity(tracker.pendingMembershipActivity?.id)
                        promotion.await()
                    }
                }
            assertNull(tracker.pendingMembershipActivity)
            assertEquals(OptimisticGroupRosterMutation.Invite(listOf("bob")), tracker.current)
            promotion.complete(Unit)
            result.await()
            assertNull(tracker.current)
        }

    @Test
    fun cancelledMembershipActionClearsFeedbackWithoutRunningTheCallAgain() =
        runBlocking {
            val tracker = OptimisticGroupRosterMutationTracker()
            var calls = 0
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    tracker.track(OptimisticGroupRosterMutation.Remove("bob")) {
                        calls++
                        CompletableDeferred<Unit>().await()
                    }
                }
            result.cancel()
            result.join()
            assertEquals(1, calls)
            assertNull(tracker.pendingMembershipActivity)
            assertNull(tracker.current)
        }

    private fun member(id: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = id,
            account = null,
            local = false,
        )
}
