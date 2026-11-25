package dev.ipf.whitenoise.android.ui.onboarding

import android.view.View
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupContent
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupState
import dev.ipf.whitenoise.android.ui.onboarding.setup.SetupEditor
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupSnapshot
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A landscape phone with the keyboard open leaves the onboarding page about 100 dp under its top bar. The pinned
 * actions must give way there: the field being edited keeps a usable height and the actions scroll after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
class OnboardingShortWindowImeTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var view: View
    private var saves = 0
    private var signIns = 0

    /** The setup editor's field stays visible above the keyboard, and Save scrolls into reach and still saves. */
    @Test fun setupEditorKeepsItsFieldAndSaveAboveTheKeyboard() {
        showSetupEditor(fontScale = 1f)
        assertFieldAndActionSurviveTheKeyboard(hasSetTextAction(), "setup-editor-save")
        assertEquals(1, saves)
    }

    /** At 200% font the taller buttons still give way to the setup editor's field. */
    @Test fun setupEditorKeepsItsFieldAtLargeFont() {
        showSetupEditor(fontScale = 2f)
        assertFieldAndActionSurviveTheKeyboard(hasSetTextAction(), "setup-editor-save")
        assertEquals(1, saves)
    }

    /** The sign-in key field stays visible above the keyboard, and Sign in scrolls into reach and still submits. */
    @Test fun signInKeepsTheKeyFieldAndActionAboveTheKeyboard() {
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme {
                SignInContent(
                    SECRET,
                    false,
                    null,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = { signIns++ },
                    privateKeyState = TextFieldState(SECRET),
                )
            }
        }
        assertFieldAndActionSurviveTheKeyboard(
            hasTestTag("onboarding.sign_in.private_key"),
            "onboarding.sign_in.action",
        )
        assertEquals(1, signIns)
    }

    /** Closing the keyboard gives the page its height back, so the editor's actions are pinned again. */
    @Test fun closingTheKeyboardPinsTheActionsAgain() {
        showSetupEditor(fontScale = 1f)
        dispatchImeBottom(IME_HEIGHT_PX)
        assertFalse(isPinned("setup-editor-save"))
        dispatchImeBottom(0)
        assertTrue(isPinned("setup-editor-save"))
        assertTrue(isPinned("setup-editor-cancel"))
    }

    /** The pinning rule leaves the content at least its minimum height beside the bar and its inset. */
    @Test fun pinningNeedsRoomForTheContentAndTheBar() {
        assertTrue(pinsActions(available = 300.dp, actionsHeight = 148.dp))
        assertFalse(pinsActions(available = 300.dp, actionsHeight = 149.dp))
        assertFalse(pinsActions(available = 96.dp, actionsHeight = 0.dp))
    }

    /**
     * Scrolls to and focuses the field, opens a 200 dp keyboard, and checks that the scrolling column keeps every
     * pixel between the top bar and the keyboard with the focused field inside it. The action then sits after the
     * fields instead of in the pinned bar, and still scrolls into reach and dispatches.
     */
    private fun assertFieldAndActionSurviveTheKeyboard(
        field: SemanticsMatcher,
        actionTag: String,
    ) {
        val input = composeRule.onAllNodes(field).onFirst()
        input.performScrollTo().performClick().assertIsFocused()
        dispatchImeBottom(IME_HEIGHT_PX)

        val viewport = composeRule.onNode(hasScrollAction() and hasAnyDescendant(field)).fetchSemanticsNode()
        val keyboardTop = WINDOW_HEIGHT_PX - IME_HEIGHT_PX
        assertEquals("the fields reach down to the keyboard", keyboardTop, viewport.boundsInRoot.bottom, 1f)
        assertTrue(
            "the fields keep the height under the top bar, got ${viewport.boundsInRoot.height}",
            viewport.boundsInRoot.height >= MIN_VIEWPORT_HEIGHT_PX,
        )
        input.assertIsDisplayed().assertIsFocused()
        val visible = input.fetchSemanticsNode().boundsInRoot.intersect(viewport.boundsInRoot)
        assertTrue("the focused field stays in view, got ${visible.height}", visible.height >= MIN_FIELD_HEIGHT_PX)

        assertFalse("the action gives way to the fields", isPinned(actionTag))
        action(actionTag).performScrollTo().assertIsDisplayed().performClick()
    }

    /** Renders the real setup content with the profile editor open. */
    private fun showSetupEditor(fontScale: Float) {
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme(fontScale = fontScale) {
                AccountSetupContent(
                    AccountSetupState(
                        snapshot = setupSnapshot(),
                        editor = SetupEditor(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE),
                    ),
                    {},
                    { _, _, _ -> },
                    {},
                    { saves++ },
                    {},
                    {},
                    {},
                    {},
                    { "Quiet Otter" },
                )
            }
        }
    }

    /** Whether the tagged action sits in the pinned bar rather than in the scrolling column. */
    private fun isPinned(tag: String): Boolean =
        composeRule
            .onAllNodes(hasTestTag(tag) and !hasAnyAncestor(hasScrollAction()))
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** The single node carrying [tag], wherever it renders. */
    private fun action(tag: String): SemanticsNodeInteraction = composeRule.onNode(hasTestTag(tag))

    /** Applies a keyboard inset of [bottomPx] to the window, as the system does when the IME opens or closes. */
    private fun dispatchImeBottom(bottomPx: Int) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    private companion object {
        /** A 200 dp keyboard at mdpi. */
        const val IME_HEIGHT_PX = 200

        /** The h360dp window at mdpi. */
        const val WINDOW_HEIGHT_PX = 360f

        /** One single-line text field's input surface. */
        const val MIN_FIELD_HEIGHT_PX = 48f

        /** The 96 dp between a 64 dp top bar and the keyboard, less rounding; pinned actions would take all of it. */
        const val MIN_VIEWPORT_HEIGHT_PX = 90f

        /** A well-formed synthetic key, so the sign-in action is enabled; it is never imported. */
        val SECRET = "nsec1" + "q".repeat(58)
    }
}
