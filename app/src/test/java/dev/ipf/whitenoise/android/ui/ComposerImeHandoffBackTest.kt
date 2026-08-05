package dev.ipf.whitenoise.android.ui

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOverlayBackRegistrar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerImeHandoffBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun imeHandoffPreservesFocusedDraftUntilExplicitOverlayBack() {
        val initialValue = TextFieldValue("draft text", selection = TextRange(2, 7))
        var value by mutableStateOf(initialValue)
        var imeTargetOpen by mutableStateOf(true)
        var focused = false
        var overlayPriority: Int? = null
        var overlayCallback: OnBackInvokedCallback? = null
        lateinit var focusManager: FocusManager
        val focusRequester = FocusRequester()

        composeRule.setContent {
            focusManager = LocalFocusManager.current
            WhiteNoiseTheme {
                Surface {
                    ComposerPill(
                        textFieldValue = value,
                        composerFocus = focusRequester,
                        emojiPickerOpen = false,
                        onValueChange = { value = it },
                        onEmojiPickerToggle = {},
                        onAttachmentsToggle = {},
                        attachmentSheetOpen = false,
                        onPickFromGallery = null,
                        onPickDocument = null,
                        onComposerFocusChanged = { focused = it },
                        preImeBackEnabled = true,
                        onPreImeBack = { focusManager.clearFocus(force = true) },
                        overlayBackRegistrar =
                            ComposerOverlayBackRegistrar { priority, callback ->
                                overlayPriority = priority
                                overlayCallback = callback
                                { overlayCallback = null }
                            },
                        modifier =
                            Modifier.semantics {
                                stateDescription = if (imeTargetOpen) "IME target open" else "IME target closed"
                            },
                    )
                }
            }
        }

        val composer = composeRule.onNodeWithText("draft text")
        composeRule.runOnIdle { focusRequester.requestFocus() }
        composeRule.waitForIdle()
        composer.assertIsFocused()
        assertEquals(OnBackInvokedDispatcher.PRIORITY_OVERLAY, overlayPriority)
        assertNotNull(overlayCallback)

        composeRule.runOnIdle { imeTargetOpen = false }
        composeRule.waitForIdle()

        composer.assertIsFocused()
        assertEquals(initialValue, value)
        assertNotNull(overlayCallback)

        composeRule.runOnIdle { checkNotNull(overlayCallback).onBackInvoked() }
        composeRule.waitForIdle()

        composer.assertIsNotFocused()
        assertEquals(initialValue.text, value.text)
        assertNull(overlayCallback)
    }
}
