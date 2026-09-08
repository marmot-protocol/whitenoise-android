package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The host waits for MDK's genuine decision, then opens Chats without another confirmation. */
class AccountSetupAutomationTest {
    /** With defaults settled by MDK, only device consent is actionable and completion activates once. */
    @Test fun deviceConsentProceedsDirectlyToChats() =
        runTest {
            val client =
                FakeSetupClient(
                    setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY)),
                )
            var activations = 0
            val controller =
                AccountSetupController(
                    SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { true },
                    { activations++ },
                    {},
                )
            controller.reconnect()
            runCurrent()
            assertEquals(0, activations)
            assertEquals(0, client.requests.size)
            client.executeResult = { setupSnapshot(revision = 4uL, ready = true).also { client.current = it } }
            controller.submit(SetupRequest(3uL, OnboardingStepFfi.SINGLE_DEVICE, OnboardingActionFfi.CONTINUE_ANYWAY))
            runCurrent()
            assertEquals(1, activations)
            assertEquals(1, client.requests.size)
            controller.openChats()
            runCurrent()
            assertEquals(1, activations)
            controller.close()
        }

    /** Neither an interrupted checkpoint nor a cancelled flow can trigger automatic activation. */
    @Test fun errorsAndCancellationNeverAutoActivate() {
        val automation = AccountSetupAutomation()
        var activated = 0
        val ready = setupSnapshot(ready = true)
        automation.advance(AccountSetupState(ready, error = true)) { activated++ }
        automation.advance(AccountSetupState(ready.copy(cancellationPending = true))) { activated++ }
        assertEquals(0, activated)
        automation.advance(AccountSetupState(ready)) { activated++ }
        automation.advance(AccountSetupState(ready)) { activated++ }
        assertEquals(1, activated)
    }

    /** Diagnostics remain a presentation choice without submitting any native action. */
    @Test fun detailsToggleDoesNotMutateTheAccount() =
        runTest {
            val client = FakeSetupClient()
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.toggleDetails()
            assertEquals(true, controller.state.value.detailsExpanded)
            controller.toggleDetails()
            assertFalse(controller.state.value.detailsExpanded)
            assertEquals(0, client.requests.size)
            controller.close()
        }
}
