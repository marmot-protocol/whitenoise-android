package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distinguishes a user's reviewed repair revision from later progress in the surrounding checkpoint. */
class AccountSetupApprovalRevisionTest {
    /** An unchanged repair stays approvable through both cached and authoritative snapshot revision advances. */
    @Test
    fun unchangedProposalCanBeApprovedAfterTheSnapshotAdvances() =
        runTest {
            val client = FakeSetupClient(repairSnapshot(5uL))
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            client.current = repairSnapshot(6uL)
            val request = SetupRequest(3uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.APPROVE_REPAIR)
            controller.submit(request)
            runCurrent()
            assertEquals(listOf(request), client.requests)
            assertFalse(controller.state.value.staleDecision)
            assertEquals(
                6uL,
                controller.state.value.snapshot
                    ?.revision,
            )
            controller.close()
        }

    /** An identical revision in another recovery attempt cannot reuse either publication or device consent. */
    @Test
    fun grantsCannotCrossRecoveryEpochs() =
        runTest {
            for (action in listOf(OnboardingActionFfi.APPROVE_REPAIR, OnboardingActionFfi.CONTINUE_ANYWAY)) {
                val initial = repairSnapshot(3uL, actions = listOf(action)).copy(recoveryEpoch = "reviewed-epoch")
                val client = FakeSetupClient(initial)
                val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
                controller.reconnect()
                runCurrent()
                client.current = initial.copy(recoveryEpoch = "replacement-epoch")
                controller.submit(
                    SetupRequest(3uL, OnboardingStepFfi.RELAYS, action, recoveryEpoch = initial.recoveryEpoch),
                )
                runCurrent()
                assertEquals(1, client.snapshotReads)
                assertTrue(client.requests.isEmpty())
                assertTrue(controller.state.value.staleDecision)
                assertEquals(client.current, controller.state.value.snapshot)
                controller.close()
            }
        }

    /** A replaced proposal must be reviewed again even when approval is still an offered action. */
    @Test
    fun replacedProposalCannotUseThePreviousApproval() = rejectFreshRepair(repairSnapshot(6uL, proposalRevision = 4uL))

    /** Removing the saved repair invalidates an approval without falling back to the snapshot revision. */
    @Test
    fun removedProposalCannotBeApproved() = rejectFreshRepair(repairSnapshot(6uL).copy(proposal = null))

    /** An approved or otherwise unavailable decision cannot be submitted a second time after rereading MDK. */
    @Test
    fun withdrawnApprovalActionCannotBeSubmitted() =
        rejectFreshRepair(
            repairSnapshot(6uL, actions = listOf(OnboardingActionFfi.RETRY)),
        )

    /** A proposal owned by another step cannot borrow the displayed step's approval action. */
    @Test
    fun proposalFromAnotherStepCannotBeApproved() =
        rejectFreshRepair(
            repairSnapshot(6uL, proposalStep = OnboardingStepFfi.INBOX_RELAYS),
        )

    /** Retry and other non-approval actions still use the current snapshot revision. */
    @Test
    fun retryUsesSnapshotRevisionEvenWhenAProposalIsRetained() =
        runTest {
            val client = FakeSetupClient(repairSnapshot(5uL, actions = listOf(OnboardingActionFfi.RETRY)))
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            val request = SetupRequest(5uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.RETRY)
            controller.submit(request)
            runCurrent()
            assertEquals(listOf(request), client.requests)
            assertFalse(controller.state.value.staleDecision)
            controller.close()
        }

    /** Mounts the rendered decision before replacing only the authoritative state used by submit's reread. */
    private fun rejectFreshRepair(latest: OnboardingSnapshotFfi) =
        runTest {
            val client = FakeSetupClient(repairSnapshot(5uL))
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            client.current = latest
            controller.submit(SetupRequest(3uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.APPROVE_REPAIR))
            runCurrent()
            assertEquals(1, client.snapshotReads)
            assertTrue(client.requests.isEmpty())
            assertTrue(controller.state.value.staleDecision)
            assertEquals(latest, controller.state.value.snapshot)
            controller.close()
        }

    /** Creates a retained relay repair whose revision is independent of the overall checkpoint. */
    private fun repairSnapshot(
        snapshotRevision: ULong,
        proposalRevision: ULong = 3uL,
        proposalStep: OnboardingStepFfi = OnboardingStepFfi.RELAYS,
        actions: List<OnboardingActionFfi> = listOf(OnboardingActionFfi.APPROVE_REPAIR),
    ) = setupSnapshot(OnboardingStepFfi.RELAYS, actions, revision = snapshotRevision).copy(
        proposal =
            OnboardingRepairProposalFfi(
                proposalStep,
                proposalRevision,
                null,
                listOf("wss://relay.example"),
                emptyList(),
                null,
                null,
            ),
    )
}
