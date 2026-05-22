package dev.ipf.darkmatter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.ui.DarkMatterApp
import dev.ipf.darkmatter.ui.theme.DarkMatterTheme

class MainActivity : ComponentActivity() {
    private var inboundProfilePayload by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inboundProfilePayload = intent?.dataString
        enableEdgeToEdge()
        setContent {
            DarkMatterTheme {
                val appState = remember { DarkMatterAppState(applicationContext) }
                DarkMatterApp(
                    appState = appState,
                    inboundProfilePayload = inboundProfilePayload,
                    onProfilePayloadHandled = { handled ->
                        if (inboundProfilePayload == handled) inboundProfilePayload = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inboundProfilePayload = intent.dataString
    }
}
