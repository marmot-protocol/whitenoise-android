package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
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
}
