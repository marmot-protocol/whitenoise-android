package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupConversationSnapshotFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupConversationSnapshotApplicationTest {
    @Test
    fun oneSnapshotPublishesBothTheRosterAndItsManagementCapabilities() =
        runTest {
            val expected = snapshot("Updated", admin = false)
            var calls = 0
            val controller =
                controller {
                    calls++
                    expected
                }
            try {
                assertTrue(controller.refreshManagementState())
                assertEquals(1, calls)
                assertEquals("Updated", controller.group.name)
                assertEquals(emptyList<String>(), controller.group.admins)
                assertEquals(expected.managementState, controller.managementState)
                assertTrue(controller.membersVerified)
            } finally {
                controller.onCleared()
            }
        }

    /** A committed cancellation retries only the roster read after a transient refresh failure. */
    @Test
    fun blockedLeaveCancellationRestoresMembershipOnlyAfterReadback() =
        runTest {
            val joined = snapshot("Stayed", admin = true)
            val blocked = joined.withBlockedLeave()
            var reads = 0
            val controller =
                controller(initialGroup = blocked.details.group) {
                    when (reads++) {
                        0 -> blocked
                        1 -> error("Roster temporarily unavailable")
                        else -> joined
                    }
                }
            val nativeCalls = mutableListOf<Pair<Any?, Any?>>()
            controller.installCancelLeaveStub { account, group -> nativeCalls += account to group }
            try {
                assertTrue(controller.refreshManagementState())
                assertTrue(controller.canCancelBlockedLeave)
                assertFalse(controller.canSendMessages)

                assertFalse(controller.cancelBlockedLeave())
                assertTrue(controller.canCancelBlockedLeave)
                assertFalse(controller.canSendMessages)

                assertTrue(controller.cancelBlockedLeave())
                assertEquals(listOf(ACCOUNT to ConversationTimelineTestIds.GROUP_ID), nativeCalls)
                assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
                assertTrue(controller.isSelfMember)
                assertTrue(controller.canSendMessages)
                assertFalse(controller.showsBlockedLeave)
            } finally {
                controller.onCleared()
            }
        }

    /** A cancellation can commit even when MDK fails to write its membership projection. */
    @Test
    fun blockedLeaveReadsMembershipAfterNativeCancellationError() =
        runTest {
            val joined = snapshot("Stayed", admin = true)
            val blocked = joined.withBlockedLeave()
            var current = blocked
            val controller = controller(initialGroup = blocked.details.group) { current }
            controller.installCancelLeaveStub { _, _ ->
                current = joined
                error("Native response lost after cancellation")
            }
            try {
                assertTrue(controller.refreshManagementState())
                assertTrue(controller.cancelBlockedLeave())
                assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
                assertTrue(controller.canSendMessages)
            } finally {
                controller.onCleared()
            }
        }

    /** A reopened controller can reconcile a cancellation submitted from the old conversation. */
    @Test
    fun reopenedBlockedLeaveResolvesAfterJoinedStateUpdate() =
        runTest {
            val joined = snapshot("Stayed", admin = true)
            val controller = controller(initialGroup = joined.withBlockedLeave().details.group) { joined }
            try {
                controller.applyGroupStateForTest(joined.details.group)
                assertFalse(controller.canSendMessages)
                controller.refreshMembersAfterGroupState()
                assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
                assertTrue(controller.canSendMessages)
            } finally {
                controller.onCleared()
            }
        }

    /** An observer can retry a failed readback even after MDK clears the blocked flag. */
    @Test
    fun clearedBlockKeepsReadbackAvailableAfterTransientFailure() =
        runTest {
            val joined = snapshot("Stayed", admin = true)
            var reads = 0
            val controller =
                controller(initialGroup = joined.withBlockedLeave().details.group) {
                    if (reads++ == 0) error("Roster temporarily unavailable")
                    joined
                }
            try {
                controller.applyGroupStateForTest(joined.details.group)
                assertFalse(controller.refreshManagementState())
                assertTrue(controller.showsBlockedLeave)
                assertTrue(controller.blockedLeaveNeedsReadback)
                assertTrue(controller.canCancelBlockedLeave)
                assertTrue(controller.cancelBlockedLeave())
                assertTrue(controller.canSendMessages)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun perLeafDetailsAreValidatedBeforeIdentityDeduplication() =
        runTest {
            val expected = snapshot("Multi-device", admin = true)
            val self = expected.details.members.single()
            val peer =
                self.copy(
                    memberIdHex = "cc".repeat(32),
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                )
            expected.details.members = listOf(self, self, peer, peer, peer)
            expected.details.mlsState.memberCount = 5u
            val controller = controller { expected }
            try {
                controller.refreshManagementState()
                assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)
                assertEquals(2, controller.members.size)
                assertEquals(expected.managementState, controller.managementState)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun earlierSnapshotCannotOverwriteTheNewerRosterOrCapabilities() =
        runTest {
            val old = CompletableDeferred<GroupConversationSnapshotFfi>()
            var calls = 0
            val expected = snapshot("Newer", admin = false)
            val controller = controller { if (calls++ == 0) old.await() else expected }
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { controller.refreshManagementState() }
                controller.refreshManagementState()
                old.complete(snapshot("Old", admin = true))
                first.await()
                assertEquals("Newer", controller.group.name)
                assertEquals(expected.managementState, controller.managementState)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun disposedControllerCannotPublishALateSuccessfulSnapshot() =
        runTest {
            val reply = CompletableDeferred<GroupConversationSnapshotFfi>()
            val controller = controller { reply.await() }
            val first = async(start = CoroutineStart.UNDISPATCHED) { controller.refreshManagementState() }
            controller.onCleared()
            reply.complete(snapshot("Late", admin = false))
            first.await()
            assertEquals("Timeline group", controller.group.name)
            assertNull(controller.managementState)
        }

    @Test
    fun committedProfileChangeSupersedesAnEarlierDetailsRead() =
        runTest {
            val reply = CompletableDeferred<GroupConversationSnapshotFfi>()
            val controller = controller { reply.await() }
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { controller.refreshManagementState() }
                assertTrue(controller.updateGroupProfile("Just committed", "Updated description"))
                reply.complete(snapshot("Old name", admin = false))
                org.junit.Assert.assertFalse(first.await())
                assertEquals("Just committed", controller.group.name)
                assertEquals("Updated description", controller.group.description)
                assertNull(controller.managementState)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun invalidJoinedRosterDoesNotPublishManagementAsThoughTheDetailsWereValid() =
        runTest {
            val invalid = snapshot("Invalid", admin = false)
            invalid.details.members = emptyList()
            val controller = controller { invalid }
            try {
                controller.refreshManagementState()
                assertEquals("Timeline group", controller.group.name)
                assertNull(controller.managementState)
                assertEquals(GroupRosterLoadState.INCONSISTENT, controller.memberRosterState)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun aSnapshotForAnotherGroupOrAccountCannotReplaceThisConversationsState() =
        runTest {
            for (wrongGroup in listOf(false, true)) {
                val foreign = snapshot("Foreign", admin = true)
                if (wrongGroup) {
                    foreign.details.group.groupIdHex = "ee".repeat(32)
                } else {
                    foreign.managementState.myAccountIdHex = "ff".repeat(32)
                }
                val controller = controller { foreign }
                try {
                    controller.refreshManagementState()
                    assertEquals("Timeline group", controller.group.name)
                    assertNull(controller.managementState)
                } finally {
                    controller.onCleared()
                }
            }
        }

    @Test
    fun aLateMemberProjectionCannotRestoreCapabilitiesAfterATerminalSelfLeave() =
        runTest {
            val left = conversationTimelineTestGroup().copy(selfMembership = SelfMembershipFfi.LEFT)
            val controller = controller(initialGroup = left) { snapshot("Old member frontier", admin = true) }
            try {
                controller.refreshManagementState()
                assertEquals(SelfMembershipFfi.LEFT, controller.group.selfMembership)
                assertTrue(controller.members.isEmpty())
                assertNull(controller.managementState)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun profileRemovalRechecksRevokedAuthorityInsideTheCommitLock() =
        runTest {
            var calls = 0
            val initial = snapshot("Before", admin = true)
            val peer =
                initial.details.members.single().copy(
                    memberIdHex = "cc".repeat(32),
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                )
            initial.details.members = initial.details.members + peer
            initial.details.mlsState.memberCount = 2u
            val revoked = snapshot("Revoked", admin = false)
            revoked.details.members = revoked.details.members + peer
            revoked.details.mlsState.memberCount = 2u
            val controller = controller { if (calls++ == 0) initial else revoked }
            try {
                controller.refreshManagementState()
                org.junit.Assert.assertTrue(controller.isSelfAdmin)
                org.junit.Assert.assertFalse(
                    controller.removeMember(
                        controller.members.single { it.memberIdHex == peer.memberIdHex },
                        refreshAuthorityBeforeCommit = true,
                    ),
                )
                assertEquals(2, calls)
                org.junit.Assert.assertFalse(controller.isSelfAdmin)
                org.junit.Assert.assertNotNull(controller.lastMutationError)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun failedAuthoritativeRefreshCannotReuseEarlierRemovalPermission() =
        runTest {
            var calls = 0
            val initial = snapshot("Before", admin = true)
            val peer =
                initial.details.members.single().copy(
                    memberIdHex = "cc".repeat(32),
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                )
            initial.details.members = initial.details.members + peer
            initial.details.mlsState.memberCount = 2u
            val controller =
                controller {
                    if (calls++ == 0) initial else throw IllegalStateException("network unavailable")
                }
            try {
                controller.refreshManagementState()
                org.junit.Assert.assertFalse(
                    controller.removeMember(
                        controller.members.single { it.memberIdHex == peer.memberIdHex },
                        refreshAuthorityBeforeCommit = true,
                    ),
                )
                assertEquals(2, calls)
                org.junit.Assert.assertNotNull(controller.lastMutationError)
                assertTrue(controller.members.any { it.memberIdHex == peer.memberIdHex })
            } finally {
                controller.onCleared()
            }
        }

    /** A competing accepted snapshot must not credit this removal's rejected authority read. */
    @Test
    fun supersededRemovalReadCannotBorrowAnotherSnapshotsAuthorityEpoch() =
        runTest {
            var calls = 0
            val initial = snapshot("Before", admin = true)
            val peer =
                initial.details.members.single().copy(
                    memberIdHex = "cc".repeat(32),
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                )
            initial.details.members = initial.details.members + peer
            initial.details.mlsState.memberCount = 2u
            val newer = snapshot("Newer", admin = true)
            newer.details.members = initial.details.members
            newer.details.mlsState.memberCount = 2u
            lateinit var controller: ConversationController
            controller =
                controller {
                    when (calls++) {
                        1 -> {
                            assertTrue(controller.refreshManagementState())
                            initial
                        }
                        2 -> newer
                        else -> initial
                    }
                }
            try {
                assertTrue(controller.refreshManagementState())
                org.junit.Assert.assertFalse(
                    controller.removeMember(
                        controller.members.single { it.memberIdHex == peer.memberIdHex },
                        refreshAuthorityBeforeCommit = true,
                    ),
                )
                assertEquals(3, calls)
                assertEquals("Newer", controller.group.name)
                assertTrue(controller.members.any { it.memberIdHex == peer.memberIdHex })
                assertTrue(checkNotNull(controller.lastMutationError).report.contains("error=STALE_STATE"))
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun retiredProfileRemovalCannotPresentAStaleRosterFailureToTheNewRuntime() =
        runTest {
            var calls = 0
            val initial = snapshot("Before", admin = true)
            val peer =
                initial.details.members.single().copy(
                    memberIdHex = "cc".repeat(32),
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                )
            initial.details.members = initial.details.members + peer
            initial.details.mlsState.memberCount = 2u
            lateinit var controller: ConversationController
            controller =
                controller {
                    if (calls++ > 0) {
                        val field = WhiteNoiseAppState::class.java.getDeclaredField("runtimeGeneration\$delegate")
                        field.isAccessible = true
                        val generation = field.get(controller.appState) as androidx.compose.runtime.MutableIntState
                        generation.intValue++
                    }
                    initial
                }
            try {
                controller.refreshManagementState()
                val previousToast = controller.appState.toast
                org.junit.Assert.assertFalse(
                    controller.removeMember(
                        controller.members.single { it.memberIdHex == peer.memberIdHex },
                        refreshAuthorityBeforeCommit = true,
                    ),
                )
                assertEquals(2, calls)
                assertEquals(previousToast, controller.appState.toast)
                assertNull(controller.lastMutationError)
            } finally {
                controller.onCleared()
            }
        }

    /** Models a native leave request whose projected LEFT is still provisional. */
    private fun GroupConversationSnapshotFfi.withBlockedLeave() =
        copy(
            details =
                details.copy(
                    group =
                        details.group.copy(
                            selfMembership = SelfMembershipFfi.LEFT,
                            leaveRequestPending = true,
                            leaveRequestedAtMs = 1uL,
                            leaveBlocked = true,
                        ),
                ),
            managementState =
                managementState.copy(
                    leaveRequestPending = true,
                    leaveRequestedAtMs = 1uL,
                    leaveBlocked = true,
                    canCancelLeave = true,
                ),
        )

    /** Installs the native cancellation stub alongside the injected snapshot reader. */
    private fun ConversationController.installCancelLeaveStub(cancel: (Any?, Any?) -> Unit) {
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, args ->
                check(method.name == "cancelLeave")
                val arguments = checkNotNull(args)
                cancel(arguments[0], arguments[1])
            } as MarmotInterface
        WhiteNoiseAppState::class.java
            .getDeclaredField("marmotRuntime")
            .apply { isAccessible = true }
            .set(appState, AppMarmotRuntime(rootPath = "test", marmot = marmot))
    }

    /** Builds a conversation controller whose group snapshot reads come from the given reader. */
    private fun controller(
        initialGroup: AppGroupRecordFfi = conversationTimelineTestGroup(),
        read: suspend () -> GroupConversationSnapshotFfi,
    ): ConversationController {
        val state =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { SELF },
                accounts = listOf(AccountSummaryFfi(ACCOUNT, SELF, true, false, false, true, false)),
                activeAccountRef = ACCOUNT,
            )
        return ConversationController(
            appState = state,
            initialGroup = initialGroup,
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            groupSnapshotReader = { account, group ->
                assertEquals(ACCOUNT, account)
                assertEquals(ConversationTimelineTestIds.GROUP_ID, group)
                read()
            },
            groupProfileUpdater = { _, _, _, _ -> },
        )
    }

    private fun snapshot(
        name: String,
        admin: Boolean,
    ) = groupConversationSnapshotFixture(name, admin)

    private companion object {
        const val ACCOUNT = "alice"
        val SELF = ConversationTimelineTestIds.ACCOUNT_ID
    }
}
