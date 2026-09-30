package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An IME can commit a transcript and request Send before Compose renders that edit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class ComposerImeSendTimingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transcriptCommittedToEmptyComposerSendsBeforeNextRender() {
        val state = ComposerTextState(TextFieldValue(""))
        val sent = render(state)

        commitAndSendBeforeNextRender("Dictated message")

        assertEquals(listOf("Dictated message"), sent)
        assertEquals("", state.valueState.value.text)
    }

    @Test
    fun clearingComposerBeforeSendDoesNotDispatchEmptyText() {
        val state = ComposerTextState(TextFieldValue("Old text"))
        val sent = render(state)

        commitAndSendBeforeNextRender("")

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun replacingComposerWithWhitespaceBeforeSendDoesNotDispatch() {
        val state = ComposerTextState(TextFieldValue("Old text"))
        val sent = render(state)

        commitAndSendBeforeNextRender(" \n ")

        assertEquals(emptyList<String>(), sent)
        assertEquals(" \n ", state.valueState.value.text)
    }

    private fun render(state: ComposerTextState): MutableList<String> {
        val sent = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { text, accepted ->
                            sent += text
                            accepted()
                        },
                        textState = state,
                        enterKeyBehavior = EnterKeyBehavior.SendMessage,
                    )
                }
            }
        }
        return sent
    }

    /** Invoke both real editor actions in one main-thread turn, without an intervening frame. */
    private fun commitAndSendBeforeNextRender(text: String) {
        val config = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config
        val commitText = checkNotNull(config[SemanticsActions.SetText].action)
        val send = checkNotNull(config[SemanticsActions.OnImeAction].action)
        composeRule.runOnIdle {
            assertTrue(commitText(AnnotatedString(text)))
            send()
        }
        composeRule.waitForIdle()
    }
}
