package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.ACCOUNT
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.GROUP_ID
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.LATE_ACCEPTED_NAME
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.NEW_WELCOME
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.OLD_WELCOME
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.OTHER_ACCOUNT
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.appState
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.chatListRow
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.group
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.memberRoster
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.memberSnapshot
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.removedRoster
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Action-boundary coverage for stale and superseded invitation generations (#1248). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationInviteAcceptanceIntegrationTest {
    private val owners = InviteAcceptanceOwnerFixtures()

    /** Releases every fixture owner, including tests that deliberately clear one early. */
    @After
    fun releaseControllers() = owners.release()

    /** Both rendered Join surfaces pass their exact group and Welcome generation into the controller. */
    @Test
    fun bothProductionJoinEntriesFenceTheirRenderedWelcomeGeneration() {
        listOf(
            source("ui/conversation/ConversationBottomBar.kt"),
            source("ui/conversation/TimelineRowTtsHighlight.kt"),
        ).forEach { source ->
            assertTrue("Join entry must capture the rendered group", "renderedInviteGroupIdHex" in source)
            assertTrue("Join entry must capture the rendered Welcome", "renderedInviteWelcomeMessageIdHex" in source)
            assertTrue(
                "Join entry must pass the group fence",
                "renderedGroupIdHex = renderedInviteGroupIdHex" in source,
            )
            assertTrue(
                "Join entry must pass the Welcome fence",
                "renderedWelcomeMessageIdHex = renderedInviteWelcomeMessageIdHex" in source,
            )
        }
    }

    /** A queued click from an old composition never reaches native acceptance for the new Welcome. */
    @Test
    fun queuedOldRenderedActionCannotAcceptANewerWelcome() =
        runTest {
            var nativeCalls = 0
            val controller =
                controller(
                    appState = appState(),
                    accept = { _, _ ->
                        nativeCalls += 1
                        group(pending = false, welcome = NEW_WELCOME)
                    },
                )
            controller.applyGroupStateForTest(group(pending = true, welcome = NEW_WELCOME))

            assertFalse(
                controller.acceptInvite(
                    renderedGroupIdHex = GROUP_ID,
                    renderedWelcomeMessageIdHex = OLD_WELCOME,
                ),
            )

            assertEquals(0, nativeCalls)
            assertTrue(controller.group.pendingConfirmation)
            assertEquals(NEW_WELCOME, controller.group.viaWelcomeMessageIdHex)
        }

    /** A terminal group update wins over a successful native return already in flight. */
    @Test
    fun lateSuccessfulAcceptAfterRemovalPublishesNoSuccessStateOrNotice() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val appState = appState()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ ->
                        started.complete(Unit)
                        release.await()
                        group(pending = false, welcome = OLD_WELCOME)
                    },
                )
            val acceptance = async { controller.acceptInvite() }
            started.await()

            controller.applyGroupStateForTest(
                group(
                    pending = false,
                    welcome = OLD_WELCOME,
                    selfMembership = SelfMembershipFfi.REMOVED,
                ),
            )
            release.complete(Unit)

            assertFalse(acceptance.await())
            assertEquals(SelfMembershipFfi.REMOVED, controller.group.selfMembership)
            assertFalse(controller.group.pendingConfirmation)
            assertFalse(controller.isSelfMember)
            assertNull(appState.transientNotice)
        }

    /** A late result for one Welcome cannot consume a distinct canonical re-invite. */
    @Test
    fun lateSuccessfulAcceptPreservesAndCanLaterAcceptTheNewerWelcome() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var call = 0
            val controller =
                controller(
                    appState = appState(),
                    accept = { _, _ ->
                        call += 1
                        if (call == 1) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            group(pending = false, welcome = OLD_WELCOME)
                        } else {
                            group(pending = false, welcome = NEW_WELCOME)
                        }
                    },
                )
            val firstAcceptance = async { controller.acceptInvite(notify = false) }
            firstStarted.await()

            controller.applyGroupStateForTest(group(pending = true, welcome = NEW_WELCOME))
            releaseFirst.complete(Unit)

            assertFalse(firstAcceptance.await())
            assertTrue(controller.group.pendingConfirmation)
            assertEquals(NEW_WELCOME, controller.group.viaWelcomeMessageIdHex)
            assertTrue(
                controller.acceptInvite(
                    notify = false,
                    renderedGroupIdHex = GROUP_ID,
                    renderedWelcomeMessageIdHex = NEW_WELCOME,
                ),
            )
            assertEquals(2, call)
            assertEquals(NEW_WELCOME, controller.group.viaWelcomeMessageIdHex)
            assertFalse(controller.group.pendingConfirmation)
        }

    /** A native record for another Welcome is reconciled from authority, never assigned as this success. */
    @Test
    fun mismatchedSuccessfulReturnCannotReplaceTheAcceptedGeneration() =
        runTest {
            val appState = appState()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ -> group(pending = false, welcome = NEW_WELCOME) },
                )

            assertFalse(controller.acceptInvite())

            assertEquals(OLD_WELCOME, controller.group.viaWelcomeMessageIdHex)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
            assertFalse(controller.inviteAcceptanceResolutionPending)
            assertNull(appState.transientNotice)
        }

    /** A failed roster read keeps the refused action retired and both membership surfaces closed. */
    @Test
    fun typedRefusalWithFailedRosterReadStaysPendingAuthoritativeResolution() =
        runTest {
            val appState = appState()
            var rosterAvailable = false
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ -> throw MarmotKitException.GroupInviteNotPending() },
                    roster = { _, _ ->
                        if (!rosterAvailable) throw IllegalStateException("offline")
                        memberRoster()
                    },
                )

            assertFalse(controller.acceptInvite())

            assertFalse(controller.group.pendingConfirmation)
            assertTrue(controller.inviteAcceptanceResolutionPending)
            assertFalse(controller.isSelfMember)
            assertFalse(controller.canSendMessages)
            assertEquals(GroupRosterLoadState.FAILED, controller.memberRosterState)
            assertNull(appState.transientNotice)
            assertNull(appState.toast)

            rosterAvailable = true
            controller.retryInviteAcceptanceAuthority()

            assertFalse(controller.inviteAcceptanceResolutionPending)
            assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)
            assertTrue(controller.isSelfMember)
        }

    /** Typed refusal plus a member roster means another actor already accepted; it is not removal. */
    @Test
    fun typedRefusalWhileAuthoritativeMemberDoesNotInventTerminalMembership() =
        runTest {
            val appState = appState()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ -> throw MarmotKitException.GroupInviteNotPending() },
                    roster = { _, _ -> memberRoster() },
                )

            assertFalse(controller.acceptInvite())

            assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
            assertFalse(controller.group.pendingConfirmation)
            assertFalse(controller.inviteAcceptanceResolutionPending)
            assertTrue(controller.isSelfMember)
            assertNull(appState.transientNotice)
        }

    /** Typed refusal publishes terminal chrome only after a roster confirms removal. */
    @Test
    fun typedRefusalUsesConfirmedRemovedRosterWithoutAnAcceptedNotice() =
        runTest {
            val appState = appState()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ -> throw MarmotKitException.GroupInviteNotPending() },
                    roster = { _, _ -> removedRoster() },
                )

            assertFalse(controller.acceptInvite())

            assertEquals(SelfMembershipFfi.REMOVED, controller.group.selfMembership)
            assertFalse(controller.group.pendingConfirmation)
            assertFalse(controller.inviteAcceptanceResolutionPending)
            assertFalse(controller.isSelfMember)
            assertNull(appState.transientNotice)
        }

    /** Cancelling an old action cannot roll a terminal group back to its captured invite. */
    @Test
    fun cancellationAfterRemovalPreservesTheAuthoritativeTerminalState() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val appState = appState()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ ->
                        started.complete(Unit)
                        never.await()
                        group(pending = false, welcome = OLD_WELCOME)
                    },
                )
            val acceptance = async { controller.acceptInvite() }
            started.await()
            controller.applyGroupStateForTest(
                group(
                    pending = false,
                    welcome = OLD_WELCOME,
                    selfMembership = SelfMembershipFfi.LEFT,
                ),
            )

            acceptance.cancelAndJoin()

            assertEquals(SelfMembershipFfi.LEFT, controller.group.selfMembership)
            assertFalse(controller.group.pendingConfirmation)
            assertNull(appState.transientNotice)
            assertNull(appState.toast)
        }

    /** A Retry queued after controller disposal cannot cross the native roster boundary. */
    @Test
    fun queuedAuthorityRetryAfterControllerClearDoesNoWork() =
        runTest {
            assertQueuedAuthorityRetryDoesNoWork(owners) { it.onCleared() }
        }

    /** A Retry queued after account teardown cannot cross the native roster boundary. */
    @Test
    fun queuedAuthorityRetryAfterAccountTeardownDoesNoWork() =
        runTest {
            assertQueuedAuthorityRetryDoesNoWork(owners) {
                it.closeLiveSubscriptionsForAccountTeardown(ACCOUNT)
            }
        }

    /** A transient retry cannot accept a newer Welcome that appeared during its backoff. */
    @Test
    fun transientRetryCannotAcceptANewerWelcomeDuringBackoff() =
        runTest {
            assertBusyRetryDoesNotCrossAuthoritativeUpdate(
                owners,
                group(pending = true, welcome = NEW_WELCOME),
            )
        }

    /** A transient retry cannot accept after authoritative removal lands during its backoff. */
    @Test
    fun transientRetryCannotAcceptAfterRemovalDuringBackoff() =
        runTest {
            assertBusyRetryDoesNotCrossAuthoritativeUpdate(
                owners,
                group(
                    pending = false,
                    welcome = OLD_WELCOME,
                    selfMembership = SelfMembershipFfi.REMOVED,
                ),
            )
        }

    /** Controller disposal invalidates an authority result already suspended in native I/O. */
    @Test
    fun heldAuthorityCompletionAfterControllerClearDoesNotPublish() =
        runTest {
            assertHeldAuthorityCompletionDoesNotPublish(owners) { it.onCleared() }
        }

    /** Account teardown invalidates an authority result already suspended in native I/O. */
    @Test
    fun heldAuthorityCompletionAfterAccountTeardownDoesNotPublish() =
        runTest {
            assertHeldAuthorityCompletionDoesNotPublish(owners) {
                it.closeLiveSubscriptionsForAccountTeardown(ACCOUNT)
            }
        }

    /** A disposed controller drops a held success before roster work or accepted projection writes. */
    @Test
    fun lateSuccessAfterControllerClearDoesNoPostAcceptanceWork() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var rosterReads = 0
            val appState = appState()
            val chatsController = owners.attachedChatList(appState)
            runCurrent()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ ->
                        started.complete(Unit)
                        release.await()
                        group(pending = false, welcome = OLD_WELCOME).copy(name = LATE_ACCEPTED_NAME)
                    },
                    roster = { _, _ ->
                        rosterReads += 1
                        memberRoster()
                    },
                )
            val acceptance = async { controller.acceptInvite() }
            started.await()
            runCurrent()
            val optimisticProjectedGroup = chatsController.items.single().group
            controller.onCleared()

            release.complete(Unit)

            assertFalse(acceptance.await())
            runCurrent()
            assertEquals(0, rosterReads)
            assertEquals(optimisticProjectedGroup, chatsController.items.single().group)
            assertNotEquals(
                LATE_ACCEPTED_NAME,
                chatsController.items
                    .single()
                    .group.name,
            )
            assertNull(appState.transientNotice)
        }

    /** Account teardown drops a held success before roster work or accepted projection writes. */
    @Test
    fun lateSuccessAfterAccountTeardownDoesNoPostAcceptanceWork() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var rosterReads = 0
            val appState = appState()
            val chatsController = owners.attachedChatList(appState)
            runCurrent()
            val controller =
                controller(
                    appState = appState,
                    accept = { _, _ ->
                        started.complete(Unit)
                        release.await()
                        group(pending = false, welcome = OLD_WELCOME).copy(name = LATE_ACCEPTED_NAME)
                    },
                    roster = { _, _ ->
                        rosterReads += 1
                        memberRoster()
                    },
                )
            val acceptance = async { controller.acceptInvite() }
            started.await()
            runCurrent()
            val optimisticProjectedGroup = chatsController.items.single().group
            controller.closeLiveSubscriptionsForAccountTeardown(ACCOUNT)

            release.complete(Unit)

            assertFalse(acceptance.await())
            runCurrent()
            assertEquals(0, rosterReads)
            assertEquals(optimisticProjectedGroup, chatsController.items.single().group)
            assertNotEquals(
                LATE_ACCEPTED_NAME,
                chatsController.items
                    .single()
                    .group.name,
            )
            assertNull(appState.transientNotice)
        }

    /** A roster started before a fresh Welcome cannot re-latch the old terminal generation. */
    @Test
    fun freshWelcomeInvalidatesAnOlderRosterCompletionBeforeClearingSelfLeft() =
        runTest {
            val firstReadStarted = CompletableDeferred<Unit>()
            val releaseFirstRead = CompletableDeferred<Unit>()
            var rosterReads = 0
            val controller =
                owners.track(
                    ConversationController(
                        appState = appState(),
                        initialGroup =
                            group(
                                pending = false,
                                welcome = OLD_WELCOME,
                                selfMembership = SelfMembershipFfi.REMOVED,
                            ),
                        initialMemberSnapshot = memberSnapshot(),
                        groupRosterReader = { _, _ ->
                            rosterReads += 1
                            if (rosterReads == 1) {
                                firstReadStarted.complete(Unit)
                                releaseFirstRead.await()
                            }
                            memberRoster()
                        },
                    ),
                )
            val oldRefresh = async { controller.retryMembers() }
            firstReadStarted.await()

            controller.applyGroupStateForTest(group(pending = true, welcome = NEW_WELCOME))
            releaseFirstRead.complete(Unit)
            oldRefresh.await()

            assertTrue(controller.group.pendingConfirmation)
            assertEquals(NEW_WELCOME, controller.group.viaWelcomeMessageIdHex)
            assertFalse(controller.isSelfMember)
            assertTrue(controller.members.isEmpty())

            controller.retryMembers()

            assertEquals(2, rosterReads)
            assertTrue(controller.group.pendingConfirmation)
            assertTrue(controller.isSelfMember)
        }

    /** A distinct nonblank canonical Welcome reopens a terminal controller and accepts normally. */
    @Test
    fun genuineReinviteRecoversOnTheSameController() =
        runTest {
            val appState = appState()
            val controller =
                owners.track(
                    ConversationController(
                        appState = appState,
                        initialGroup =
                            group(
                                pending = true,
                                welcome = OLD_WELCOME,
                                selfMembership = SelfMembershipFfi.REMOVED,
                            ),
                        initialMemberSnapshot = memberSnapshot(),
                        inviteAcceptor = { _, _ -> group(pending = false, welcome = NEW_WELCOME) },
                        groupRosterReader = { _, _ -> memberRoster() },
                    ),
                )
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(SelfMembershipFfi.REMOVED, controller.group.selfMembership)

            controller.applyGroupStateForTest(group(pending = true, welcome = NEW_WELCOME))

            assertTrue(controller.group.pendingConfirmation)
            assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
            assertTrue(
                controller.acceptInvite(
                    notify = false,
                    renderedGroupIdHex = GROUP_ID,
                    renderedWelcomeMessageIdHex = NEW_WELCOME,
                ),
            )
            runCurrent()
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(NEW_WELCOME, controller.group.viaWelcomeMessageIdHex)
            assertTrue(controller.isSelfMember)
        }

    /** Account-pinned acceptance never mutates another account's attached chat projection. */
    @Test
    fun pinnedAcceptanceLeavesTheActiveAccountsSameGroupRowUntouched() =
        runTest {
            val appState = appState(activeAccountRef = OTHER_ACCOUNT)
            val chatsController =
                owners.track(
                    ChatsController(
                        appState = appState,
                        initialAccountRef = OTHER_ACCOUNT,
                        memberSnapshotLoader = { _, _ -> emptyList() },
                    ),
                )
            appState.attachChatsController(chatsController)
            chatsController.setChatListVisible(false)
            chatsController.applyChatListRow(chatListRow(pending = true))
            chatsController.setChatListVisible(true)
            runCurrent()
            val controller =
                owners.track(
                    ConversationController(
                        appState = appState,
                        initialGroup = group(pending = true, welcome = OLD_WELCOME),
                        initialMemberSnapshot = memberSnapshot(),
                        accountRefOverride = ACCOUNT,
                        inviteAcceptor = { _, _ -> group(pending = false, welcome = OLD_WELCOME) },
                        groupRosterReader = { _, _ -> memberRoster() },
                    ),
                )

            assertTrue(controller.acceptInvite(notify = false))

            assertTrue(
                chatsController.items
                    .single()
                    .group.pendingConfirmation,
            )
            assertFalse(controller.group.pendingConfirmation)
        }

    /** Builds a controller whose invite and roster boundaries are deterministic. */
    private fun controller(
        appState: WhiteNoiseAppState,
        accept: InviteAcceptor,
        roster: suspend (String, String) -> GroupRosterFfi = { _, _ -> memberRoster() },
    ) = owners.controller(appState, accept, roster)
}
