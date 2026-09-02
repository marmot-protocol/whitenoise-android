package dev.ipf.whitenoise.android.ui

import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOverlayBackRegistrar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composer-level coverage for the focused-composer Back contract.
 *
 * Scope boundary: these are JVM/Robolectric tests. They drive real IME window
 * insets through [ViewCompat.dispatchApplyWindowInsets], which is the same
 * state `WindowInsets.ime` and `WindowInsets.imeAnimationTarget` read from, but
 * they cannot reproduce the platform's real ordering between an IME animation,
 * a keyboard-to-voice handoff, and a gesture/predictive Back dispatch. That
 * ordering — and the actual pre-IME Back interception on a physical keyboard —
 * still needs a device pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerImeHandoffBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * While the composer owns focus it must hold an overlay-priority Back
     * callback so Back reaches it before the IME, invoking that callback must
     * clear focus, and losing focus must release the registration.
     */
    @Test
    fun focusedComposerHoldsAnOverlayBackCallbackUntilItLosesFocus() {
        val harness = harness()

        harness.focusComposer()
        harness.composer.assertIsFocused()
        assertEquals(OnBackInvokedDispatcher.PRIORITY_OVERLAY, harness.overlayPriority)
        assertNotNull(harness.overlayCallback)

        composeRule.runOnIdle { checkNotNull(harness.overlayCallback).onBackInvoked() }
        composeRule.waitForIdle()

        harness.composer.assertIsNotFocused()
        assertNull("losing focus must release the overlay registration", harness.overlayCallback)
        assertEquals("Back dismisses the composer, it must not clear the draft", DRAFT, harness.value.text)
    }

    /**
     * An IME collapse is not a Back press. A keyboard-to-voice handoff drops the
     * IME insets to zero while the user is still composing, so the draft, the
     * caret and the overlay registration all have to survive that edge.
     */
    @Test
    fun imeCollapseNeitherInvokesOverlayBackNorDisturbsTheFocusedDraft() {
        val harness = harness()

        harness.focusComposer()
        val registeredCallback = checkNotNull(harness.overlayCallback)

        harness.dispatchImeBottom(300)
        harness.composer.assertIsFocused()

        harness.dispatchImeBottom(0)

        val caret =
            harness.composer
                .fetchSemanticsNode()
                .config
                .getOrNull(TextSelectionRange)
        harness.composer.assertIsFocused()
        assertEquals(0, harness.backInvocations)
        assertEquals(INITIAL_VALUE, harness.value)
        assertEquals(TextRange(2, 7), caret)
        assertSame(
            "an IME geometry change must not churn the overlay registration",
            registeredCallback,
            harness.overlayCallback,
        )
    }

    @Test
    fun keyboardDictationFinishCommitAndImeReopenPreserveFocusDraftAndSelection() {
        val harness = harness()

        harness.focusComposer()
        harness.dispatchImeBottom(300)
        val registeredCallback = checkNotNull(harness.overlayCallback)

        // Third-party keyboard dictation temporarily owns the bottom surface,
        // then commits one large TextFieldValue update before reopening its IME.
        harness.dispatchImeBottom(0)
        composeRule.runOnIdle {
            harness.value =
                TextFieldValue(
                    text = "draft dictated replacement text",
                    selection = TextRange(16),
                )
        }
        composeRule.waitForIdle()
        harness.dispatchImeBottom(300)

        val caret =
            harness.composer
                .fetchSemanticsNode()
                .config
                .getOrNull(TextSelectionRange)
        harness.composer.assertIsFocused()
        assertEquals(0, harness.backInvocations)
        assertEquals(harness.value.selection, caret)
        assertEquals("draft dictated replacement text", harness.value.text)
        assertSame(
            "keyboard-owned dictation must not churn the focused composer's Back owner",
            registeredCallback,
            harness.overlayCallback,
        )
    }

    @Test
    fun overlayBackOwnerCanDeferFocusClearUntilTheImeInsetIsZero() {
        val harness = harness(clearFocusOnBack = false)

        harness.focusComposer()
        harness.dispatchImeBottom(300)
        composeRule.runOnIdle { checkNotNull(harness.overlayCallback).onBackInvoked() }
        composeRule.waitForIdle()

        harness.composer.assertIsFocused()
        assertEquals(1, harness.backInvocations)
        assertEquals(INITIAL_VALUE, harness.value)

        harness.dispatchImeBottom(0)
        harness.composer.assertIsFocused()

        composeRule.runOnIdle { harness.focusManager.clearFocus(force = true) }
        composeRule.waitForIdle()

        harness.composer.assertIsNotFocused()
        assertEquals(DRAFT, harness.value.text)
    }

    private fun harness(clearFocusOnBack: Boolean = true): Harness {
        val harness = Harness()
        composeRule.setContent {
            harness.view = LocalView.current
            harness.focusManager = LocalFocusManager.current
            WhiteNoiseTheme {
                Surface {
                    ComposerPill(
                        textFieldValue = harness.value,
                        composerFocus = harness.focusRequester,
                        emojiPickerOpen = false,
                        onValueChange = { harness.value = it },
                        onEmojiPickerToggle = {},
                        onComposerFocusChanged = {},
                        preImeBackEnabled = true,
                        onPreImeBack = {
                            harness.backInvocations++
                            if (clearFocusOnBack) harness.focusManager.clearFocus(force = true)
                        },
                        overlayBackRegistrar =
                            ComposerOverlayBackRegistrar { priority, callback ->
                                harness.overlayPriority = priority
                                harness.overlayCallback = callback
                                { harness.overlayCallback = null }
                            },
                    )
                }
            }
        }
        return harness
    }

    private inner class Harness {
        var value by mutableStateOf(INITIAL_VALUE)
        var overlayPriority: Int? = null
        var overlayCallback: OnBackInvokedCallback? = null
        var backInvocations = 0
        val focusRequester = FocusRequester()
        lateinit var focusManager: FocusManager
        lateinit var view: View

        val composer: SemanticsNodeInteraction
            get() = composeRule.onNode(hasSetTextAction())

        fun focusComposer() {
            composeRule.runOnIdle { focusRequester.requestFocus() }
            composeRule.waitForIdle()
        }

        fun dispatchImeBottom(bottomPx: Int) {
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
    }

    private companion object {
        const val DRAFT = "draft text"
        val INITIAL_VALUE = TextFieldValue(DRAFT, selection = TextRange(2, 7))
    }
}
