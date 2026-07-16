package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationOpenDismissalTargetTest {
    @Test
    fun returnsTargetWhenOpeningNonblankGroupUnderNonblankAccount() {
        assertEquals(
            ConversationNotificationTarget("acct-a", "group-1"),
            conversationOpenDismissalTarget("acct-a", "group-1"),
        )
    }

    @Test
    fun noTargetWhenGroupIsNullBlankOrAccountMissing() {
        assertNull(conversationOpenDismissalTarget("acct-a", null))
        assertNull(conversationOpenDismissalTarget("acct-a", "   "))
        assertNull(conversationOpenDismissalTarget(null, "group-1"))
        assertNull(conversationOpenDismissalTarget("   ", "group-1"))
    }

    @Test
    fun openingConversationDismissesTheComputedTarget() {
        val dismissed = mutableListOf<ConversationNotificationTarget>()

        runBlocking {
            dismissConversationNotificationsOnOpen("acct-a", "group-1") { accountRef, groupIdHex ->
                dismissed += ConversationNotificationTarget(accountRef, groupIdHex)
            }
        }

        assertEquals(listOf(ConversationNotificationTarget("acct-a", "group-1")), dismissed)
    }

    @Test
    fun openingConversationWithNoTargetDoesNotDismissAnything() {
        val dismissed = mutableListOf<ConversationNotificationTarget>()

        runBlocking {
            dismissConversationNotificationsOnOpen("acct-a", "   ") { accountRef, groupIdHex ->
                dismissed += ConversationNotificationTarget(accountRef, groupIdHex)
            }
        }

        assertEquals(emptyList<ConversationNotificationTarget>(), dismissed)
    }

    @Test
    fun foregroundResumeTargetsTheStillOpenConversation() {
        val resumed =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "group-1", accountRef = "acct-a")
                .onBackground()
                .onForeground()

        assertEquals(
            ConversationNotificationTarget("acct-a", "group-1"),
            visibleConversationDismissalTarget(
                appInForeground = resumed.inForeground,
                appLockScreenVisible = false,
                activeAccountRef = resumed.activeConversationAccountRef,
                groupIdHex = resumed.activeConversationGroupIdHex,
            ),
        )
    }

    @Test
    fun lockedForegroundDoesNotTargetConversationUntilUnlock() {
        assertNull(
            visibleConversationDismissalTarget(
                appInForeground = true,
                appLockScreenVisible = true,
                activeAccountRef = "acct-a",
                groupIdHex = "group-1",
            ),
        )
        assertEquals(
            ConversationNotificationTarget("acct-a", "group-1"),
            visibleConversationDismissalTarget(
                appInForeground = true,
                appLockScreenVisible = false,
                activeAccountRef = "acct-a",
                groupIdHex = "group-1",
            ),
        )
    }
}
