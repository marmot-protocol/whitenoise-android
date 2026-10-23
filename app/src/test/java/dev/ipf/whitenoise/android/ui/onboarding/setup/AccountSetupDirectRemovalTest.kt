package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayTagFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The direct Remove gesture prepares a native proposal but never grants publication. */
class AccountSetupDirectRemovalTest {
    @Test fun oneRemoveStagesExactEndpointAndRequiresSeparateApproval() =
        runTest {
            val preview = removablePreview()
            val review =
                preview.copy(
                    revision = 5uL,
                    proposal = preview.proposal!!.copy(revision = 5uL),
                )
            val client = removalClient(preview, review)
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()

            controller.edit(
                OnboardingStepFfi.RELAYS,
                OnboardingActionFfi.EDIT_RELAYS,
                3uL,
                endpoint = RETIRED,
            )
            runCurrent()

            assertEquals(
                listOf(
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
                    OnboardingActionFfi.EDIT_RELAYS,
                ),
                client.requests.map { it.action },
            )
            val staged = client.requests.last()
            assertEquals(listOf(KEPT), staged.readRelays)
            assertEquals(listOf(KEPT), staged.writeRelays)
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.APPROVE_REPAIR })
            assertEquals(review, controller.state.value.snapshot)
            assertTrue(controller.state.value.removalReview)
            assertNull(controller.state.value.editor)
            controller.close()
        }

    @Test fun repeatTapCannotCreateTwoPreviewsOrProposals() =
        runTest {
            val preview = removablePreview()
            val client =
                removalClient(
                    preview,
                    preview.copy(
                        revision = 5uL,
                        proposal = preview.proposal!!.copy(revision = 5uL),
                    ),
                )
            val wait = CompletableDeferred<Unit>()
            client.hold = wait
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL, RETIRED)
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL, RETIRED)
            runCurrent()
            assertEquals(1, client.requests.size)
            wait.complete(Unit)
            runCurrent()
            assertEquals(2, client.requests.size)
            controller.close()
        }

    @Test fun malformedSourceAndEmptyRequiredRouteNeverSubmitTheEdit() =
        runTest {
            val valid = removablePreview()
            val oneTag =
                valid.proposal!!
                    .relayRepair!!
                    .beforeTags
                    .first()
            val previews =
                listOf(
                    valid.copy(
                        proposal =
                            valid.proposal!!.copy(
                                relayRepair =
                                    valid.proposal!!.relayRepair!!.copy(
                                        beforeTags = listOf(oneTag.copy(endpoint = null)),
                                    ),
                            ),
                    ),
                    valid.copy(
                        proposal =
                            valid.proposal!!.copy(
                                relayRepair =
                                    valid.proposal!!.relayRepair!!.copy(beforeTags = listOf(oneTag)),
                            ),
                    ),
                )
            for ((index, preview) in previews.withIndex()) {
                val client = removalClient(preview, preview)
                val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
                controller.reconnect()
                runCurrent()
                controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL, RETIRED)
                runCurrent()
                assertEquals(
                    listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS),
                    client.requests.map { it.action },
                )
                assertEquals(
                    if (index == 0) RelayRemovalIssue.UNSAFE_DECLARATION else RelayRemovalIssue.ADD_REQUIRED_RELAY,
                    controller.state.value.relayRemovalIssue,
                )
                assertTrue(controller.state.value.editor != null)
                controller.close()
            }
        }

    @Test fun newerStreamDecisionCannotBeOverwrittenByLatePreview() =
        runTest {
            val preview = removablePreview()
            val client = removalClient(preview, preview)
            val wait = CompletableDeferred<Unit>()
            client.hold = wait
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL, RETIRED)
            runCurrent()
            val newer = preview.copy(revision = 6uL)
            client.current = newer
            client.updates.send(newer)
            runCurrent()
            wait.complete(Unit)
            runCurrent()
            assertEquals(newer, controller.state.value.snapshot)
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.EDIT_RELAYS })
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.CANCEL_REPAIR })
            controller.close()
        }

    @Test fun ownerInvalidatedAtSecondNativeReadNeverSubmitsProposal() =
        runTest {
            val preview = removablePreview()
            val client = removalClient(preview, preview)
            var ownerCurrent = true
            client.beforeSnapshot = { if (client.snapshotReads == 2) ownerCurrent = false }
            val controller =
                AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { ownerCurrent }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL, RETIRED)
            runCurrent()
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.EDIT_RELAYS })
            assertTrue(client.requests.any { it.action == OnboardingActionFfi.CANCEL_REPAIR })
            controller.close()
        }

    @Test fun changedRenderedEpochCannotStartRemoval() =
        runTest {
            val client = removalClient(removablePreview(), removablePreview())
            client.current = retiredDecision().copy(recoveryEpoch = "current")
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(
                OnboardingStepFfi.RELAYS,
                OnboardingActionFfi.EDIT_RELAYS,
                3uL,
                RETIRED,
                recoveryEpoch = "old",
            )
            runCurrent()
            assertTrue(client.requests.isEmpty())
            controller.close()
        }

    private fun removalClient(
        preview: OnboardingSnapshotFfi,
        review: OnboardingSnapshotFfi,
    ): FakeSetupClient =
        FakeSetupClient(retiredDecision()).apply {
            executeResult = { request ->
                current =
                    when (request.action) {
                        OnboardingActionFfi.USE_RECOMMENDED_RELAYS -> preview
                        OnboardingActionFfi.EDIT_RELAYS -> review
                        OnboardingActionFfi.CANCEL_REPAIR -> retiredDecision().copy(revision = 6uL)
                        else -> current
                    }
                current
            }
        }

    private fun retiredDecision() =
        relayDecision().apply {
            steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
                listOf(OnboardingFindingFfi(OnboardingIssueFfi.RETIRED_RELAY, RETIRED))
        }

    private fun removablePreview(): OnboardingSnapshotFfi {
        val retired = OnboardingRelayTagFfi(listOf("r", RETIRED), RETIRED, OnboardingRelayTagRoleFfi.UNMARKED)
        val kept = OnboardingRelayTagFfi(listOf("r", KEPT), KEPT, OnboardingRelayTagRoleFfi.UNMARKED)
        return relayPreviewSnapshot().apply {
            proposal!!.relayRepair = proposal!!.relayRepair!!.copy(beforeTags = listOf(retired, kept))
        }
    }

    private companion object {
        const val RETIRED = "wss://retired.example"
        const val KEPT = "wss://kept.example"
    }
}
