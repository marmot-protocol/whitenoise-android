package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.ttsRateLabel

/**
 * Read-aloud transport strip rendered beneath the conversation's top bar in
 * every top-bar state (default, selection, search): speech continues through
 * all of them, so the controls must too. Sentence-granular progress only —
 * no scrub gesture, since the framework offers no utterance-internal seek.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun TtsTransportBar(
    appState: WhiteNoiseAppState,
    modifier: Modifier = Modifier,
) {
    val state by appState.ttsController.state.collectAsState()
    val current = state
    if (current is TtsState.Idle) return
    val rateOverride by appState.ttsRatePreferences.rateOverride.collectAsState()

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            when (current) {
                is TtsState.Speaking ->
                    IconButton(onClick = { appState.ttsController.pause() }) {
                        Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.tts_bar_pause))
                    }
                is TtsState.Paused ->
                    IconButton(onClick = { appState.ttsController.resume() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.tts_bar_play))
                    }
                is TtsState.Error, is TtsState.Idle -> Unit
            }
            IconButton(onClick = { appState.ttsController.skipPrevious() }, enabled = current !is TtsState.Error) {
                Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.tts_bar_skip_previous))
            }
            IconButton(onClick = { appState.ttsController.skipNext() }, enabled = current !is TtsState.Error) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.tts_bar_skip_next))
            }
            Column(modifier = Modifier.weight(1f)) {
                val preview =
                    if (current is TtsState.Error) {
                        stringResource(R.string.tts_bar_error)
                    } else {
                        appState.ttsNowPlayingPreview.orEmpty()
                    }
                if (preview.isNotBlank()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (current is TtsState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (current !is TtsState.Error) {
                    val chunkCount = ttsChunkCount(current)
                    val chunkIndex = ttsChunkIndex(current)
                    if (chunkCount > 0) {
                        LinearProgressIndicator(
                            progress = { chunkIndex.toFloat() / chunkCount },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
            if (current !is TtsState.Error) {
                TextButton(onClick = { appState.setTtsRateOverride(nextTtsPresetRate(rateOverride)) }) {
                    Text(ttsRateLabel(rateOverride ?: appState.ttsRatePreferences.resolvedRate()))
                }
            }
            IconButton(onClick = { appState.stopSpeaking() }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tts_bar_stop))
            }
        }
    }
}

internal fun ttsChunkIndex(state: TtsState): Int =
    when (state) {
        is TtsState.Speaking -> state.chunkIndex
        is TtsState.Paused -> state.chunkIndex
        is TtsState.Error -> state.chunkIndex
        is TtsState.Idle -> state.chunkIndex
    }

internal fun ttsChunkCount(state: TtsState): Int =
    when (state) {
        is TtsState.Speaking -> state.chunkCount
        is TtsState.Paused -> state.chunkCount
        is TtsState.Error -> state.chunkCount
        is TtsState.Idle -> state.chunkCount
    }

/** Cycles the preset list; from the System default it starts at 1×. */
internal fun nextTtsPresetRate(currentOverride: Float?): Float {
    val presets = TtsRatePreferences.PRESET_RATES
    val currentIndex = presets.indexOfFirst { it == currentOverride }
    return if (currentIndex < 0) {
        TtsRatePreferences.DEFAULT_RATE
    } else {
        presets[(currentIndex + 1) % presets.size]
    }
}
