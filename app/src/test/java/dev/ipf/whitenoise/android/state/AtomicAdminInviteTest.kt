package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.GroupConversationSnapshotFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomicAdminInviteTest {
    @Test
    fun mixedRolesAreCapturedInOneNativeInvitationWithoutFollowupPromotion() =
        runTest {
            val fixture = Fixture()
            try {
                fixture.controller.refreshManagementState()
                assertTrue(fixture.controller.inviteMembers(listOf(PEER, OTHER), initialAdminRefs = listOf(PEER)))
                assertEquals(listOf(listOf(PEER, OTHER) to listOf(PEER)), fixture.invocations)
                assertTrue(PEER in fixture.controller.group.admins)
                assertFalse(OTHER in fixture.controller.group.admins)
                assertEquals(3, fixture.controller.members.size)
            } finally {
                fixture.controller.onCleared()
            }
        }

    @Test
    fun ordinaryInvitationsKeepEveryRecipientANormalMember() =
        runTest {
            val fixture = Fixture()
            try {
                fixture.controller.refreshManagementState()
                assertTrue(fixture.controller.inviteMembers(listOf(PEER, OTHER)))
                assertEquals(listOf(listOf(PEER, OTHER) to emptyList<String>()), fixture.invocations)
                assertEquals(listOf(SELF), fixture.controller.group.admins)
            } finally {
                fixture.controller.onCleared()
            }
        }

    @Test
    fun unselectedAdminTargetFailsBeforeAnyNativeMutation() =
        runTest {
            val fixture = Fixture()
            try {
                fixture.controller.refreshManagementState()
                assertFalse(fixture.controller.inviteMembers(listOf(PEER), initialAdminRefs = listOf(OTHER)))
                assertTrue(fixture.invocations.isEmpty())
                assertEquals(1, fixture.controller.members.size)
            } finally {
                fixture.controller.onCleared()
            }
        }

    @Test
    fun lostReplyAfterNativeCommitReconcilesTheActualRolesWithoutAnotherInvitation() =
        runTest {
            val fixture = Fixture(loseReply = true)
            try {
                fixture.controller.refreshManagementState()
                assertTrue(fixture.controller.inviteMembers(listOf(PEER, OTHER), initialAdminRefs = listOf(PEER)))
                assertEquals(1, fixture.invocations.size)
                assertEquals(2, fixture.snapshotReads)
                assertEquals(listOf(SELF, PEER), fixture.controller.group.admins)
                assertNull(fixture.controller.lastMutationError)
            } finally {
                fixture.controller.onCleared()
            }
        }

    @Test
    fun failedNativeCommitReportsFailureWithoutChangingTheAuthoritativeRoster() =
        runTest {
            val fixture = Fixture(failBeforeCommit = true)
            try {
                fixture.controller.refreshManagementState()
                assertFalse(fixture.controller.inviteMembers(listOf(PEER), initialAdminRefs = listOf(PEER)))
                assertEquals(1, fixture.invocations.size)
                assertEquals(listOf(SELF), fixture.controller.members.map { it.memberIdHex })
                assertTrue(fixture.controller.lastMutationError != null)
            } finally {
                fixture.controller.onCleared()
            }
        }

    @Test
    fun revokedAuthorityDuringRecipientResolutionCannotReachTheNativeCommit() =
        runTest {
            val fixture = Fixture()
            try {
                fixture.controller.refreshManagementState()
                fixture.revokeOnRecipientResolution = true
                assertFalse(fixture.controller.inviteMembers(listOf(PEER), initialAdminRefs = listOf(PEER)))
                assertTrue(fixture.invocations.isEmpty())
                assertFalse(fixture.controller.isSelfAdmin)
            } finally {
                fixture.controller.onCleared()
            }
        }

    private class Fixture(
        private val loseReply: Boolean = false,
        private val failBeforeCommit: Boolean = false,
    ) {
        val invocations = mutableListOf<Pair<List<String>, List<String>>>()
        var snapshotReads = 0
        var revokeOnRecipientResolution = false
        private var current = snapshot(listOf(SELF), listOf(SELF))
        private val state: WhiteNoiseAppState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { ref ->
                    if (ref == PEER && revokeOnRecipientResolution) {
                        revokeOnRecipientResolution = false
                        current = snapshot(listOf(SELF), emptyList())
                        controller.refreshManagementState()
                    }
                    if (ref == ACCOUNT) SELF else ref
                },
                accounts = listOf(AccountSummaryFfi(ACCOUNT, SELF, true, false, false, true)),
                activeAccountRef = ACCOUNT,
            )
        val controller: ConversationController =
            ConversationController(
                appState = state,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupSnapshotReader = { _, _ ->
                    snapshotReads++
                    current
                },
                initialAdminInviteWriter = { account, group, members, admins ->
                    assertEquals(ACCOUNT, account)
                    assertEquals(ConversationTimelineTestIds.GROUP_ID, group)
                    invocations += members.toList() to admins.toList()
                    if (failBeforeCommit) error("native commit rejected")
                    current = snapshot(listOf(SELF) + members, listOf(SELF) + admins)
                    if (loseReply) error("native response lost after commit")
                    GroupMutationResultFfi(
                        SendSummaryFfi(
                            1u,
                            emptyList(),
                            SendAcceptDispositionFfi.PUBLISHED,
                            SendMaintenanceDispositionFfi.READY,
                        ),
                        current.details,
                        current.managementState,
                    )
                },
            )
    }

    private companion object {
        const val ACCOUNT = "alice"
        val SELF = ConversationTimelineTestIds.ACCOUNT_ID
        val PEER = "cc".repeat(32)
        val OTHER = "dd".repeat(32)

        fun snapshot(
            ids: List<String>,
            admins: List<String>,
        ): GroupConversationSnapshotFfi {
            val group = conversationTimelineTestGroup().copy(admins = admins)
            val details =
                GroupDetailsFfi(
                    group,
                    ids.map { id ->
                        GroupMemberDetailsFfi(
                            id,
                            ACCOUNT.takeIf { id == SELF },
                            id == SELF,
                            id in admins,
                            id == SELF,
                            "npub-$id",
                            null,
                        )
                    },
                    AppGroupMlsStateFfi(
                        group.groupIdHex,
                        group.protocolProfile,
                        GroupLifecycleStateFfi.STABLE,
                        0uL,
                        ids.size.toUInt(),
                        false,
                        emptyList(),
                        false,
                        false,
                        emptyList(),
                        null,
                    ),
                )
            val management =
                GroupManagementStateFfi(
                    myAccountIdHex = SELF,
                    isSelfAdmin = SELF in admins,
                    isLastAdmin = admins == listOf(SELF),
                    canInvite = SELF in admins,
                    canLeave = SELF !in admins,
                    requiresSelfDemoteBeforeLeave = SELF in admins,
                    leaveRequestPending = false,
                    leaveRequestedAtMs = null,
                    lifecycleState = GroupLifecycleStateFfi.STABLE,
                    disbandingEnabled = false,
                    disbanding = false,
                    canEnableDisbanding = false,
                    canDisband = false,
                    disbandingBlockers = emptyList(),
                    disbandRequest = null,
                    memberActions = emptyList(),
                )
            return GroupConversationSnapshotFfi(details, management)
        }
    }
}
