package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Account setup keeps its decision actions pinned outside the scrolling reasons, on the shared onboarding page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupPinnedActionsTest {
    @get:Rule val composeRule = createComposeRule()
    private val actions = mutableListOf<SetupRequest>()
    private var opened = 0
    private var reconnected = 0

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

    /** The surface follows the editor, readiness, a running check and the native decision in that order. */
    @Test fun surfaceClassificationMatchesTheRenderedBranch() {
        val decision = setupSnapshot(OnboardingStepFfi.PROFILE)
        assertEquals(SetupSurface.Progress, AccountSetupState().setupSurface())
        assertEquals(SetupSurface.Progress, AccountSetupState(snapshot = decision, busy = true).setupSurface())
        assertEquals(SetupSurface.Decision, AccountSetupState(snapshot = decision).setupSurface())
        assertEquals(SetupSurface.Decision, AccountSetupState(snapshot = decision, error = true).setupSurface())
        assertEquals(SetupSurface.Ready, AccountSetupState(snapshot = setupSnapshot(ready = true)).setupSurface())
    }

    /** Finds a tagged node that no scrolling container owns, i.e. one in the pinned action bar. */
    private fun pinned(tag: String) = composeRule.onNode(hasTestTag(tag) and !hasAnyAncestor(hasScrollAction()))

    /** Renders the real setup content with recording callbacks. */
    private fun show(state: AccountSetupState) {
        composeRule.setContent {
            WhiteNoiseTheme {
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
}
