package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saved native operations remain inspectable even when the engine grants no action for this revision. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupReadOnlyRecoveryTest {
    @get:Rule val composeRule = createComposeRule()
    private var opens = 0

    /** Opening a saved approved repair reveals its exact proposal without inventing Retry or Approval. */
    @Test fun noActionApprovedRepairShowsNoticeAndReadOnlyProposal() {
        val client = showNative(approvedRepair())
        composeRule.onNodeWithText("Your saved change needs to finish.", substring = true).assertExists()
        composeRule.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
        composeRule.onNodeWithText("wss://read.example").performScrollTo().assertExists()
        composeRule.onNodeWithText("wss://write.example").performScrollTo().assertExists()
        composeRule.onNodeWithTag("setup-details").performScrollTo().performClick()
        composeRule.onNodeWithTag("setup-action-APPROVE_REPAIR").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-RETRY").assertDoesNotExist()
        assertTrue(client.requests.isEmpty())
        assertEquals(0, opens)
    }

    /** Cold cancellation with no grants retains its own explanation on checklist and inspected checkpoint. */
    @Test fun noActionCancellationRetainsExplanationAndNeverOffersApproval() {
        val snapshot = setupSnapshot(OnboardingStepFfi.INBOX_RELAYS, emptyList()).copy(cancellationPending = true)
        val client = showNative(snapshot)
        composeRule.onNodeWithText("Cancellation stopped.", substring = true).assertExists()
        composeRule.onNodeWithTag("setup-step-INBOX_RELAYS").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancellation stopped.", substring = true).assertExists()
        composeRule.onNodeWithTag("setup-action-APPROVE_REPAIR").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-CANCEL_ONBOARDING").assertDoesNotExist()
        assertTrue(client.requests.isEmpty())
        assertEquals(0, opens)
    }

    /** Busy inspection stays gated, but the saved-operation explanation remains visible during work. */
    @Test fun busyChecklistStillExplainsTheApprovedOperation() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountSetupContent(
                    AccountSetupState(approvedRepair(), busy = true),
                    {},
                    { _, _, _ -> },
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithText("Your saved change needs to finish.", substring = true).assertExists()
        composeRule.onNodeWithTag("setup-step-RELAYS").assertHasNoClickAction()
    }

    /** Selection belongs to the displayed native step; returning A after B cannot resurrect an old A detail. */
    @Test fun nativeStepRoundTripRequiresFreshDetailNavigation() {
        val state = mutableStateOf(AccountSetupState(snapshot = setupSnapshot()))
        composeRule.setContent {
            WhiteNoiseTheme { AccountSetupContent(state.value, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}) }
        }
        composeRule.onNodeWithTag("setup-step-PROFILE").performScrollTo().performClick()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertExists()
        composeRule.runOnIdle {
            state.value = AccountSetupState(setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY)))
        }
        composeRule.onNodeWithTag("setup-step-RELAYS").assertExists()
        composeRule.runOnIdle { state.value = AccountSetupState(setupSnapshot(revision = 5uL)) }
        composeRule.onNodeWithTag("setup-step-PROFILE").assertExists()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-step-PROFILE").performScrollTo().performClick()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertExists()
    }

    /** The fixture is native-shaped and intentionally offers no user mutation. */
    private fun approvedRepair(): OnboardingSnapshotFfi =
        setupSnapshot(OnboardingStepFfi.RELAYS, emptyList()).copy(
            proposal =
                OnboardingRepairProposalFfi(
                    OnboardingStepFfi.RELAYS,
                    3uL,
                    "existing",
                    listOf("wss://read.example"),
                    listOf("wss://write.example"),
                    null,
                    null,
                ),
        )

    /** Uses the actual controller and lifecycle-aware route; the client only supplies a saved native snapshot. */
    private fun showNative(snapshot: OnboardingSnapshotFfi): FakeSetupClient {
        val client = FakeSetupClient(snapshot)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    AccountSetupController(SETUP_TEST_ACCOUNT, client, scope, { true }, { opens++ }, {})
                }
            LaunchedEffect(controller) { controller.reconnect() }
            WhiteNoiseTheme { AccountSetupScreen(controller) {} }
        }
        composeRule.waitForIdle()
        return client
    }
}
