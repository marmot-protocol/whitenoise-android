package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dismissal releases a successful editor's temporary proposal without approving or replacing native state. */
class AccountSetupRelayDismissTest {
    /** Cancel waits for native cleanup, ignores repeated taps and restores the original two repair choices. */
    @Test fun dismissRestoresRepairChoicesAfterNativeCancellation() =
        runTest {
            val client = previewClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            assertNotNull(controller.state.value.editor)
            client.hold = CompletableDeferred()
            controller.dismissEditor()
            controller.dismissEditor()
            runCurrent()
            assertTrue(controller.state.value.busy)
            assertNotNull(controller.state.value.editor)
            assertEquals(1, client.requests.count { it.action == OnboardingActionFfi.CANCEL_REPAIR })
            client.hold!!.complete(Unit)
            runCurrent()
            val state = controller.state.value
            assertFalse(state.busy)
            assertNull(state.editor)
            assertNull(state.snapshot?.proposal)
            assertEquals(5uL, state.snapshot?.revision)
            assertTrue(OnboardingActionFfi.USE_RECOMMENDED_RELAYS in state.currentStep!!.actions)
            assertTrue(OnboardingActionFfi.EDIT_RELAYS in state.currentStep!!.actions)
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.APPROVE_REPAIR })
            controller.close()
        }

    /** A failed native cancel must not pretend dismissal succeeded; another tap can retry the same editor. */
    @Test fun failedDiscardKeepsTheEditorForRetry() =
        runTest {
            val client = previewClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            val editor = controller.state.value.editor
            client.fail = true
            controller.dismissEditor()
            runCurrent()
            assertTrue(controller.state.value.error)
            assertEquals(editor, controller.state.value.editor)
            assertNotNull(client.current.proposal)
            client.fail = false
            controller.dismissEditor()
            runCurrent()
            assertFalse(controller.state.value.error)
            assertNull(controller.state.value.editor)
            assertNull(client.current.proposal)
            controller.close()
        }

    /** A replacement not yet delivered by the native stream is shown without cancelling its proposal. */
    @Test fun dismissDoesNotCancelANewerNativeProposal() =
        runTest {
            val client = previewClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            val newer = relayPreviewSnapshot().copy(revision = 6uL, recoveryEpoch = "replacement-attempt")
            client.current = newer
            controller.dismissEditor()
            runCurrent()
            assertEquals(newer, controller.state.value.snapshot)
            assertNull(controller.state.value.editor)
            assertFalse(client.requests.any { it.action == OnboardingActionFfi.CANCEL_REPAIR })
            controller.close()
        }

    /** Profile drafts are local until submitted and must not invoke native proposal cancellation on dismissal. */
    @Test fun profileDismissalDoesNotSendNativeMutation() =
        runTest {
            val initial = setupSnapshot(OnboardingStepFfi.PROFILE, listOf(OnboardingActionFfi.EDIT_PROFILE))
            val client = FakeSetupClient(initial)
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE, 3uL)
            runCurrent()
            assertNotNull(controller.state.value.editor)
            controller.dismissEditor()
            assertNull(controller.state.value.editor)
            assertTrue(client.requests.isEmpty())
            controller.close()
        }

    /** Models persisted preview acquisition and the native step restored by cancellation. */
    private fun previewClient() =
        FakeSetupClient(relayDecision()).apply {
            executeResult = { request ->
                current =
                    if (request.action == OnboardingActionFfi.CANCEL_REPAIR) {
                        relayDecision().copy(revision = 5uL)
                    } else {
                        relayPreviewSnapshot()
                    }
                current
            }
        }
}
