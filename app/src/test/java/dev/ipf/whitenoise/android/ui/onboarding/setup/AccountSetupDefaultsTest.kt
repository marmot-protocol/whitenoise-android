package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** Proves automatic defaults use only the published API and never approve replacement of existing records. */
class AccountSetupDefaultsTest {
    /** Healthy relay lists need no replacement or publication while the device decision is pending. */
    @Test fun healthyAccountDoesNotRepublishItsExistingLists() =
        runTest {
            val device = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY))
            assertEquals(device, advance(device))
        }

    /** A fresh account needs no profile, follow, recommended-relay, or relay-approval taps. */
    @Test fun freshAccountAdvancesToDeviceConsent() =
        runTest {
            val device = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY))
            val result =
                advance(
                    setupSnapshot(),
                    "continueOnboardingWithout" to setupSnapshot(OnboardingStepFfi.FOLLOWS),
                    "continueOnboardingWithout" to missing(OnboardingStepFfi.RELAYS),
                    "proposeOnboardingRecommendedRelays" to proposal(OnboardingStepFfi.RELAYS),
                    "approveOnboardingRepair" to missing(OnboardingStepFfi.INBOX_RELAYS),
                    "proposeOnboardingRecommendedRelays" to proposal(OnboardingStepFfi.INBOX_RELAYS),
                    "approveOnboardingRepair" to device,
                )
            assertEquals(device, result)
        }

    /** Mixed, inconclusive, and unhealthy findings must not turn a missing-list hint into permission. */
    @Test fun uncertainOrUnhealthyListsNeverPublishAutomatically() =
        runTest {
            for (issue in listOf(
                OnboardingIssueFfi.DISCOVERY_INCOMPLETE,
                OnboardingIssueFfi.UNREACHABLE,
                OnboardingIssueFfi.NO_USABLE_ROUTE,
                OnboardingIssueFfi.INVALID_RELAY,
            )) {
                val snapshot = missing(OnboardingStepFfi.RELAYS)
                snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.apply {
                    findings += OnboardingFindingFfi(issue, null)
                }
                assertEquals(snapshot, advance(snapshot))
            }
        }

    /** Discovery may change before proposal creation; an existing event requires explicit replacement consent. */
    @Test fun newlyDiscoveredExistingRecordStopsBeforeApproval() =
        runTest {
            val existing = proposal(OnboardingStepFfi.RELAYS, "existing-record")
            assertEquals(
                existing,
                advance(
                    missing(OnboardingStepFfi.RELAYS),
                    "proposeOnboardingRecommendedRelays" to existing,
                ),
            )
        }

    /** Restored decisions and cancellation are durable user intent, never a new automatic publication grant. */
    @Test fun savedProposalAndPendingCancellationAreUntouched() =
        runTest {
            val proposed = proposal(OnboardingStepFfi.INBOX_RELAYS)
            assertEquals(proposed, advance(proposed))
            val cancelling = missing(OnboardingStepFfi.RELAYS).copy(cancellationPending = true)
            assertEquals(cancelling, advance(cancelling))
        }

    /** Signer denial stops the sequence without another automatic request to Amber. */
    @Test fun signerRejectionRequiresAnExplicitRetry() =
        runTest {
            val denied = proposal(OnboardingStepFfi.INBOX_RELAYS)
            denied.steps.first { it.step == OnboardingStepFfi.INBOX_RELAYS }.apply {
                status = OnboardingStatusFfi.WAITING_FOR_SIGNER
                actions = listOf(OnboardingActionFfi.RECONNECT_SIGNER)
            }
            assertEquals(
                denied,
                advance(
                    missing(OnboardingStepFfi.INBOX_RELAYS),
                    "proposeOnboardingRecommendedRelays" to proposal(OnboardingStepFfi.INBOX_RELAYS),
                    "approveOnboardingRepair" to denied,
                ),
            )
        }

    /** Native approval owns the final discovery fence; a rejected attempt must not loop and try again. */
    @Test fun changedRecordDuringApprovalStopsTheAutomaticSequence() =
        runTest {
            val changed = missing(OnboardingStepFfi.RELAYS)
            changed.steps.first { it.step == OnboardingStepFfi.RELAYS }.apply {
                status = OnboardingStatusFfi.RETRYABLE_FAILURE
                findings = listOf(OnboardingFindingFfi(OnboardingIssueFfi.RECORD_CHANGED, null))
                actions = listOf(OnboardingActionFfi.RETRY)
            }
            assertEquals(
                changed,
                advance(
                    missing(OnboardingStepFfi.RELAYS),
                    "proposeOnboardingRecommendedRelays" to proposal(OnboardingStepFfi.RELAYS),
                    "approveOnboardingRepair" to changed,
                ),
            )
        }

    /** Builds positive missing evidence, separate from empty or incomplete discovery. */
    private fun missing(step: OnboardingStepFfi): OnboardingSnapshotFfi =
        setupSnapshot(step, listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS)).also { snapshot ->
            snapshot.steps.first { it.step == step }.findings =
                listOf(OnboardingFindingFfi(OnboardingIssueFfi.MISSING, null))
        }

    /** Models the durable proposal whose revision must be passed unchanged to native approval. */
    private fun proposal(
        step: OnboardingStepFfi,
        previous: String? = null,
    ): OnboardingSnapshotFfi =
        setupSnapshot(step, listOf(OnboardingActionFfi.APPROVE_REPAIR), revision = 9uL).also {
            it.proposal =
                OnboardingRepairProposalFfi(
                    step,
                    9uL,
                    previous,
                    listOf("wss://relay.example"),
                    if (step == OnboardingStepFfi.RELAYS) listOf("wss://relay.example") else emptyList(),
                    null,
                    null,
                )
        }

    /** A strict ordered script fails on any extra native call or unintended automatic retry. */
    private suspend fun advance(
        initial: OnboardingSnapshotFfi,
        vararg responses: Pair<String, OnboardingSnapshotFfi>,
    ): OnboardingSnapshotFfi {
        val pending = ArrayDeque(responses.toList())
        val type = MarmotInterface::class.java
        val marmot =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val expected = pending.removeFirst()
                val name = method.name.substringBefore('-')
                assertEquals(expected.first, name)
                assertEquals(SETUP_TEST_ACCOUNT, args[0])
                if (name == "approveOnboardingRepair") assertEquals(9uL, (args[1] as Long).toULong())
                expected.second
            } as MarmotInterface
        val result = AccountSetupDefaults(marmot, SETUP_TEST_ACCOUNT).advance(initial)
        assertEquals(0, pending.size)
        return result
    }
}
