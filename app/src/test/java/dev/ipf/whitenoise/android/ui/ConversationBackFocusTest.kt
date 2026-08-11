package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.ComposerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.ConversationBackAction
import dev.ipf.whitenoise.android.ui.conversation.awaitStableImeInset
import dev.ipf.whitenoise.android.ui.conversation.composerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.conversationBackAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationBackFocusTest {
    @Test
    fun preImeBackDownDismissesTheFocusedComposer() {
        assertEquals(
            ComposerPreImeBackAction.DISMISS,
            composerPreImeBackAction(
                enabled = true,
                isBackKey = true,
                isKeyDown = true,
            ),
        )
    }

    @Test
    fun preImeBackUpIsConsumedWithoutDismissingTwice() {
        assertEquals(
            ComposerPreImeBackAction.CONSUME,
            composerPreImeBackAction(
                enabled = true,
                isBackKey = true,
                isKeyDown = false,
            ),
        )
    }

    @Test
    fun nonBackKeysContinueToTheIme() {
        assertEquals(
            ComposerPreImeBackAction.IGNORE,
            composerPreImeBackAction(
                enabled = true,
                isBackKey = false,
                isKeyDown = true,
            ),
        )
    }

    @Test
    fun customInputPaneKeepsItsExistingBackHandler() {
        assertEquals(
            ComposerPreImeBackAction.IGNORE,
            composerPreImeBackAction(
                enabled = false,
                isBackKey = true,
                isKeyDown = true,
            ),
        )
    }

    @Test
    fun conversationBackActionsRespectPriority() {
        val cases =
            listOf(
                BackCase(
                    textSelectionActive = true,
                    messageSelectionActive = true,
                    searchOpen = true,
                    composerFocused = true,
                    imeIsOpen = true,
                    expected = ConversationBackAction.CLEAR_TEXT_SELECTION,
                ),
                BackCase(
                    messageSelectionActive = true,
                    searchOpen = true,
                    composerFocused = true,
                    imeIsOpen = true,
                    expected = ConversationBackAction.CLEAR_MESSAGE_SELECTION,
                ),
                BackCase(
                    searchOpen = true,
                    composerFocused = true,
                    imeIsOpen = true,
                    expected = ConversationBackAction.CLOSE_SEARCH,
                ),
                BackCase(
                    composerFocused = true,
                    imeIsOpen = false,
                    expected = ConversationBackAction.DISMISS_COMPOSER,
                ),
                BackCase(
                    composerFocused = false,
                    imeIsOpen = true,
                    expected = ConversationBackAction.DISMISS_COMPOSER,
                ),
                BackCase(
                    composerFocused = false,
                    imeIsOpen = true,
                    composerDismissInProgress = true,
                    expected = ConversationBackAction.NAVIGATE_UP,
                ),
                BackCase(expected = ConversationBackAction.NAVIGATE_UP),
            )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                conversationBackAction(
                    textSelectionActive = case.textSelectionActive,
                    messageSelectionActive = case.messageSelectionActive,
                    searchOpen = case.searchOpen,
                    composerFocused = case.composerFocused,
                    imeIsOpen = case.imeIsOpen,
                    composerDismissInProgress = case.composerDismissInProgress,
                ),
            )
        }
    }

    @Test
    fun imeSettleWaitsWithoutWritingAndStopsAfterStableGeometry() =
        kotlinx.coroutines.test.runTest {
            val insets = ArrayDeque(listOf(100, 180, 240, 240, 240))
            var current = 40
            var frames = 0

            val settled =
                awaitStableImeInset(
                    maxFrames = 24,
                    readInset = { current },
                    awaitFrame = {
                        frames++
                        current = insets.removeFirst()
                    },
                )

            assertEquals(true, settled)
            assertEquals(5, frames)
        }
}

private data class BackCase(
    val textSelectionActive: Boolean = false,
    val messageSelectionActive: Boolean = false,
    val searchOpen: Boolean = false,
    val composerFocused: Boolean = false,
    val imeIsOpen: Boolean = false,
    val composerDismissInProgress: Boolean = false,
    val expected: ConversationBackAction,
)
