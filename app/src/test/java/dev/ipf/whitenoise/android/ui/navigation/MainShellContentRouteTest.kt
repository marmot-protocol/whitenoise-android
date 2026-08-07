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
            ),
        )
    }
}
