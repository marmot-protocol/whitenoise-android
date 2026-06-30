package dev.ipf.whitenoise.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
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
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

class MainActivity : ComponentActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private var inboundNotificationTarget by mutableStateOf<NotificationTarget?>(null)
    private lateinit var appState: WhiteNoiseAppState

    override fun onCreate(savedInstanceState: Bundle?) {
        val initialSystemDarkTheme = resources.configuration.isNightModeActive
        // Apply the pre-Compose theme here, not in attachBaseContext: the window
        // doesn't exist that early, so Activity.setTheme() NPEs on getWindow().
        // onCreate (before super) still runs before the first frame.
        setTheme(preComposeThemeFor(readPersistedThemeMode(), initialSystemDarkTheme))
        super.onCreate(savedInstanceState)
        appState = (application as WhiteNoiseApplication).appState
        consumeIntent(intent)
        enableEdgeToEdge()
        applyPreComposeWindowBackground(appState.themeMode, initialSystemDarkTheme)
        setContent {
            val state = remember { appState }
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = state.themeMode.resolveDarkTheme(systemDarkTheme)
            // The in-app theme can override the system theme (e.g. AMOLED while
            // the system is light), so the pre-Compose fallback and system-bar
            // icons must follow the resolved app theme. Left on the edge-to-edge
            // default, dark icons land on a black background and disappear.
            DisposableEffect(state.themeMode, systemDarkTheme) {
                applyPreComposeWindowBackground(state.themeMode, systemDarkTheme)
                onDispose { }
            }
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
        val parsedTarget = NotificationNavigation.parse(intent)
        val routing =
            routeInboundIntent(
                parsedTarget = parsedTarget,
                dataString = intent?.dataString,
                current = InboundIntentRouting(inboundNotificationTarget, inboundProfilePayload),
            )
        inboundNotificationTarget = routing.notificationTarget
        inboundProfilePayload = routing.profilePayload
        if (parsedTarget != null) {
            // Notification taps are one-shot navigation requests. Replace the
            // stored intent after parsing so activity recreation cannot replay
            // the same target after the UI has already consumed it.
            setIntent(Intent(this, MainActivity::class.java))
        }
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

    private fun readPersistedThemeMode(): AppThemeMode =
        AppThemeMode.fromPreference(
            getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(THEME_MODE_KEY, null),
        )

    private fun applyPreComposeWindowBackground(
        themeMode: AppThemeMode,
        systemDarkTheme: Boolean,
    ) {
        window.setBackgroundDrawable(ColorDrawable(preComposeWindowBackgroundFor(themeMode, systemDarkTheme)))
    }
}

internal fun preComposeThemeFor(
    themeMode: AppThemeMode,
    systemDarkTheme: Boolean,
): Int =
    when (themeMode) {
        AppThemeMode.System -> if (systemDarkTheme) R.style.Theme_WhiteNoise_Dark else R.style.Theme_WhiteNoise_Light
        AppThemeMode.Light -> R.style.Theme_WhiteNoise_Light
        AppThemeMode.Dark -> R.style.Theme_WhiteNoise_Dark
        AppThemeMode.Amoled -> R.style.Theme_WhiteNoise_Amoled
    }

internal fun preComposeWindowBackgroundFor(
    themeMode: AppThemeMode,
    systemDarkTheme: Boolean,
): Int =
    when (themeMode) {
        AppThemeMode.System -> if (systemDarkTheme) PRE_COMPOSE_BACKGROUND_DARK else PRE_COMPOSE_BACKGROUND_LIGHT
        AppThemeMode.Light -> PRE_COMPOSE_BACKGROUND_LIGHT
        AppThemeMode.Dark -> PRE_COMPOSE_BACKGROUND_DARK
        AppThemeMode.Amoled -> Color.BLACK
    }

private val Configuration.isNightModeActive: Boolean
    get() = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private const val APP_PREFERENCES_NAME = "whitenoise"
private const val THEME_MODE_KEY = "theme_mode"
private const val PRE_COMPOSE_BACKGROUND_LIGHT = 0xFFECEEEE.toInt()
private const val PRE_COMPOSE_BACKGROUND_DARK = 0xFF0F1112.toInt()
