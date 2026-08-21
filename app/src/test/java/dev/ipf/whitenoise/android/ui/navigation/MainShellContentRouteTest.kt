package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainShellContentRouteTest {
    @Test
    fun notificationRoutingTakesPrecedenceOverOpenConversation() {
        assertEquals(
            MainShellContentRoute.NotificationLoading,
            resolveMainShellContentRoute(
                conversationOpen = true,
                routingNotification = true,
                routingTtsReturn = true,
            ),
        )
    }

    @Test
    fun ttsReturnTransitionHidesAnOpenConversationAndMainContent() {
        assertEquals(
            MainShellContentRoute.TtsReturnTransition,
            resolveMainShellContentRoute(
                conversationOpen = true,
                routingNotification = false,
                routingTtsReturn = true,
            ),
        )
        assertEquals(
            MainShellContentRoute.TtsReturnTransition,
            resolveMainShellContentRoute(
                conversationOpen = false,
                routingNotification = false,
                routingTtsReturn = true,
            ),
        )
    }

    @Test
    fun openConversationShownWhenNotRoutingNotification() {
        assertEquals(
            MainShellContentRoute.Conversation,
            resolveMainShellContentRoute(
                conversationOpen = true,
                routingNotification = false,
                routingTtsReturn = false,
            ),
        )
    }

    @Test
    fun mainShellWhenNoConversationAndNotRouting() {
        assertEquals(
            MainShellContentRoute.Main,
            resolveMainShellContentRoute(
                conversationOpen = false,
                routingNotification = false,
                routingTtsReturn = false,
            ),
        )
    }

    @Test
    fun tappedConversationWaitsForItsAuthoritativeLocalPage() {
        assertFalse(
            preparedConversationCanOpen(
                hasPublishedAuthoritativeTimeline = false,
                hasPreparedInitialPresentation = false,
                hasLoadError = false,
                terminalConversationUnavailable = false,
            ),
        )
        assertFalse(
            preparedConversationCanOpen(
                hasPublishedAuthoritativeTimeline = true,
                hasPreparedInitialPresentation = false,
                hasLoadError = false,
                terminalConversationUnavailable = false,
            ),
        )
        assertTrue(
            preparedConversationCanOpen(
                hasPublishedAuthoritativeTimeline = true,
                hasPreparedInitialPresentation = true,
                hasLoadError = false,
                terminalConversationUnavailable = false,
            ),
        )
    }

    @Test
    fun preparedConversationStillOpensToItsDistinctErrorSurface() {
        assertTrue(
            preparedConversationCanOpen(
                hasPublishedAuthoritativeTimeline = false,
                hasPreparedInitialPresentation = false,
                hasLoadError = true,
                terminalConversationUnavailable = false,
            ),
        )
    }

    @Test
    fun terminallyUnavailableConversationStillResolvesThePendingTap() {
        assertTrue(
            preparedConversationCanOpen(
                hasPublishedAuthoritativeTimeline = false,
                hasPreparedInitialPresentation = false,
                hasLoadError = false,
                terminalConversationUnavailable = true,
            ),
        )
    }

    @Test
    fun conversationSlideMirrorsInRtl() {
        assertEquals(1, conversationRouteForwardDirection(LayoutDirection.Ltr))
        assertEquals(-1, conversationRouteForwardDirection(LayoutDirection.Rtl))
    }

    @Test
    fun pendingConversationOpenBelongsOnlyToItsOriginatingAccount() {
        assertTrue(pendingConversationOpenBelongsToAccount("personal", "personal"))
        assertFalse(pendingConversationOpenBelongsToAccount("personal", "work"))
        assertFalse(pendingConversationOpenBelongsToAccount("personal", null))
        assertFalse(pendingConversationOpenBelongsToAccount(null, "personal"))
        assertFalse(pendingConversationOpenBelongsToAccount(null, null))
    }

    @Test
    fun mainShellConversationContentRequiresStableAccountOwnership() {
        assertTrue(mainShellAccountContentOwned("personal", "personal"))
        assertFalse(mainShellAccountContentOwned("personal", "work"))
        assertFalse(mainShellAccountContentOwned("personal", null))
    }

    @Test
    fun retainedExitKeepsItsPinnedAccountAfterSelectedContextClears() {
        assertEquals(
            "target",
            conversationControllerAccountRef(
                selectedPinnedAccountRef = null,
                pendingAccountRef = null,
                exitingAccountRef = "target",
                activeAccountRef = "source",
            ),
        )
        assertTrue(
            retainedConversationContentBelongsToRoute(
                contentAccountRef = "target",
                activeAccountRef = "source",
                pinnedAccountRef = "target",
                notificationRouteTraceRequestId = 42L,
                notificationEarlyOpenRequestId = 42L,
            ),
        )
    }

    @Test
    fun retainedExitRejectsContentFromAnUnrelatedAccount() {
        assertFalse(
            retainedConversationContentBelongsToRoute(
                contentAccountRef = "personal",
                activeAccountRef = "work",
                pinnedAccountRef = null,
                notificationRouteTraceRequestId = null,
                notificationEarlyOpenRequestId = 0L,
            ),
        )
    }

    @Test
    fun conversationRouteHasNoAccountWhenEveryOwnershipSourceIsAbsent() {
        assertNull(
            conversationControllerAccountRef(
                selectedPinnedAccountRef = null,
                pendingAccountRef = null,
                exitingAccountRef = null,
                activeAccountRef = null,
            ),
        )
    }

    @Test
    fun selectedNotificationConversationKeepsItsPinnedAccountUntilTheSwitchLands() {
        assertEquals(
            "account-b",
            selectedConversationAccountRef(
                pinnedAccountRef = "account-b",
                activeAccountRef = "account-a",
            ),
        )
    }

    @Test
    fun ordinarySelectedConversationUsesTheActiveAccount() {
        assertEquals(
            "account-a",
            selectedConversationAccountRef(
                pinnedAccountRef = null,
                activeAccountRef = "account-a",
            ),
        )
    }
}
