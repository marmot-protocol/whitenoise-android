package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
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
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adaptive navigation and ownership tests for the checklist and shared editor fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupChecklistPresentationTest {
    @get:Rule val composeRule = createComposeRule()

    /** Readiness never ignores a saved cancellation even if a malformed snapshot also sets ready. */
    @Test fun pendingCancellationCannotOpenChats() {
        val snapshot = setupSnapshot(ready = true).copy(cancellationPending = true)
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountSetupContent(AccountSetupState(snapshot), {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithTag("setup-open-chats").assertIsNotEnabled()
    }

    /** Native work disables navigation into actions; a later ready snapshot can enable the primary. */
    @Test fun busyRowsDoNotExposeDecisionsAndFreshReadyUpdatesTheButton() {
        val state = mutableStateOf(AccountSetupState(snapshot = setupSnapshot(), busy = true))
        composeRule.setContent {
            WhiteNoiseTheme { AccountSetupContent(state.value, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}) }
        }
        composeRule.onNodeWithTag("setup-step-PROFILE").assertHasNoClickAction()
        composeRule.onNodeWithTag("setup-open-chats").assertIsNotEnabled()
        composeRule.runOnIdle { state.value = AccountSetupState(snapshot = setupSnapshot(ready = true)) }
        composeRule.onNodeWithTag("setup-open-chats").assertIsEnabled()
    }

    /** Large-font layouts can reach the last actionable checkpoint and its consent without using a fixed height. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun shortLargeFontViewportKeepsLastCheckpointAndNativeActionReachable() {
        var calls = 0
        val snapshot = setupSnapshot(OnboardingStepFfi.KEY_PACKAGE, listOf(OnboardingActionFfi.RECONNECT_SIGNER))
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                AccountSetupContent(AccountSetupState(snapshot), { calls++ }, { _, _, _ -> }, {}, {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithTag("setup-step-KEY_PACKAGE").performScrollTo().performClick()
        composeRule.onNodeWithTag("setup-action-RECONNECT_SIGNER").performScrollTo().performClick()
        assertEquals(1, calls)
    }

    /** Back during native work retains the existing cancellation owner, including a busy editor. */
    @Test fun busyEditorBackStillInvokesTheSaveAndExitOwner() {
        var exits = 0
        val state =
            AccountSetupState(
                snapshot = setupSnapshot(),
                busy = true,
                editor = SetupEditor(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE),
            )
        composeRule.setContent {
            WhiteNoiseTheme { AccountSetupContent(state, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { exits++ }) }
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, exits)
    }

    /** The field snapshot used by Save sees edits before any coroutine-based draft mirroring can run. */
    @Test fun saveSnapshotReadsTheLastInputSynchronously() {
        val editor = SetupEditor(3uL, OnboardingStepFfi.PROFILE, OnboardingActionFfi.EDIT_PROFILE)
        val fields = SetupEditorFields(editor)
        fields.displayName.edit { replace(0, length, "Last IME edit") }
        fields.about.edit { replace(0, length, "New about") }
        assertEquals("Last IME edit", fields.current(editor).displayName)
        assertEquals("New about", fields.current(editor).about)
    }

    /** Identical step/revision values from another account must never reuse the previous account's field buffers. */
    @Test fun accountChangeDropsOldEditorFieldsEvenWhenNativeRevisionsMatch() {
        val state =
            mutableStateOf(
                AccountSetupState(
                    snapshot = setupSnapshot(),
                    editor =
                        SetupEditor(
                            3uL,
                            OnboardingStepFfi.PROFILE,
                            OnboardingActionFfi.EDIT_PROFILE,
                            displayName = "Alice",
                        ),
                ),
            )
        composeRule.setContent {
            WhiteNoiseTheme { AccountSetupContent(state.value, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}) }
        }
        composeRule.onNode(hasSetTextAction() and hasText("Alice")).performTextReplacement("Private draft")
        composeRule.runOnIdle {
            state.value =
                AccountSetupState(
                    snapshot = setupSnapshot().copy(accountIdHex = "cd".repeat(32)),
                    editor =
                        SetupEditor(
                            3uL,
                            OnboardingStepFfi.PROFILE,
                            OnboardingActionFfi.EDIT_PROFILE,
                            displayName = "Bob",
                        ),
                )
        }
        composeRule.onNode(hasSetTextAction() and hasText("Bob")).assertExists()
        composeRule.onNode(hasSetTextAction() and hasText("Private draft")).assertDoesNotExist()
    }
}
