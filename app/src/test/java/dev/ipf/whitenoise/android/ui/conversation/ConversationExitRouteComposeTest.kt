package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationExitRouteComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retainedOutgoingComposerReleasesFocusAndKeepsDraftAcrossChatListRoute() {
        var conversationOpen by mutableStateOf(true)
        var draft by mutableStateOf(TextFieldValue(""))

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    AnimatedContent(
                        targetState = conversationOpen,
                        transitionSpec = { fadeIn(tween(1_000)) togetherWith fadeOut(tween(1_000)) },
                        label = "conversation-route",
                    ) { open ->
                        if (open) {
                            val exit = rememberConversationExitCoordinator { conversationOpen = false }
                            Box {
                                ComposerBar(
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    onPickFromGallery = {},
                                    onPickDocument = {},
                                    initialDraft = draft,
                                    onDraftChange = { draft = it },
                                )
                                IconButton(onClick = exit::exit) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = BACK)
                                }
                            }
                        } else {
                            TextButton(onClick = { conversationOpen = true }) {
                                Text(CHATS)
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNode(hasSetTextAction()).performClick().performTextInput(DRAFT)
        val editor = composeRule.onNodeWithText(DRAFT)
        editor.assertIsFocused()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithContentDescription(BACK).performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(CHATS).assertExists()
        editor.assertExists().assertIsNotFocused()
        composeRule.runOnIdle { assertEquals(DRAFT, draft.text) }

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText(CHATS).performClick()
        composeRule.onNodeWithText(DRAFT).assertExists()
    }

    private companion object {
        const val DRAFT = "Unsent retained draft"
        const val BACK = "Back to chats"
        const val CHATS = "Chat list"
    }
}
