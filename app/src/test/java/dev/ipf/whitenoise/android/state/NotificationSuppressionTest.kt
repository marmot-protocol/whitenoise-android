package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lifecycle invariant behind issue #821: the active conversation may be
 * remembered across ordinary background/foreground, but task removal must clear
 * both halves so stale foreground-service process state cannot suppress a chat
 * after the UI is gone. Pure value-type tests, no Android context required.
 */
class NotificationSuppressionTest {
    @Test
    fun foregroundWithOpenChatSuppressesThatChat() {
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "group-a", accountRef = "account-a")

        assertTrue(state.inForeground)
        assertEquals("group-a", state.activeConversationGroupIdHex)
        assertEquals("account-a", state.activeConversationAccountRef)
    }

    @Test
    fun backgroundingKeepsTheOpenConversationButDisablesForegroundSuppression() {
        // Open chat A in the foreground, then background (press home / overview).
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "group-a", accountRef = "account-a")
                .onBackground()

        // The foreground half gates suppression while backgrounded, but the chat
        // identity stays in memory so returning to the same mounted Activity can
        // resume suppression without Compose re-running an unchanged effect.
        assertFalse(state.inForeground)
        assertEquals("group-a", state.activeConversationGroupIdHex)
        assertEquals("account-a", state.activeConversationAccountRef)
    }

    @Test
    fun taskRemovedClearsEverythingEvenIfStopNeverRan() {
        // Swipe-away from recents while a foreground service keeps the process
        // alive: onStop may not have fired, so the active conversation could
        // still be set when onTaskRemoved arrives.
        val state =
            NotificationSuppression(
                inForeground = true,
                activeConversationGroupIdHex = "group-a",
                activeConversationAccountRef = "account-a",
            ).onTaskRemoved()

        assertFalse(state.inForeground)
        assertNull(state.activeConversationGroupIdHex)
        assertNull(state.activeConversationAccountRef)
    }

    @Test
    fun closingTheConversationKeepsForegroundButClearsTheChat() {
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "group-a", accountRef = "account-a")
                .onActiveConversation(groupIdHex = null, accountRef = null)

        // Returning to the chat list (still foreground) clears the active chat
        // but stays foreground, matching the contrast case in the issue.
        assertTrue(state.inForeground)
        assertNull(state.activeConversationGroupIdHex)
        assertNull(state.activeConversationAccountRef)
    }

    @Test
    fun returningToForegroundResumesTheStillOpenChat() {
        // After background → foreground, the still-mounted chat becomes visible
        // again. Preserve its identity across the stop/start pair so foreground
        // suppression resumes even if Compose does not re-run the unchanged
        // DisposableEffect.
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "group-a", accountRef = "account-a")
                .onBackground()
                .onForeground()

        assertTrue(state.inForeground)
        assertEquals("group-a", state.activeConversationGroupIdHex)
        assertEquals("account-a", state.activeConversationAccountRef)
    }
}
