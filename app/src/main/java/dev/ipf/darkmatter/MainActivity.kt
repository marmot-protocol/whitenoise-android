package dev.ipf.darkmatter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.notifications.InboundIntentRouting
import dev.ipf.darkmatter.notifications.NotificationNavigation
import dev.ipf.darkmatter.notifications.NotificationTarget
import dev.ipf.darkmatter.notifications.routeInboundIntent
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.ui.DarkMatterApp
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme

class MainActivity : ComponentActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private var inboundNotificationTarget by mutableStateOf<NotificationTarget?>(null)
    private lateinit var appState: DarkMatterAppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appState = (application as DarkMatterApplication).appState
        consumeIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state = remember { appState }
            val systemDarkTheme = isSystemInDarkTheme()
            DarkMatterTheme(darkTheme = state.themeMode.resolveDarkTheme(systemDarkTheme)) {
                DarkMatterApp(
                    appState = state,
                    inboundProfilePayload = inboundProfilePayload,
                    onProfilePayloadHandled = { handled ->
                        if (inboundProfilePayload == handled) inboundProfilePayload = null
                    },
                    inboundNotificationTarget = inboundNotificationTarget,
                    onNotificationTargetHandled = { handled ->
                        if (inboundNotificationTarget == handled) inboundNotificationTarget = null
                    },
                )
            }
        }
    }

    /**
     * Route an inbound intent: a notification tap (our [NotificationNavigation.ACTION_OPEN]
     * action) becomes a navigation target; a `darkmatter://` data URI becomes a
     * profile-link payload. A dataless, non-notification intent leaves any
     * already-queued target/link intact (see [routeInboundIntent]).
     */
    private fun consumeIntent(intent: Intent?) {
        val routing =
            routeInboundIntent(
                parsedTarget = NotificationNavigation.parse(intent),
                dataString = intent?.dataString,
                current = InboundIntentRouting(inboundNotificationTarget, inboundProfilePayload),
            )
        inboundNotificationTarget = routing.notificationTarget
        inboundProfilePayload = routing.profilePayload
    }

    override fun onStart() {
        super.onStart()
        if (::appState.isInitialized) appState.setAppInForeground(true)
    }

    override fun onStop() {
        if (::appState.isInitialized) appState.setAppInForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }
}
