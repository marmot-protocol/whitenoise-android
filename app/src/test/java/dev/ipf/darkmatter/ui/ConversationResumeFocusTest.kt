package dev.ipf.darkmatter.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation resume-focus decision (issue #589, Case B).
 *
 * After switching to another app and returning to a chat, the soft keyboard
 * must match the state it had on leave: re-raise it only if the composer held
 * focus when we were paused, and otherwise stay closed instead of popping open
 * unrequested. The Compose lifecycle observer in `ConversationScreen` delegates
 * that decision to [shouldRestoreComposerFocusOnResume] so it can be pinned
 * here with plain JUnit, matching the repo's pure-predicate testing convention.
 *
 * `true` → restore focus / re-raise keyboard; `false` → clear focus / keep the
 * keyboard hidden.
 */
class ConversationResumeFocusTest {
    @Test
    fun focusedOnPauseRestoresFocus() {
        // Case A keyboard state: it was open (composer focused) when leaving, so
        // resume must bring it back exactly as it was.
        assertTrue(
            shouldRestoreComposerFocusOnResume(
                wasComposerFocusedOnPause = true,
            ),
        )
    }

    @Test
    fun notFocusedOnPauseDoesNotRestoreFocus() {
        // Case B: the keyboard was closed on leave, so resume must NOT request
        // focus — the observer instead clears focus and hides the keyboard.
        assertFalse(
            shouldRestoreComposerFocusOnResume(
                wasComposerFocusedOnPause = false,
            ),
        )
    }

    @Test
    fun activeEditSessionRestoresEvenIfFocusFlagLagged() {
        // An in-progress edit/reply deliberately owns the keyboard; treat it as
        // focus-owning so returning to it never drops into a closed-keyboard
        // state even if the raw focus edge briefly lagged on pause.
        assertTrue(
            shouldRestoreComposerFocusOnResume(
                wasComposerFocusedOnPause = false,
                hasActiveEditOrReplySession = true,
            ),
        )
    }

    @Test
    fun noFocusAndNoActiveSessionDoesNotRestore() {
        assertFalse(
            shouldRestoreComposerFocusOnResume(
                wasComposerFocusedOnPause = false,
                hasActiveEditOrReplySession = false,
            ),
        )
    }

    @Test
    fun clearsFocusWhenNotRestoringAndSearchClosed() {
        // Case B (keyboard closed on leave, search not involved): the resume
        // observer must actively clear focus + hide the keyboard so the system
        // does not pop it open unrequested.
        assertTrue(
            shouldClearFocusOnResume(
                restoringComposerFocus = false,
                searchOpen = false,
            ),
        )
    }

    @Test
    fun doesNotClearFocusWhileSearchOpen() {
        // #589 regression / #292 guard: in-chat search legitimately owns focus
        // and the keyboard while open. The screen-wide clearFocus(force = true)
        // must NOT fire on resume in that state, or the search field loses focus
        // and its keyboard hides with nothing to restore it.
        assertFalse(
            shouldClearFocusOnResume(
                restoringComposerFocus = false,
                searchOpen = true,
            ),
        )
    }

    @Test
    fun doesNotClearFocusWhenRestoringComposerFocus() {
        // When we are restoring composer focus, the destructive clear branch must
        // not also fire — the two outcomes are mutually exclusive.
        assertFalse(
            shouldClearFocusOnResume(
                restoringComposerFocus = true,
                searchOpen = false,
            ),
        )
    }

    @Test
    fun doesNotClearFocusWhenRestoringAndSearchOpen() {
        assertFalse(
            shouldClearFocusOnResume(
                restoringComposerFocus = true,
                searchOpen = true,
            ),
        )
    }
}
