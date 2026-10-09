package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val WAVE_DISMISSAL_READ_TIMEOUT_MS = 5_000L

/** Only an Android action dismissal; membership and send acceptance remain native-owned. */
internal class WaveHiDismissal(
    val dismissed: Boolean,
    val persistDismissal: () -> Unit,
)

internal suspend fun loadWaveHiDismissal(
    context: Context,
    key: String,
): WaveHiDismissal =
    withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("whitenoise.wave_dismissals", Context.MODE_PRIVATE)
        WaveHiDismissal(preferences.getBoolean(key, false)) {
            preferences.edit().putBoolean(key, true).apply()
        }
    }

internal class WaveHiPreparation {
    var dismissal by mutableStateOf<WaveHiDismissal?>(null)
    var failed by mutableStateOf(false)
    var retry by mutableStateOf(0)
    var accepted by mutableStateOf(false)
    var sending by mutableStateOf(false)
}

/** A new account generation/event gets a new state; cancelled reads cannot publish into its replacement. */
@Composable
internal fun rememberWaveHiPreparation(
    key: String,
    load: suspend (String) -> WaveHiDismissal,
): WaveHiPreparation {
    val state = remember(key) { WaveHiPreparation() }
    LaunchedEffect(key, state.retry) {
        state.failed = false
        val result =
            runCatchingCancellable {
                withTimeoutOrNull(WAVE_DISMISSAL_READ_TIMEOUT_MS) { load(key) }
            }
        state.dismissal = result.getOrNull()
        state.failed = state.dismissal == null
    }
    return state
}
