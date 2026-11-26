package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Account setup keeps its decision actions pinned outside the scrolling reasons, on the shared onboarding page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupPinnedActionsTest {
    @get:Rule val composeRule = createComposeRule()
    private val actions = mutableListOf<SetupRequest>()
    private var opened = 0
    private var reconnected = 0
    private var primary = 0

    /** The page has its own title rather than repeating the sign-in screen's. */
    @Test fun titleNamesAccountSetup() {
        show(AccountSetupState(snapshot = setupSnapshot()))
        composeRule.onNodeWithText("Account setup").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertDoesNotExist()
    }

    /** The native decision's filled action sits in the pinned bar and still dispatches the exact revision. */
    @Test fun decisionPrimaryActionIsPinned() {
        show(AccountSetupState(snapshot = setupSnapshot(actions = listOf(OnboardingActionFfi.CONTINUE_WITHOUT))))
        pinned("setup-action-CONTINUE_WITHOUT").assertIsDisplayed().performClick()
        assertEquals(OnboardingActionFfi.CONTINUE_WITHOUT, actions.single().action)
        assertEquals(3uL, actions.single().revision)
    }

    /** A stopped setup shows the error callout in the content and pins Resume checks as the way forward. */
    @Test fun failureShowsCalloutAndPinnedResume() {
        show(AccountSetupState(snapshot = setupSnapshot(), error = true))
        composeRule.onNodeWithTag("setup-failure").assertIsDisplayed()
        composeRule.onNodeWithText("Setup stopped. Resume to try again.").assertIsDisplayed()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertDoesNotExist()
        pinned("setup-resume-checks").assertIsEnabled().performClick()
        assertEquals(1, reconnected)
    }

    /** Once native setup is ready, Open chats is the pinned action. */
    @Test fun readyPinsOpenChats() {
        show(AccountSetupState(snapshot = setupSnapshot(ready = true)))
        pinned("setup-open-chats").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    /** A lost signer pins Reconnect as the one filled action; Resume beside it stays outlined. */
    @Test fun disconnectedSignerPinsOneFilledAction() {
        show(
            AccountSetupState(
                snapshot = setupSnapshot(OnboardingStepFfi.KEY_PACKAGE, listOf(OnboardingActionFfi.RECONNECT_SIGNER)),
                disconnected = true,
            ),
        )
        assertEquals("Reconnect is the filled primary", primary, containerPixel("setup-action-RECONNECT_SIGNER"))
        assertNotEquals("Resume must not be a second filled primary", primary, containerPixel("setup-resume-checks"))
        pinned("setup-resume-checks").performClick()
        assertEquals(1, reconnected)
    }

    /** A lost signer beside a retired relay keeps Reconnect as the one filled action; Remove turns outlined. */
    @Test fun disconnectedRelayRemovalIsNotASecondFilledAction() {
        val snapshot =
            setupSnapshot(
                OnboardingStepFfi.RELAYS,
                listOf(OnboardingActionFfi.EDIT_RELAYS, OnboardingActionFfi.RECONNECT_SIGNER),
            )
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(
                setupFinding(
                    OnboardingIssueFfi.RETIRED_RELAY,
                    "wss://retired.example",
                    actions = listOf(OnboardingActionFfi.EDIT_RELAYS),
                ),
            )
        show(AccountSetupState(snapshot = snapshot, disconnected = true))
        assertEquals("Reconnect is the filled primary", primary, containerPixel("setup-action-RECONNECT_SIGNER"))
        assertEquals(
            "Remove must be outlined, showing the bar behind it, not a second filled action",
            backgroundBeside("setup-remove-relay"),
            containerPixel("setup-remove-relay"),
        )
    }

    /** A failure disables Remove relay, so it turns outlined and Resume becomes the one filled action. */
    @Test fun failureBesideRetiredRelayFillsResumeNotDisabledRemoval() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.EDIT_RELAYS))
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(
                setupFinding(
                    OnboardingIssueFfi.RETIRED_RELAY,
                    "wss://retired.example",
                    actions = listOf(OnboardingActionFfi.EDIT_RELAYS),
                ),
            )
        show(AccountSetupState(snapshot = snapshot, error = true))
        pinned("setup-remove-relay").assertIsNotEnabled()
        assertEquals("Resume is the filled way forward", primary, containerPixel("setup-resume-checks"))
        assertEquals(
            "Disabled Remove must be outlined, showing the bar behind it",
            backgroundBeside("setup-remove-relay"),
            containerPixel("setup-remove-relay"),
        )
    }

    /** With no decision action left, Resume is the filled way forward. */
    @Test fun failureAloneFillsResume() {
        show(AccountSetupState(snapshot = setupSnapshot(), staleDecision = true))
        composeRule.onNodeWithTag("setup-failure").assertIsDisplayed()
        assertEquals("Resume is filled when it is the only action", primary, containerPixel("setup-resume-checks"))
    }

    /** The editor pins only Save and Cancel; a failure's Resume stays in the content beside the callout. */
    @Test fun editorFailureKeepsResumeOutOfThePinnedBar() {
        show(
            AccountSetupState(
                snapshot = setupSnapshot(),
                editor = SetupEditor(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE),
                error = true,
            ),
        )
        pinned("setup-editor-save").assertExists()
        pinned("setup-editor-cancel").assertExists()
        composeRule.onNode(hasTestTag("setup-resume-checks") and hasAnyAncestor(hasScrollAction())).performClick()
        assertEquals(1, reconnected)
    }

    /** A running check or a decision offering nothing pins no empty action band; a failure still pins Resume. */
    @Test fun runningCheckHasNoPinnedBar() {
        assertFalse(AccountSetupState().hasPinnedActions)
        assertFalse(AccountSetupState(snapshot = setupSnapshot(actions = emptyList())).hasPinnedActions)
        assertTrue(AccountSetupState(snapshot = setupSnapshot(actions = emptyList()), error = true).hasPinnedActions)
        assertTrue(AccountSetupState(snapshot = setupSnapshot(), busy = true, error = true).hasPinnedActions)
        assertTrue(AccountSetupState(snapshot = setupSnapshot(ready = true)).hasPinnedActions)
    }

    /** The surface follows the editor, readiness, a running check and the native decision in that order. */
    @Test fun surfaceClassificationMatchesTheRenderedBranch() {
        val decision = setupSnapshot(OnboardingStepFfi.PROFILE)
        assertEquals(SetupSurface.Progress, AccountSetupState().setupSurface())
        assertEquals(SetupSurface.Progress, AccountSetupState(snapshot = decision, busy = true).setupSurface())
        assertEquals(SetupSurface.Decision, AccountSetupState(snapshot = decision).setupSurface())
        assertEquals(SetupSurface.Decision, AccountSetupState(snapshot = decision, error = true).setupSurface())
        assertEquals(SetupSurface.Ready, AccountSetupState(snapshot = setupSnapshot(ready = true)).setupSurface())
    }

    /** A failed inbox check explains the step and pins Try again beside the suggested review. */
    @Test fun failedInboxCheckExplainsAndPinsRetry() {
        show(AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot()))
        composeRule.onNodeWithText("Inbox relays are where people send you chat invitations.").assertIsDisplayed()
        composeRule.onNodeWithText("Some relays didn’t answer, so this check couldn’t finish.").assertIsDisplayed()
        assertEquals("Try again is the filled primary", primary, containerPixel("setup-action-RETRY"))
        pinned("setup-action-USE_RECOMMENDED_RELAYS").assertIsDisplayed()
        composeRule.onNodeWithTag("setup-action-EDIT_DISCOVERY_RELAYS").assertDoesNotExist()
        pinned("setup-action-RETRY").performClick()
        assertEquals(OnboardingActionFfi.RETRY, actions.single().action)
    }

    /** A refused Save says why and that nothing was published; only Try again is pinned. */
    @Test fun refusedSaveExplainsAndPinsOnlyRetry() {
        show(AccountSetupState(snapshot = refusedSaveSnapshot()))
        composeRule
            .onNodeWithText(
                "The final relay check couldn’t finish. Your changes weren’t saved or published.",
            ).assertIsDisplayed()
        pinned("setup-action-RETRY").assertIsDisplayed()
        composeRule
            .onNode(hasTestTag("setup-action-EDIT_DISCOVERY_RELAYS") and !hasAnyAncestor(hasScrollAction()))
            .assertDoesNotExist()
    }

    /** Details lists a relay problem recorded on a step that passed, under that step. */
    @Test fun detailsListPassedStepWarnings() {
        show(AccountSetupState(snapshot = passedWithWarningsSnapshot(), detailsExpanded = true))
        composeRule.onNodeWithTag("setup-passed-warnings").scrollToIfScrollable().assertIsDisplayed()
        composeRule.onNodeWithText("Setup continued, but these relays had a problem.").assertExists()
    }

    /** A passed cleanup action carries that step's grant into the native preview. */
    @Test fun passedWarningCleanupTargetsItsOwnStep() {
        show(AccountSetupState(snapshot = passedWithCleanupSnapshot(), detailsExpanded = true))
        composeRule.onNodeWithTag("setup-action-REMOVE_UNUSABLE_RELAYS").scrollToIfScrollable().performClick()
        assertEquals(
            SetupRequest(3uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS),
            actions.single(),
        )
    }

    /** A cleanup for a passed step opens its preview with Save and that preview's own reason. */
    @Test fun passedCleanupPreviewShowsTheNativeRemovalReason() {
        val snapshot = passedCleanupPreviewSnapshot()
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings = emptyList()
        show(AccountSetupState(snapshot))
        pinned("setup-action-APPROVE_REPAIR").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("This relay requires authentication.").assertExists()
        assertEquals(SetupRequest(4uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.APPROVE_REPAIR), actions.single())
    }

    /** Samples a node's container colour just inside its leading edge, where a filled button differs from outlined. */
    private fun containerPixel(tag: String): Int {
        val image = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return image[CONTAINER_SAMPLE_X, image.height / 2].toArgb()
    }

    /** Samples the page just outside a node's leading edge, the colour an outlined button's container shows. */
    private fun backgroundBeside(tag: String): Int {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().captureToImage().toPixelMap()
        return root[(bounds.left - BACKGROUND_SAMPLE_OFFSET).toInt(), bounds.center.y.toInt()].toArgb()
    }

    /** Finds a tagged node that no scrolling container owns, i.e. one in the pinned action bar. */
    private fun pinned(tag: String) = composeRule.onNode(hasTestTag(tag) and !hasAnyAncestor(hasScrollAction()))

    /** Renders the real setup content with recording callbacks. */
    private fun show(state: AccountSetupState) {
        composeRule.setContent {
            WhiteNoiseTheme {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                AccountSetupContent(
                    state,
                    actions::add,
                    { _, _, _ -> },
                    {},
                    {},
                    {},
                    { reconnected++ },
                    { opened++ },
                    {},
                    { "Quiet Otter" },
                )
            }
        }
    }

    private companion object {
        /** Far enough inside a 56 dp pill to clear its 1 dp outline and rounded corner at mdpi. */
        const val CONTAINER_SAMPLE_X = 14

        /** Inside the bar's 16 dp inset, clear of the button's outline. */
        const val BACKGROUND_SAMPLE_OFFSET = 8f
    }
}
