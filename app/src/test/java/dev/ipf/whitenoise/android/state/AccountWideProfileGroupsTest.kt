package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.ui.profile.ProfileAccountWideGroupsHolder
import dev.ipf.whitenoise.android.ui.profile.personSharedGroupsForProfile
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.PEER_B
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.group
import dev.ipf.whitenoise.android.ui.share.member
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The roster MDK would return for one group, keyed by group id. */
private typealias RosterRead = suspend (String, String) -> List<AppGroupMemberRecordFfi>

/** A profile's groups in common and its group pickers see chats beyond the retained chat-list window (#2983). */
@RunWith(RobolectricTestRunner::class)
class AccountWideProfileGroupsTest {
    /** Groups in common include a shared group the window does not retain once its roster resolves. */
    @Test
    fun groupsInCommonIncludeChatsBeyondTheWindow() =
        runBlocking {
            val (appState, controller) = profileController { _, _ -> roster(TARGET) }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            val accountWide = ProfileAccountWideGroups(snapshot)

            assertEquals(setOf(RETAINED), appState.personSharedGroupsForProfile(TARGET).groups.ids())
            assertEquals(setOf(BEYOND), appState.personSharedGroupsForProfile(TARGET, accountWide).unresolvedGroupIds)
            assertTrue(snapshot.resolveMembersBeyondWindow(setOf(BEYOND)).isEmpty())
            val shared = appState.personSharedGroupsForProfile(TARGET, accountWide)
            assertEquals(setOf(RETAINED, BEYOND), shared.groups.ids())
        }

    /** Add to Groups offers an admin group beyond the window, loading until its roster resolves. */
    @Test
    fun addToGroupsOffersAdminGroupsBeyondTheWindow() =
        runBlocking {
            val (_, controller) = profileController(retainedMembers = roster()) { _, _ -> roster() }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            val accountWide = ProfileAccountWideGroups(snapshot)

            assertEquals(setOf(RETAINED), controller.profileAddableGroupsState(TARGET, ACCOUNT_HEX).groups.ids())
            val loading = controller.profileAddableGroupsState(TARGET, ACCOUNT_HEX, accountWide)
            assertEquals(setOf(BEYOND), loading.pendingGroupIds)
            assertEquals(ProfileGroupPickerLoadState.LOADING, loading.loadState)

            snapshot.resolveMembersBeyondWindow(loading.pendingGroupIds)
            val ready = controller.profileAddableGroupsState(TARGET, ACCOUNT_HEX, accountWide)
            assertEquals(setOf(RETAINED, BEYOND), ready.groups.ids())
            assertEquals(ProfileGroupPickerLoadState.READY, ready.loadState)
        }

    /** Promote offers a group beyond the window where the target is a member but not an admin. */
    @Test
    fun promoteOffersGroupsBeyondTheWindow() =
        runBlocking {
            val (_, controller) = profileController { _, _ -> roster(TARGET) }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            snapshot.resolveMembersBeyondWindow(setOf(BEYOND))

            val accountWide = ProfileAccountWideGroups(snapshot)
            val promotable = controller.profilePromotableGroupsState(TARGET, ACCOUNT_HEX, accountWide)

            assertEquals(setOf(RETAINED, BEYOND), promotable.groups.ids())
        }

    /** A roster that cannot be read beyond the window is reported, so the picker offers Retry instead of loading. */
    @Test
    fun unreadableRosterBeyondTheWindowBecomesARetryableFailure() =
        runBlocking {
            var unreadable = true
            val (_, controller) =
                profileController(retainedMembers = roster()) { _, groupId ->
                    check(!(unreadable && groupId == BEYOND)) { "roster unavailable" }
                    roster()
                }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())

            val missing = snapshot.resolveMembersBeyondWindow(setOf(BEYOND))
            val withFailures = ProfileAccountWideGroups(snapshot, missing)
            val failed = controller.profileAddableGroupsState(TARGET, ACCOUNT_HEX, withFailures)
            assertEquals(setOf(BEYOND), missing)
            assertEquals(ProfileGroupPickerLoadState.FAILED, failed.loadState)

