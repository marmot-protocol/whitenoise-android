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
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.ui.DarkMatterApp
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme

class MainActivity : ComponentActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)
    private lateinit var appState: DarkMatterAppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appState = (application as DarkMatterApplication).appState
        inboundProfilePayload = intent?.dataString
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
                )
            }
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
        inboundProfilePayload = intent.dataString
    }
}
