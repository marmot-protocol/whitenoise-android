package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.OLD_WELCOME
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.appState
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.chatListRow
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.group
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.memberRoster
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.memberSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises native Join results and independently delivered confirmation snapshots (#2567). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class InviteConfirmationReconciliationTest {
    private val owners = InviteAcceptanceOwnerFixtures()

    /** Releases all controller-owned jobs, including post-accept work. */
    @After
    fun release() = owners.release()

    /** Neither a delayed pending snapshot nor a later roster refresh can undo confirmed acceptance. */
    @Test
    fun delayedPendingSnapshotAfterSuccessfulJoinStaysAccepted() =
        runTest {
            val controller = owners.controller(appState(), { _, _ -> accepted() })
            assertTrue(controller.acceptInvite(notify = false))
            controller.applyGroupStateForTest(pending().copy(name = "Updated title"))
            controller.retryMembers()
            assertFalse(controller.group.pendingConfirmation)
            assertEquals("Updated title", controller.group.name)
            assertTrue(controller.canSendMessages)
        }

    /** Same-generation metadata can cross a successful Join without being replaced by its older result. */
    @Test
    fun pendingSnapshotDuringNativeJoinKeepsOptimismAndSettlesSuccess() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                owners.controller(appState(), { _, _ ->
                    started.complete(Unit)
                    release.await()
                    accepted()
                })
            val acceptance = async { controller.acceptInvite(notify = false) }
            started.await()
            controller.applyGroupStateForTest(pending().copy(name = "New metadata"))
            assertFalse(controller.group.pendingConfirmation)
            release.complete(Unit)
            assertTrue(acceptance.await())
            assertFalse(controller.group.pendingConfirmation)
            assertEquals("New metadata", controller.group.name)
        }

    /** A roster proves MLS membership, not consent: native failure restores the latest pending record. */
    @Test
    fun failedJoinAfterSnapshotAndRosterRestoresPendingWithoutLosingMetadata() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                owners.controller(appState(), { _, _ ->
                    started.complete(Unit)
                    release.await()
                    error("permanent test failure")
                })
            val acceptance = async { controller.acceptInvite(notify = false) }
            started.await()
            controller.applyGroupStateForTest(pending().copy(name = "New metadata"))
            controller.retryMembers()
            assertFalse(controller.group.pendingConfirmation)
            release.complete(Unit)
            assertFalse(acceptance.await())
            assertTrue(controller.group.pendingConfirmation)
            assertEquals("New metadata", controller.group.name)
        }

    /** Cancellation cannot undo acceptance already observed on the canonical group subscription. */
    @Test
    fun cancellationAfterCanonicalAcceptanceDoesNotRestoreInvite() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                owners.controller(appState(), { _, _ ->
                    started.complete(Unit)
                    release.await()
                    accepted()
                })
            val acceptance = async { controller.acceptInvite(notify = false) }
            started.await()
            controller.applyGroupStateForTest(accepted())
            acceptance.cancelAndJoin()
            controller.applyGroupStateForTest(pending())
            assertFalse(controller.group.pendingConfirmation)
        }

    /** An unrelated same-Welcome refresh must not abandon a safe retry after runtime contention. */
    @Test
    fun sameWelcomeMetadataDuringBackoffStillRetriesAndConfirms() =
        runTest {
            var attempts = 0
            val controller =
                owners.controller(appState(), { _, _ ->
                    attempts += 1
                    if (attempts == 1) throw MarmotKitException.RuntimeBusy()
                    accepted()
                })
            val acceptance = async { controller.acceptInvite(notify = false) }
            runCurrent()
            assertEquals(1, attempts)
            controller.applyGroupStateForTest(pending().copy(name = "Refreshed during retry"))
            advanceTimeBy(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
            runCurrent()
            assertTrue(acceptance.await())
            assertEquals(2, attempts)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals("Refreshed during retry", controller.group.name)
        }

    /** Native success cannot revive a group frozen while the call was suspended. */
    @Test
    fun freezeDuringJoinInvalidatesItsSuccessfulResult() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                owners.controller(appState(), { _, _ ->
                    started.complete(Unit)
                    release.await()
                    accepted()
                })
            val acceptance = async { controller.acceptInvite(notify = false) }
            started.await()
            controller.applyGroupStateForTest(pending().copy(unrecoverable = true))
            release.complete(Unit)
            assertFalse(acceptance.await())
            assertTrue(controller.group.unrecoverable)
        }

    /** A native snapshot resolves the stale row before normal Join/Decline or send can run. */
    @Test
    fun conflictingPendingRowResolvesAcceptedGroupWithoutJoiningAgain() =
        runTest {
            assertConflictingProjectionResolution(rowPending = true, canonical = accepted())
        }

    /** An older accepted row must not authorize a newer unaccepted Welcome. */
    @Test
    fun conflictingAcceptedRowPreservesGenuinePendingInvite() =
        runTest {
            assertConflictingProjectionResolution(rowPending = false, canonical = pending())
        }

    /** Failed reads retain the conflict and retry the canonical snapshot with proper handle cleanup. */
    @Test
    fun failedConflictReadRetriesWithoutUsingRosterAsConfirmation() =
        runTest {
            val item = chatListItemFromProjection(chatListRow(pending = true), group = accepted())
            var available = false
            var closed = 0
            val controller =
                conflictController(item) {
                    object : ConversationGroupStateSubscriptionHandle {
                        override fun snapshot(): AppGroupRecordFfi {
                            check(available) { "unavailable" }
                            return accepted()
                        }

                        override suspend fun next(): AppGroupRecordFfi? = null

                        override fun close() {
                            closed += 1
                        }
                    }
                }
            controller.retryInviteAcceptanceAuthority()
            assertTrue(controller.inviteAcceptanceResolutionPending)
            assertFalse(controller.canSendMessages)
            assertEquals(GroupRosterLoadState.FAILED, controller.memberRosterState)
            assertEquals(1, closed)
            available = true
            controller.retryInviteAcceptanceAuthority()
            assertFalse(controller.inviteAcceptanceResolutionPending)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(2, closed)
        }

    /** Unknown generation identity cannot establish a sticky acceptance proof. */
    @Test
    fun missingWelcomeIdentityWaitsForLaterCanonicalState() {
        val authority = InviteConfirmationAuthority(accepted().copy(viaWelcomeMessageIdHex = null))
        authority.reconcile(accepted().copy(viaWelcomeMessageIdHex = null))
        assertTrue(authority.reconcile(pending().copy(viaWelcomeMessageIdHex = null)).pendingConfirmation)
    }

    /** Tests both conflicting directions and proves that resolving the row never invokes native Join. */
    private suspend fun assertConflictingProjectionResolution(
        rowPending: Boolean,
        canonical: AppGroupRecordFfi,
    ) {
        val item = chatListItemFromProjection(chatListRow(pending = rowPending), group = canonical)
        assertTrue(item.inviteConfirmationUnresolved)
        val controller = conflictController(item) { ScriptedConversationGroupStateSubscription(canonical) }
        assertTrue(controller.inviteAcceptanceResolutionPending)
        assertFalse(controller.group.pendingConfirmation)
        assertFalse(controller.canSendMessages)
        assertFalse(controller.acceptInvite(notify = false))
        assertFalse(controller.declineInvite())
        controller.retryInviteAcceptanceAuthority()
        assertFalse(controller.inviteAcceptanceResolutionPending)
        assertEquals(canonical.pendingConfirmation, controller.group.pendingConfirmation)
    }

    /** Builds a conflict controller whose only native authority is the injected subscription. */
    private fun conflictController(
        item: ChatListItem,
        open: suspend () -> ConversationGroupStateSubscriptionHandle,
    ): ConversationController {
        val app = appState()
        app.liveSubscriptionOverrides.conversation =
            ConversationLiveSubscriptions(
                openTimeline = { _, _, _ -> error("unexpected timeline") },
                openGroupState = { _, _ -> open() },
            )
        return owners.track(
            ConversationController(
                appState = app,
                initialGroup = item.group,
                initialMemberSnapshot = memberSnapshot(),
                initialChatListRow = item.projection,
                initialInviteConfirmationUnresolved = item.inviteConfirmationUnresolved,
                inviteAcceptor = { _, _ -> error("unexpected Join") },
                groupRosterReader = { _, _ -> memberRoster() },
            ),
        )
    }

    /** Stable canonical invitation identity shared by both sides of each race. */
    private fun pending() = group(pending = true, welcome = OLD_WELCOME)

    /** The native confirmation response for that exact Welcome. */
    private fun accepted() = group(pending = false, welcome = OLD_WELCOME)
}
