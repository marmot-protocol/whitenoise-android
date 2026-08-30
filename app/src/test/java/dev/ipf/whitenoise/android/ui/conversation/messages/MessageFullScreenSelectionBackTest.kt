package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageFullScreenSelectionBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backClearsSelectionBeforeDismissingTheReader() {
        val body = "select this word before closing the reader"
        var selection: ReaderTextSelectionController? = null
        var dismissCount = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                val controller = rememberReaderTextSelectionController(body)
                selection = controller
                reader(
                    body = body,
                    selection = controller,
                    onDismiss = { dismissCount++ },
                )
            }
        }

        composeRule.onNodeWithText(body).performTouchInput { longClick() }
        composeRule.runOnIdle { assertTrue(requireNotNull(selection).active) }

        pressBack()
        composeRule.runOnIdle {
            assertFalse(requireNotNull(selection).active)
            assertEquals(0, dismissCount)
        }

        pressBack()
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun reopeningTheReaderStartsWithASeparateCleanSelectionSession() {
        val firstBody = "first reader selection"
        val body = mutableStateOf(firstBody)
        var selection: ReaderTextSelectionController? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                val controller = rememberReaderTextSelectionController(body.value)
                selection = controller
                reader(body = body.value, selection = controller)
            }
        }

        composeRule.onNodeWithText(firstBody).performTouchInput { longClick() }
        val first = composeRule.runOnIdle { requireNotNull(selection).also { assertTrue(it.active) } }

        composeRule.runOnIdle { body.value = "reopened reader is clean" }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val reopened = requireNotNull(selection)
            assertNotSame(first, reopened)
            assertFalse(reopened.active)
        }
    }

    @Test
    fun longPressStillSelectsAfterTheReaderHasScrolled() {
        val body = (1..80).joinToString("\n") { index -> "Reader line $index has selectable words" }
        var selection: ReaderTextSelectionController? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                val controller = rememberReaderTextSelectionController(body)
                selection = controller
                reader(body = body, selection = controller)
            }
        }

        val readerBody = composeRule.onNodeWithTag(MESSAGE_FULL_SCREEN_BODY_TAG)
        readerBody.performTouchInput { swipeUp() }
        readerBody.performTouchInput { swipeUp() }
        readerBody.performTouchInput { longClick() }

        composeRule.runOnIdle {
            val activeSelection = requireNotNull(selection)
            assertTrue(activeSelection.active)
            val selected = activeSelection.selectedText(body)
            assertTrue(selected.isNotBlank())
            assertTrue(body.contains(selected))
            assertFalse(selected == body)
        }
    }

    @androidx.compose.runtime.Composable
    private fun reader(
        body: String,
        selection: ReaderTextSelectionController,
        onDismiss: () -> Unit = {},
    ) {
        MessageFullScreenView(
            senderDisplayName = "Alice",
            senderSeed = "alice",
            senderAvatarUrl = null,
            body = body,
            bodyMarkdownDocument = null,
            mentionDisplayName = null,
            isGroupMember = null,
            onNostrProfileTap = null,
            onCopyMarkdownLink = {},
            timeText = "10:30",
            showStatus = false,
            status = MessageStatus.Received,
            canReply = true,
            canReact = true,
            canDelete = false,
            onReply = {},
            onReact = {},
            onCopy = {},
            onDelete = {},
            onDismiss = onDismiss,
            bottomBar = {},
            selectionController = selection,
        )
    }

    private fun pressBack() {
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
    }
}
