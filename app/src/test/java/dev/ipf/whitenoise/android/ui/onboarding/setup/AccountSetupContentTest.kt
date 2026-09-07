package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives every visible decision through its real callback, including scrolling to offscreen actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupContentTest {
    @get:Rule val composeRule = createComposeRule()
    private val actions = mutableListOf<SetupRequest>()
    private val edits = mutableListOf<OnboardingActionFfi>()
    private var later = 0
    private var opened = 0
    private var reconnected = 0

    /** The useful profile decision is visible without scrolling through all six checks first. */
    @Test
    fun profileActionsAreVisibleAboveTheDetailedChecklist() {
        show(AccountSetupState(snapshot = setupSnapshot()))
        composeRule.onNodeWithTag("setup-action-EDIT_PROFILE").assertIsDisplayed()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertIsDisplayed()
        composeRule.onNodeWithText("0 of 6 checks complete").assertIsDisplayed()
    }

    @Test
    fun everyOfferedActionHasAWorkingCallbackAndUsesRenderedRevision() {
        show(AccountSetupState(snapshot = setupSnapshot(actions = OnboardingActionFfi.entries)))
        OnboardingActionFfi.entries.filter { it != OnboardingActionFfi.EDIT_FOLLOWS }.forEach { action ->
            composeRule
                .onNodeWithTag("setup-action-${action.name}")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
        }
        assertEquals(setupEditorActions, edits.toSet())
        assertEquals(
            OnboardingActionFfi.entries.toSet() - setupEditorActions - OnboardingActionFfi.EDIT_FOLLOWS,
            actions.map { it.action }.toSet(),
        )
        assertTrue(actions.all { it.revision == 3uL })
    }

    @Test
    fun busyDecisionsCannotBeTappedButLaterRemainsReachable() {
        show(AccountSetupState(snapshot = setupSnapshot(), busy = true))
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("setup-later").performScrollTo().performClick()
        assertEquals(1, later)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun incompleteDiscoveryOffersRetryWithoutDefaultReplacement() {
        show(
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.RELAYS,
                        listOf(OnboardingActionFfi.RETRY, OnboardingActionFfi.EDIT_DISCOVERY_RELAYS),
                    ),
            ),
        )
        composeRule.onNodeWithTag("setup-action-USE_RECOMMENDED_RELAYS").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-RETRY").performScrollTo().performClick()
        assertEquals(OnboardingActionFfi.RETRY, actions.single().action)
    }

    @Test
    fun proposalShowsExactCapabilitiesAndReplacementWarningBeforeApproval() {
        val snapshot =
            setupSnapshot(
                OnboardingStepFfi.RELAYS,
                listOf(
                    OnboardingActionFfi.APPROVE_REPAIR,
                    OnboardingActionFfi.CANCEL_REPAIR,
                ),
            )
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                3uL,
                "previous",
                listOf("wss://read.example"),
                listOf("wss://write.example"),
                null,
                null,
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("wss://read.example").performScrollTo().assertExists()
        composeRule.onNodeWithText("wss://write.example").performScrollTo().assertExists()
        composeRule.onNodeWithText("Publishing replaces this entire relay list.", substring = true).assertExists()
        assertTrue(actions.isEmpty())
        composeRule.onNodeWithTag("setup-action-APPROVE_REPAIR").performScrollTo().performClick()
        assertEquals(3uL, actions.single().revision)
    }

    @Test
    fun signerReconnectAndReadyAfterStreamEndBothHaveWorkingRoutes() {
        show(
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.KEY_PACKAGE,
                        listOf(OnboardingActionFfi.RECONNECT_SIGNER),
                    ),
                disconnected = true,
            ),
        )
        composeRule.onNodeWithTag("setup-action-RECONNECT_SIGNER").performScrollTo().performClick()
        assertEquals(OnboardingActionFfi.RECONNECT_SIGNER, actions.single().action)
        composeRule.onNodeWithText("Resume checks").performScrollTo().performClick()
        assertEquals(1, reconnected)
    }

    @Test
    fun readyAfterStreamEndStillEnablesOpenChats() {
        show(AccountSetupState(snapshot = setupSnapshot(ready = true), disconnected = true))
        composeRule.onNodeWithText("The connection or operation stopped.", substring = true).assertDoesNotExist()
        composeRule
            .onNodeWithTag("setup-open-chats")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun interruptedApprovedRepairExplainsWhyOnlyRetryIsAvailable() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY))
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                3uL,
                null,
                listOf("wss://read.example"),
                listOf("wss://write.example"),
                null,
                null,
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("An approved repair must finish", substring = true).assertExists()
        composeRule.onNodeWithTag("setup-action-CANCEL_ONBOARDING").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-RETRY").performScrollTo().performClick()
        assertEquals(OnboardingActionFfi.RETRY, actions.single().action)
    }

    @Test
    fun interruptedCancellationOffersCancellationInsteadOfClaimingARepairWasApproved() {
        val snapshot = setupSnapshot(actions = listOf(OnboardingActionFfi.CANCEL_ONBOARDING))
        snapshot.cancellationPending = true
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("Cancellation was interrupted.", substring = true).assertExists()
        composeRule.onNodeWithText("An approved repair must finish", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-CANCEL_ONBOARDING").performScrollTo().performClick()
        assertEquals(OnboardingActionFfi.CANCEL_ONBOARDING, actions.single().action)
    }

    private fun show(state: AccountSetupState) {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountSetupContent(
                    state,
                    actions::add,
                    { _, action, _ -> edits += action },
                    {},
                    {},
                    {},
                    { reconnected++ },
                    { opened++ },
                    { later++ },
                )
            }
        }
    }
}
