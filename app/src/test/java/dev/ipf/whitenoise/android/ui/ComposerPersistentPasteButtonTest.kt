package dev.ipf.whitenoise.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ComposerPersistentPasteButtonTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val toolbar = PasteToolbar()
    private val account = mutableStateOf("account-a")
    private val group = mutableStateOf("group-a")
    private val edit = mutableStateOf<String?>(null)
    private val mounted = mutableStateOf(true)
    private lateinit var state: ComposerTextState
    private var focusGains = 0
    private var shows = 0
    private var hides = 0
    private var accepted = true
    private val sends = mutableListOf<String>()

    private fun render() {
        composeRule.setContent {
            state = rememberComposerTextState(draftKey = account.value to group.value, initialDraft = TextFieldValue())
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                WhiteNoiseTheme {
                    Surface {
                        if (mounted.value) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { text, acknowledge ->
                                    sends.add(text)
                                    if (accepted) acknowledge()
                                },
                                textState = state,
                                draftKey = account.value to group.value,
                                draftAccountRef = account.value,
                                draftGroupIdHex = group.value,
                                editingMessageId = edit.value,
                                editingInitialText = "edit body",
                                softwareKeyboardController =
                                    object : SoftwareKeyboardController {
                                        override fun show() {
                                            shows++
                                        }

                                        override fun hide() {
                                            hides++
                                        }
                                    },
                                onComposerFocusChanged = { if (it) focusGains++ },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun clip(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("sample", text))
    }

    private fun paste() {
        composeRule.onNodeWithTag("composer.paste").assertIsDisplayed().performClick()
    }

    @Test fun collapsedRepeatedPasteAndSendKeepKeyboardClosedAndControlVisible() {
        clip("clipboard")
        render()
        paste()
        clip(" message\nsecond line")
        paste()
        composeRule.runOnIdle {
            assertEquals("clipboard message\nsecond line", state.valueState.value.text)
            assertEquals(0, focusGains)
            assertEquals(0, shows)
            assertEquals(0, hides)
        }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("clipboard message\nsecond line"), sends)
            assertEquals("", state.valueState.value.text)
            assertEquals(0, focusGains)
            assertEquals(0, shows)
        }
        composeRule.onNodeWithTag("composer.paste").assertIsDisplayed()
    }

    /** Both taps read the accepted owner value even when Compose has not consumed the first edit. */
    @Test fun twoPasteActionsBeforeRecompositionAppendToTheLiveDraft() {
        clip("clipboard")
        render()
        val pasteAction =
            requireNotNull(
                composeRule
                    .onNodeWithTag("composer.paste")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        composeRule.runOnIdle {
            assertTrue(pasteAction())
            assertTrue(pasteAction())
            assertEquals(TextFieldValue("clipboardclipboard", TextRange(18)), state.valueState.value)
            assertEquals(0, focusGains)
            assertEquals(0, shows)
            assertEquals(0, hides)
        }
    }

    @Test fun typingPasteReplacesOnlySelectionAndPreservesFocus() {
        clip("insert")
        render()
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.runOnIdle {
            state.valueState.value = TextFieldValue("left selected right", TextRange(13, 5), TextRange(5, 13))
        }
        composeRule.waitForIdle()
        val before = Triple(focusGains, shows, hides)
        paste()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(TextFieldValue("left insert right", TextRange(11)), state.valueState.value)
            assertEquals(before, Triple(focusGains, shows, hides))
        }
        clip("!")
        paste()
        composeRule.runOnIdle { assertEquals("left insert! right", state.valueState.value.text) }
    }

    @Test fun emptyOrUnsupportedClipboardPreservesDraftAndRejectedSendRetainsPastedText() {
        clip("draft")
        render()
        paste()
        composeRule.runOnIdle { clipboard.clearPrimaryClip() }
        paste()
        composeRule.runOnIdle {
            assertEquals("draft", state.valueState.value.text)
            assertNotNull(toolbar.paste)
            clipboard.setPrimaryClip(
                ClipData("image", arrayOf("image/png"), ClipData.Item(android.net.Uri.parse("content://test/image"))),
            )
        }
        paste()
        composeRule.runOnIdle { assertEquals("draft", state.valueState.value.text) }
        accepted = false
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle { assertEquals("draft", state.valueState.value.text) }
    }

    @Test fun trustedFallbackPastesAfterExplicitGrantWithoutFocus() {
        clipboard.clearPrimaryClip()
        render()
        paste()
        composeRule.runOnIdle {
            val grant = checkNotNull(toolbar.paste)
            clip("granted")
            grant()
        }
        composeRule.runOnIdle {
            assertEquals("granted", state.valueState.value.text)
            assertEquals(0, shows)
            assertEquals(0, focusGains)
        }
    }

    @Test fun delayedSystemPasteCannotCrossAccountChatEditOrDisposal() {
        clipboard.clearPrimaryClip()
        render()
        val switches: List<() -> Unit> =
            listOf(
                { account.value = "account-b" },
                { group.value = "group-b" },
                { edit.value = "edit-a" },
                { edit.value = "edit-b" },
                { mounted.value = false },
            )
        for (switch in switches) {
            clipboard.clearPrimaryClip()
            paste()
            lateinit var stale: () -> Unit
            composeRule.runOnIdle {
                stale = checkNotNull(toolbar.paste)
                switch()
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                val before = state.valueState.value
                clip("must not insert")
                stale()
                assertEquals(before, state.valueState.value)
            }
        }
    }

    private class PasteToolbar : TextToolbar {
        var paste: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            paste = onPasteRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            paste = null
            status = TextToolbarStatus.Hidden
        }
    }
}
