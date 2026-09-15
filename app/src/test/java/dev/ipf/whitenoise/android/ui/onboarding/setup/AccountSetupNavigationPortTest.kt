package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the new destinations with the real controller and its existing native-client test boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupNavigationPortTest {
    @get:Rule val composeRule = createComposeRule()
    private val client = FakeSetupClient()
    private lateinit var controller: AccountSetupController
    private var ownerCurrent = true
    private var exits = 0
    private var opens = 0
    private val visible = mutableStateOf(true)

    /** Checklist navigation does not grant consent, while the explicit native decision retains its revision. */
    @Test fun checklistAndBackAreLocalUntilAnOfferedActionIsChosen() {
        show()
        composeRule.onNodeWithTag("setup-open-chats").assertIsNotEnabled()
        composeRule.onNodeWithTag("setup-step-FOLLOWS").assertHasNoClickAction()
        openProfile()
        assertTrue(client.requests.isEmpty())
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(0, exits)
        openProfile()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(SetupRequest(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.CONTINUE_WITHOUT)),
            client.requests,
        )
        assertEquals(0, opens)
    }

    /** The new detail button cannot bypass the controller's authoritative reread when the checkpoint advances. */
    @Test fun staleRenderedDecisionNeverReachesTheNativeExecutor() {
        show()
        openProfile()
        composeRule.runOnIdle { client.current = setupSnapshot(revision = 4uL) }
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performClick()
        composeRule.waitForIdle()
        assertTrue(client.requests.isEmpty())
        assertTrue(controller.state.value.staleDecision)
        assertEquals(
            4uL,
            controller.state.value.snapshot
                ?.revision,
        )
    }

    /** Metadata drafts remain process-owned; recreating the view then cancelling never publishes them. */
    @Test fun viewRecreationKeepsTheNativeEditorDraftAndBackOnlyClosesIt() {
        client.metadata = UserProfileMetadataFfi("original", "Alice", "Before", null, null, null, null)
        show()
        openProfile()
        composeRule.onNodeWithTag("setup-action-EDIT_PROFILE").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("About")).performTextReplacement("Local draft")
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNode(hasSetTextAction() and hasText("Local draft")).assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(null, controller.state.value.editor)
        assertTrue(client.requests.isEmpty())
        assertEquals(0, exits)
    }

    /** Saving reads the live shared fields and preserves metadata; native approval is a separate action. */
    @Test fun sharedFieldSaveProposesLiveTextWithoutApprovingOrErasingOtherMetadata() {
        client.metadata =
            UserProfileMetadataFfi(
                "original",
                "Alice",
                "Before",
                "https://photo.example/p.png",
                null,
                null,
                null,
            )
        show()
        openProfile()
        composeRule.onNodeWithTag("setup-action-EDIT_PROFILE").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("About")).performTextReplacement("After")
        composeRule.onNodeWithTag("setup-editor-save").performClick()
        composeRule.waitForIdle()
        val request = client.requests.single()
        assertEquals(OnboardingActionFfi.EDIT_PROFILE, request.action)
        assertEquals(client.metadata?.copy(about = "After"), request.profile)
        assertEquals(0, opens)
    }

    /** A controller invalidated by an account/runtime change rejects even an already visible button. */
    @Test fun oldOwnerCannotSubmitThroughTheDetailRoute() {
        show()
        openProfile()
        composeRule.runOnIdle { ownerCurrent = false }
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performClick()
        composeRule.waitForIdle()
        assertTrue(client.requests.isEmpty())
        assertEquals(0, opens)
    }

    /** An advancing native checkpoint returns to the checklist rather than offering the old step's actions. */
    @Test fun nextNativeDecisionRequiresOpeningItsOwnRow() {
        show()
        openProfile()
        composeRule.runOnIdle {
            client.current = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY), revision = 4uL)
            client.updates.trySend(client.current)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").assertDoesNotExist()
        composeRule.onNodeWithTag("setup-step-RELAYS").performScrollTo().performClick()
        composeRule.onNodeWithTag("setup-action-RETRY").performClick()
        composeRule.waitForIdle()
        assertEquals(4uL, client.requests.single().revision)
        assertEquals(OnboardingStepFfi.RELAYS, client.requests.single().step)
    }

    /** Current native row navigation is scrollable at every supported checklist position. */
    private fun openProfile() {
        composeRule.onNodeWithTag("setup-step-PROFILE").performScrollTo().performClick()
    }

    /** Keeps the controller above view visibility, matching the actual coordinator's process ownership. */
    private fun show() {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            controller =
                remember {
                    AccountSetupController(
                        SETUP_TEST_ACCOUNT,
                        client,
                        scope,
                        { ownerCurrent },
                        { opens++ },
                        { exits++ },
                    )
                }
            LaunchedEffect(controller) { controller.reconnect() }
            WhiteNoiseTheme {
                if (visible.value) AccountSetupScreen(controller) { exits++ }
            }
        }
        composeRule.waitForIdle()
    }
}
