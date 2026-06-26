package dev.ipf.whitenoise.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

class MainActivity : ComponentActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private var inboundNotificationTarget by mutableStateOf<NotificationTarget?>(null)
    private lateinit var appState: WhiteNoiseAppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appState = (application as WhiteNoiseApplication).appState
        consumeIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state = remember { appState }
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = state.themeMode.resolveDarkTheme(systemDarkTheme)
            // The in-app theme can override the system theme (e.g. AMOLED while
            // the system is light), so the status- and navigation-bar icons must
            // follow the resolved app theme. Left on the edge-to-edge default,
            // dark icons land on a black background and disappear.
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            WhiteNoiseTheme(
                darkTheme = darkTheme,
                amoled = state.themeMode.isAmoled,
            ) {
                WhiteNoiseApp(
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
     * action) becomes a navigation target; a White Noise data URI becomes a
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
