package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.ComposerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.ConversationBackAction
import dev.ipf.whitenoise.android.ui.conversation.awaitImeInsetAtTarget
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
    fun imeSettleWaitsForTheAnimationTargetThenStops() =
        kotlinx.coroutines.test.runTest {
            val insets = ArrayDeque(listOf(100, 180, 240, 240, 240))
            var current = 40
            var frames = 0

            awaitImeInsetAtTarget(
                readInset = { current },
                readTargetInset = { 240 },
                awaitFrame = {
                    frames++
                    current = insets.removeFirst()
                },
            )

            assertEquals(4, frames)
        }

    /**
     * A gesture-driven drag whose finger pauses produces frame-stable insets
     * below the animation target. The frames-stable idiom this helper replaced
     * read that pause as "settled" and authorized a scroll write mid-gesture —
     * the swipe-up keyboard jitter. Equality with the target must be the only
     * exit: the wait outlives any pause and ends when the release animation
     * converges the inset onto the target.
     */
    @Test
    fun imeSettleDoesNotTreatAPausedDragAsSettled() =
        kotlinx.coroutines.test.runTest {
            // Finger drags to 500 and pauses for many frames (stable but below
            // the 800 target), then releases: system animates to the target.
            val insets =
                ArrayDeque(
                    listOf(120, 300, 500, 500, 500, 500, 500, 500, 500, 500, 640, 760, 800, 800),
                )
            var current = 0
            var frames = 0

            awaitImeInsetAtTarget(
                readInset = { current },
                readTargetInset = { 800 },
                awaitFrame = {
                    frames++
                    current = insets.removeFirst()
                },
            )

            assertEquals(14, frames)
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
