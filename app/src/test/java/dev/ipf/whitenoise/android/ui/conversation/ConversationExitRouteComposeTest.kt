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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationExitRouteComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retainedRouteWaitsForImeAndCanExitAgainAfterRapidReentry() {
        var conversationOpen by mutableStateOf(true)
        var imeIsOpen by mutableStateOf(true)
        var draft by mutableStateOf(TextFieldValue(""))
        val routeCount = AtomicInteger()

        setConversationRouteContent(
            conversationOpen = { conversationOpen },
            imeIsOpen = { imeIsOpen },
            draft = { draft },
            onDraftChange = { draft = it },
            onOpenConversation = { conversationOpen = true },
            onRouteToChatList = {
                routeCount.incrementAndGet()
                conversationOpen = false
            },
        )

        composeRule.onNode(hasSetTextAction()).performClick().performTextInput(DRAFT)
        val editor = composeRule.onNodeWithText(DRAFT)
        editor.assertIsFocused()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithContentDescription(BACK).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        editor.assertIsFocused()
        composeRule.runOnIdle { assertEquals(0, routeCount.get()) }

        composeRule.runOnIdle { imeIsOpen = false }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 5_000) { routeCount.get() == 1 }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(CHATS).assertExists()
        editor.assertExists().assertIsNotFocused()
        composeRule.runOnIdle {
            assertEquals(1, routeCount.get())
            assertEquals(DRAFT, draft.text)
        }

        // Re-enter before the one-second outgoing transition can dispose the
        // remembered conversation slot, then prove that it owns a fresh exit.
        composeRule.onNodeWithText(CHATS).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { imeIsOpen = true }
        composeRule.onNodeWithText(DRAFT).performClick()
        composeRule.onNodeWithText(DRAFT).assertIsFocused()

        composeRule.onNodeWithContentDescription(BACK).performClick()
        composeRule.runOnIdle { imeIsOpen = false }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 5_000) { routeCount.get() == 2 }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(CHATS).assertExists()
        composeRule.runOnIdle {
            assertEquals(2, routeCount.get())
            assertEquals(DRAFT, draft.text)
        }
    }

    private fun setConversationRouteContent(
        conversationOpen: () -> Boolean,
        imeIsOpen: () -> Boolean,
        draft: () -> TextFieldValue,
        onDraftChange: (TextFieldValue) -> Unit,
        onOpenConversation: () -> Unit,
        onRouteToChatList: () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    AnimatedContent(
                        targetState = conversationOpen(),
                        transitionSpec = { fadeIn(tween(1_000)) togetherWith fadeOut(tween(1_000)) },
                        label = "conversation-route",
                    ) { open ->
                        if (open) {
                            val exit =
                                rememberConversationExitHandler(
                                    identity = CHAT_ID,
                                    imeIsOpen = imeIsOpen(),
                                    routeToChatList = onRouteToChatList,
                                )
                            Box {
                                ComposerBar(
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    onPickFromGallery = {},
                                    onPickDocument = {},
                                    initialDraft = draft(),
                                    onDraftChange = onDraftChange,
                                )
                                IconButton(onClick = exit) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = BACK)
                                }
                            }
                        } else {
                            TextButton(onClick = onOpenConversation) {
                                Text(CHATS)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val CHAT_ID = "chat-1"
        const val DRAFT = "Unsent retained draft"
        const val BACK = "Back to chats"
        const val CHATS = "Chat list"
    }
}
