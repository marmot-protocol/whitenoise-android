package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerDraftRestoreFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun restoredDraftFocusRunsAgainWhenConversationChanges() {
        var draftKey by mutableStateOf("first")
        lateinit var focusManager: FocusManager
        var focusGainCount = 0

        composeRule.setContent {
            focusManager = LocalFocusManager.current
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue("draft $draftKey"),
                        draftKey = draftKey,
                        autoFocusOnDraftRestore = true,
                        onComposerFocusChanged = { focused ->
                            if (focused) focusGainCount += 1
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, focusGainCount)
            focusManager.clearFocus(force = true)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { draftKey = "second" }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(2, focusGainCount) }
    }
}