            unreadable = false
            assertTrue(snapshot.resolveMembersBeyondWindow(setOf(BEYOND)).isEmpty())
            val ready = controller.profileAddableGroupsState(TARGET, ACCOUNT_HEX, ProfileAccountWideGroups(snapshot))
            assertEquals(ProfileGroupPickerLoadState.READY, ready.loadState)
        }

    /** A roster that lands after the account is torn down is neither reported nor offered under any account. */
    @Test
    fun rosterResolvedAfterTeardownIsRejected() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val (appState, controller) =
                profileController { _, groupId ->
                    if (groupId == BEYOND) {
                        started.complete(Unit)
                        release.await()
                    }
                    roster(TARGET)
                }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            val resolving = async { snapshot.resolveMembersBeyondWindow(setOf(BEYOND)) }
            started.await()
            controller.closeLiveSubscriptionsForAccountTeardown(ACCOUNT_REF)
            release.complete(Unit)

            val accountWide = ProfileAccountWideGroups(snapshot)
            assertTrue(resolving.await().isEmpty())
            val promotable = controller.profilePromotableGroupsState(TARGET, ACCOUNT_HEX, accountWide)
            assertFalse(BEYOND in promotable.groups.ids())
            assertFalse(BEYOND in appState.personSharedGroupsForProfile(TARGET, accountWide).groups.ids())
        }

    /** The profile holder records an unreadable roster and a retry clears it once the roster resolves. */
    @Test
    fun holderRecordsUnreadableRostersAndRetryClearsThem() =
        runBlocking {
            var unreadable = true
            val (appState, _) =
                profileController(retainedMembers = roster()) { _, groupId ->
                    check(!(unreadable && groupId == BEYOND)) { "roster unavailable" }
                    roster()
                }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val holder = ProfileAccountWideGroupsHolder(appState, scope)
            holder.load()

            holder.requestMembers(setOf(BEYOND))
            scope.settle()
            assertEquals(setOf(BEYOND), holder.value.failedGroupIds)

            unreadable = false
            holder.requestMembers(setOf(BEYOND), retry = true)
            scope.settle()
            assertTrue(holder.value.failedGroupIds.isEmpty())
            assertEquals(setOf(RETAINED, BEYOND), appState.profileAddableGroupsState(TARGET, holder.value).groups.ids())
        }

    /**
     * Binds a controller whose window retains [RETAINED] with [retainedMembers] and whose account-wide read also
     * presents [BEYOND], an admin group of this account read through [beyondRoster].
     */
    private fun profileController(
        retainedMembers: List<AppGroupMemberRecordFfi> = roster(TARGET),
        beyondRoster: RosterRead,
    ): Pair<WhiteNoiseAppState, ChatsController> {
        val appState = emptyAppState()
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("no live window in this test") },
                openChats = { _, _ -> error("no live feed in this test") },
                presentedChatList = { _, _ -> listOf(presentedRow(RETAINED), presentedRow(BEYOND)) },
            )
        val controller = ChatsController(appState, ACCOUNT_REF, memberSnapshotLoader = beyondRoster)
        controller.applyChatListRow(chatRow(RETAINED))
        controller.applyLocalGroupDetails(adminGroup(RETAINED), retainedMembers)
        controller.foldGroupRecordForTest(adminGroup(BEYOND))
        appState.attachChatsController(controller)
        return appState to controller
    }

    /** This account and a third member, plus [extra] members such as the viewed profile. */
    private fun roster(vararg extra: String): List<AppGroupMemberRecordFfi> =
        listOf(member(ACCOUNT_HEX, local = true), member(OTHER, local = false)) +
            extra.map { member(it, local = false) }

    /** A named group whose only admin is this account. */
    private fun adminGroup(groupIdHex: String) = group(groupIdHex).copy(name = "Group", admins = listOf(ACCOUNT_HEX))

    /** The group ids of these chat-list items. */
    private fun List<ChatListItem>.ids(): Set<String> = mapTo(linkedSetOf()) { it.group.groupIdHex }

    /** Waits for every job the holder launched into this scope. */
    private suspend fun CoroutineScope.settle() =
        coroutineContext.job.children
            .toList()
            .joinAll()

    private companion object {
        val RETAINED = "e1".repeat(32)
        val BEYOND = "e2".repeat(32)
        val TARGET = PEER_A
        val OTHER = PEER_B
    }
}
