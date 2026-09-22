package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** Proves imported relay lists are never published by automatic setup advancement. */
class AccountSetupDefaultsTest {
    /** Healthy relay lists need no replacement or publication while the device decision is pending. */
    @Test fun healthyAccountDoesNotRepublishItsExistingLists() =
        runTest {
            val device = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY))
            assertEquals(device, advance(device))
        }

    /** A missing profile must wait for the user's edit or skip decision without native mutation. */
    @Test fun missingProfileIsNeverSkippedAutomatically() =
        runTest {
            val profile = setupSnapshot()
            assertEquals(profile, advance(profile))
        }

    /** An empty result on our sources must never authorize publication for an imported identity. */
    @Test fun missingListsRequireConsent() =
        runTest {
            for (step in listOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS)) {
                val missing = missing(step)
                assertEquals(missing, advance(missing))
                assertEquals(
                    missing,
                    advance(setupSnapshot(OnboardingStepFfi.FOLLOWS), "continueOnboardingWithout" to missing),
                )
            }
        }

    /** Imported identities use public discovery sources as well as messaging relays. */
    @Test fun discoveryIncludesIndexers() {
        val options = setupOptions()
        assertEquals(dev.ipf.whitenoise.android.core.MarmotClient.bootstrapRelays, options.defaultRelays)
        assertEquals(
            options.defaultRelays + listOf("wss://purplepag.es", "wss://relay.vertexlab.io", "wss://nos.lol"),
            options.discoveryRelays,
        )
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

    /** Restored decisions and cancellation are durable user intent, never a new automatic publication grant. */
    @Test fun savedProposalAndPendingCancellationAreUntouched() =
        runTest {
            val proposed = proposal(OnboardingStepFfi.INBOX_RELAYS)
            assertEquals(proposed, advance(proposed))
            val cancelling = missing(OnboardingStepFfi.RELAYS).copy(cancellationPending = true)
            assertEquals(cancelling, advance(cancelling))
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
                if (name.startsWith("approveOnboardingRepair")) assertEquals(9uL, (args[1] as Long).toULong())
                if (name == "approveOnboardingRepairInEpoch") assertEquals("proposal-epoch", args[2])
                expected.second
            } as MarmotInterface
        val result = AccountSetupDefaults(marmot, SETUP_TEST_ACCOUNT).advance(initial)
        assertEquals(0, pending.size)
        return result
    }
}
