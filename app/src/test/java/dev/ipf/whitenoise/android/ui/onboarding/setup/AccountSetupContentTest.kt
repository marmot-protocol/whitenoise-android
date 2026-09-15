package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
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
    private var details = 0

    /** Missing profile metadata offers working edit and skip choices without expanding the checklist. */
    @Test
    fun missingProfileOffersEditAndSkip() {
        show(AccountSetupState(snapshot = setupSnapshot()))
        composeRule.onNodeWithTag("setup-action-EDIT_PROFILE").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertIsDisplayed().performClick()
        assertEquals(listOf(OnboardingActionFfi.EDIT_PROFILE), edits)
        val skip = SetupRequest(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.CONTINUE_WITHOUT)
        assertEquals(listOf(skip), actions)
        composeRule.onNodeWithText("Add a name so people recognize you.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Follow list").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-details").assertIsDisplayed()
    }

    /** Visible actions preserve their decision revision and recovery epoch and invoke the matching callback. */
    @Test
    fun everyOfferedActionHasAWorkingCallbackAndUsesRenderedRevision() {
        val snapshot = setupSnapshot(actions = OnboardingActionFfi.entries).copy(recoveryEpoch = "reviewed-epoch")
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.PROFILE,
                3uL,
                null,
                emptyList(),
                emptyList(),
                null,
                null,
            )
        show(AccountSetupState(snapshot = snapshot, detailsExpanded = true))
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
        assertTrue(actions.all { it.recoveryEpoch == "reviewed-epoch" })
    }

    /** A mutable native record cannot change the epoch paired with an already rendered decision revision. */
    @Test
    fun deviceCallbackKeepsTheRenderedEpochWhenTheRecordChanges() {
        val snapshot =
            setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY))
                .copy(recoveryEpoch = "displayed-epoch")
        show(AccountSetupState(snapshot = snapshot))
        composeRule.runOnIdle { snapshot.recoveryEpoch = "later-epoch" }
        composeRule.onNodeWithTag("setup-action-CONTINUE_ANYWAY").performClick()
        assertEquals("displayed-epoch", actions.single().recoveryEpoch)
        assertEquals(3uL, actions.single().revision)
    }

    /** A saved repair for another step must not hide profile help or imply publication at this step. */
    @Test
    fun unrelatedProposalDoesNotChangeTheCurrentDecision() {
        val snapshot = setupSnapshot()
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                7uL,
                null,
                listOf("wss://unrelated.example"),
                emptyList(),
                null,
                null,
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("Add a name so people recognize you.", substring = true).assertExists()
        composeRule.onNodeWithText("Review before publishing").assertDoesNotExist()
        composeRule.onNodeWithText("wss://unrelated.example").assertDoesNotExist()
        composeRule.onNodeWithText("Your saved change needs to finish.", substring = true).assertDoesNotExist()
    }

    /** A malformed cross-step approval cannot publish a repair that this screen has not shown. */
    @Test
    fun approvalRequiresAProposalForTheDisplayedStep() {
        val snapshot = setupSnapshot(actions = listOf(OnboardingActionFfi.APPROVE_REPAIR))
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                7uL,
                null,
                emptyList(),
                emptyList(),
                null,
                null,
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithTag("setup-action-APPROVE_REPAIR").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    /** Inbox publication is labeled distinctly from NIP-65 read/write capabilities. */
    @Test
    fun inboxEditorUsesTheInboxLabel() {
        show(
            AccountSetupState(
                snapshot = setupSnapshot(OnboardingStepFfi.INBOX_RELAYS),
                editor = SetupEditor(3uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.EDIT_RELAYS),
            ),
        )
        composeRule.onNode(hasSetTextAction() and hasText("Inbox relays")).assertExists()
        composeRule.onNodeWithText("Read relays").assertDoesNotExist()
        composeRule.onNodeWithText("Write relays").assertDoesNotExist()
    }

    /** Lookup relay overrides retain their own label even while diagnosing an inbox step. */
    @Test
    fun inboxDiscoveryEditorKeepsTheDiscoveryLabel() {
        show(
            AccountSetupState(
                snapshot = setupSnapshot(OnboardingStepFfi.INBOX_RELAYS),
                editor = SetupEditor(3uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.EDIT_DISCOVERY_RELAYS),
            ),
        )
        composeRule.onNodeWithText("Discovery relays").assertExists()
        composeRule.onNodeWithText("Read relays").assertDoesNotExist()
    }

    /** Follows have no confirmation screen while the controller advances the optional check. */
    @Test
    fun followsAdvanceWithoutAConfirmationOrReplacementEditor() {
        show(
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.FOLLOWS,
                        listOf(
                            OnboardingActionFfi.EDIT_FOLLOWS,
                            OnboardingActionFfi.RETRY,
                            OnboardingActionFfi.CONTINUE_WITHOUT,
                        ),
                    ),
            ),
        )
        composeRule.onNodeWithTag("setup-action-EDIT_FOLLOWS").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertDoesNotExist()
        composeRule.onNodeWithText("Checking your account…").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    /** Prevents duplicate decisions while keeping the non-destructive exit available during work. */
    @Test
    fun busyDecisionsCannotBeTappedButLaterRemainsReachable() {
        show(AccountSetupState(snapshot = setupSnapshot(), busy = true))
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-later").performScrollTo().performClick()
        assertEquals(1, later)
        assertTrue(actions.isEmpty())
    }

    /** Ensures an inconclusive relay lookup never silently offers replacement defaults. */
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

    /** Requires explicit replacement consequences and the proposed read/write endpoints before publication. */
    @Test
    fun proposalShowsExactCapabilitiesAndReplacementWarningBeforeApproval() {
        val snapshot =
            setupSnapshot(
                OnboardingStepFfi.RELAYS,
                listOf(
                    OnboardingActionFfi.APPROVE_REPAIR,
                    OnboardingActionFfi.CANCEL_REPAIR,
                ),
                revision = 5uL,
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
        composeRule.onNodeWithText("Relays left out of this list will be removed.", substring = true).assertExists()
        assertTrue(actions.isEmpty())
        composeRule.onNodeWithTag("setup-action-APPROVE_REPAIR").performScrollTo().performClick()
        assertEquals(3uL, actions.single().revision)
    }

    /** Exercises signer reconnection independently from the completion route. */
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

    /** Keeps a certified account actionable after its native update stream ends. */
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

    /** Explains saved-publication recovery without pretending an approved repair can be discarded. */
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
        composeRule.onNodeWithText("Your saved change needs to finish.", substring = true).assertExists()
        composeRule.onNodeWithTag("setup-action-CANCEL_ONBOARDING").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-RETRY").performScrollTo().performClick()
        assertEquals(OnboardingActionFfi.RETRY, actions.single().action)
    }

    /** Distinguishes an interrupted cancellation from an already approved publication. */
    @Test
    fun interruptedCancellationOffersCancellationInsteadOfClaimingARepairWasApproved() {
        val snapshot =
            setupSnapshot(OnboardingStepFfi.INBOX_RELAYS, listOf(OnboardingActionFfi.CANCEL_ONBOARDING))
        snapshot.cancellationPending = true
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("Cancellation stopped.", substring = true).assertExists()
        composeRule.onNodeWithText("Your saved change needs to finish.", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-CANCEL_ONBOARDING").performScrollTo().performClick()
        assertEquals(OnboardingActionFfi.CANCEL_ONBOARDING, actions.single().action)
        assertEquals(OnboardingStepFfi.INBOX_RELAYS, actions.single().step)
    }

    /** The routine device notice has one decision, with diagnostics and cancellation kept out of the way. */
    @Test fun deviceScreenHasOneDecisionAndOptionalDetails() {
        show(
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.SINGLE_DEVICE,
                        listOf(
                            OnboardingActionFfi.CONTINUE_ANYWAY,
                            OnboardingActionFfi.RETRY,
                            OnboardingActionFfi.CANCEL_ONBOARDING,
                        ),
                    ),
            ),
        )
        composeRule.onNodeWithTag("setup-action-CONTINUE_ANYWAY").assertIsDisplayed()
        composeRule.onNodeWithTag("setup-action-RETRY").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-later").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-action-CANCEL_ONBOARDING").assertDoesNotExist()
        composeRule.onNodeWithText("Follow list").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-details").performClick()
        assertEquals(1, details)
        assertTrue(actions.isEmpty())
    }

    /** Incomplete discovery remains available without expanding the normal acknowledgment copy. */
    @Test fun unknownDeviceDiscoveryAppearsInDetails() {
        show(AccountSetupState(snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE), detailsExpanded = true))
        composeRule
            .onNodeWithText(
                "We could not determine whether another installation exists. " +
                    "Continue only if you want to use this device.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    /** Relay diagnostics identify the exact endpoint before Details or a replacement proposal is opened. */
    @Test fun retiredRelayIsNamedBesideItsFinding() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS))
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(OnboardingFindingFfi(OnboardingIssueFfi.RETIRED_RELAY, "wss://retired.example"))
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("wss://retired.example").assertIsDisplayed()
        composeRule.onNodeWithText("This relay is retired.").assertIsDisplayed()
        composeRule.onNodeWithTag("setup-action-USE_RECOMMENDED_RELAYS").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    /** A real device-check failure keeps an explained, working retry and a non-destructive exit. */
    @Test fun failedDeviceCheckRetainsRetryAndLater() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.RETRY))
        snapshot.steps.first { it.step == OnboardingStepFfi.SINGLE_DEVICE }.apply {
            status = OnboardingStatusFfi.RETRYABLE_FAILURE
            findings = listOf(OnboardingFindingFfi(OnboardingIssueFfi.TIMED_OUT, null))
        }
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("This check timed out.").assertIsDisplayed()
        composeRule.onNodeWithTag("setup-action-RETRY").assertIsDisplayed().performClick()
        assertEquals(OnboardingActionFfi.RETRY, actions.single().action)
        composeRule.onNodeWithTag("setup-later").performScrollTo().performClick()
        assertEquals(1, later)
    }

    /** A ready screen sheds diagnostics and exit links rather than asking the user to finish setup again. */
    @Test fun completionDoesNotKeepSetupChrome() {
        show(AccountSetupState(snapshot = setupSnapshot(ready = true)))
        composeRule.onNodeWithTag("setup-later").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-details").assertDoesNotExist()
        composeRule.onNodeWithText("Follow list").assertExists()
    }

    /** Mounts the stateless screen with callback recorders for the supplied native setup state. */
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
                    { details++ },
                )
            }
        }
        state.checklistInspectionStep?.takeIf { state.editor == null }?.let {
            composeRule.onNodeWithTag("setup-step-${it.name}").performScrollTo().performClick()
        }
    }
}
