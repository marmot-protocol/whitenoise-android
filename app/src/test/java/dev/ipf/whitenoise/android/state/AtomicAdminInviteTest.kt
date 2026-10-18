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
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

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
                assertDiagnosticTerminal("success")
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
                assertReconciliationOutcomes(emptyList())
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
                assertReconciliationOutcomes(listOf("start", "success"))
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
                assertDiagnosticTerminal("failure")
                assertReconciliationOutcomes(listOf("start", "failure"))
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

    @Test
    fun typedPreCommitRejectionDoesNotStartReconciliation() =
        runTest {
            val fixture =
                Fixture(rejection = MarmotKitException.MissingKeyPackage(PEER, MissingKeyPackageReasonFfi.NOT_FOUND))
            try {
                fixture.controller.refreshManagementState()
                assertFalse(fixture.controller.inviteMembers(listOf(PEER)))
                assertEquals(1, fixture.snapshotReads)
                assertReconciliationOutcomes(emptyList())
            } finally {
                fixture.controller.onCleared()
            }
        }

    @Test
    fun adminResolutionFailureHasOneRecipientOutcomeAndClearsPendingActivity() =
        runTest {
            val failures = listOf(MarmotKitException.InvalidIdentity("synthetic"), CancellationException("synthetic"))
            for (failure in failures) {
                val fixture = Fixture()
                try {
                    fixture.controller.refreshManagementState()
                    var resolutions = 0
                    fixture.onPeerResolution = {
                        resolutions++
                        if (resolutions == 2) {
                            assertTrue(fixture.controller.pendingMembershipActivity != null)
                            throw failure
                        }
                    }
                    var cancelled = false
                    try {
                        assertFalse(fixture.controller.inviteMembers(listOf(PEER), initialAdminRefs = listOf(PEER)))
                    } catch (caught: CancellationException) {
                        assertTrue(caught === failure)
                        cancelled = true
                    }
                    assertEquals(failure is CancellationException, cancelled)
                    assertEquals(2, resolutions)
                    assertTrue(fixture.invocations.isEmpty())
                    assertNull(fixture.controller.pendingMembershipActivity)
                    val outcomes =
                        ShadowLog.getLogsForTag("GroupCreation")
                            .filter { it.msg.contains("phase=recipients") }
                            .map { it.msg.substringAfter("outcome=").substringBefore(" ") }
                    assertEquals(listOf("start", if (cancelled) "cancelled" else "failure"), outcomes)
                    if (cancelled) assertNull(fixture.controller.lastMutationError)
                } finally {
                    fixture.controller.onCleared()
                }
            }
        }

    private fun assertReconciliationOutcomes(outcomes: List<String>) {
        val events = ShadowLog.getLogsForTag("GroupCreation").filter { it.msg.contains("phase=reconciliation") }
        assertEquals(outcomes.size, events.size)
        outcomes.zip(events).forEach { (outcome, event) -> assertTrue(event.msg.contains("outcome=$outcome")) }
    }

    private fun assertDiagnosticTerminal(outcome: String) {
        val terminal =
            ShadowLog.getLogsForTag("GroupCreation").filter {
                it.msg.contains("operation=group_invite") &&
                    it.msg.contains("phase=owner") &&
                    !it.msg.contains("outcome=start")
            }
        assertEquals(1, terminal.size)
        assertTrue(terminal.single().msg.contains("outcome=$outcome"))
        assertFalse(terminal.single().msg.contains(PEER))
    }

    private class Fixture(
        private val loseReply: Boolean = false,
        private val failBeforeCommit: Boolean = false,
        private val rejection: Throwable? = null,
    ) {
        init {
            ShadowLog.clear()
        }

        val invocations = mutableListOf<Pair<List<String>, List<String>>>()
        var snapshotReads = 0
        var revokeOnRecipientResolution = false
        var onPeerResolution: () -> Unit = {}
        private var current = snapshot(listOf(SELF), listOf(SELF))
        private val state: WhiteNoiseAppState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { ref ->
                    if (ref == PEER) onPeerResolution()
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
                    rejection?.let { throw it }
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
