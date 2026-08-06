package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.ttsRateLabel

/**
 * Read-aloud transport strip rendered beneath the conversation's top bar in
 * every top-bar state (default, selection, search): speech continues through
 * all of them, so the controls must too. Sentence and message navigation are
 * separate actions on their own row, so the layout stays usable at narrow
 * widths and large font scales without clipping. No scrub gesture, since the
 * framework offers no utterance-internal seek.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TtsTransportBar(
    appState: WhiteNoiseAppState,
    modifier: Modifier = Modifier,
) {
    val state by appState.ttsController.state.collectAsState()
    val current = state
    if (current is TtsState.Idle) return
    val rateOverride by appState.ttsRatePreferences.rateOverride.collectAsState()
    val historyEdge by appState.ttsHistorySession.edgeState.collectAsState()

    TtsTransportBarContent(
        state = current,
        rateLabel = ttsRateLabel(rateOverride ?: appState.ttsRatePreferences.resolvedRate()),
        onPause = { appState.ttsController.pause() },
        onResume = { appState.ttsController.resume() },
        // Navigation routes through the history session so an edge tap pages
        // the conversation instead of completing or clamping the queue.
        onPreviousSentence = { appState.ttsHistorySession.previousSentence() },
        onNextSentence = { appState.ttsHistorySession.nextSentence() },
        onPreviousMessage = { appState.ttsHistorySession.previousMessage() },
        onNextMessage = { appState.ttsHistorySession.nextMessage() },
        onCycleRate = { appState.setTtsRateOverride(nextTtsPresetRate(rateOverride)) },
        onStop = { appState.stopSpeaking() },
        modifier = modifier,
        historyEdge = historyEdge,
    )
}

@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun TtsTransportBarContent(
    state: TtsState,
    rateLabel: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPreviousMessage: () -> Unit,
    onNextMessage: () -> Unit,
    onCycleRate: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    historyEdge: TtsHistoryEdgeState? = null,
) {
    val isError = state is TtsState.Error
    val navigationEnabled = ttsNavigationEnabled(state, historyEdge)
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    val preview = if (isError) stringResource(R.string.tts_bar_error) else state.messagePreview
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!isError && ttsSentenceCount(state) > 0 && ttsMessageCount(state) > 0) {
                        Text(
                            text =
                                stringResource(
                                    R.string.tts_bar_progress,
                                    ttsSentenceIndex(state) + 1,
                                    ttsSentenceCount(state),
                                    ttsMessageIndex(state) + 1,
                                    ttsMessageCount(state),
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!isError && ttsMessageCount(state) > 0) {
                        // The progress text above already narrates the position.
                        LinearProgressIndicator(
                            progress = { (ttsMessageIndex(state) + 1).toFloat() / ttsMessageCount(state) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clearAndSetSemantics {},
                        )
                    }
                    HistoryEdgeStatus(historyEdge)
                }
                if (!isError) {
                    TextButton(onClick = onCycleRate) { Text(rateLabel) }
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tts_bar_stop))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onPreviousMessage, enabled = navigationEnabled) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.tts_bar_previous_message),
                    )
                }
                IconButton(onClick = onPreviousSentence, enabled = navigationEnabled) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateBefore,
                        contentDescription = stringResource(R.string.tts_bar_skip_previous),
                    )
                }
                when (state) {
                    is TtsState.Speaking ->
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.tts_bar_pause))
                        }
                    is TtsState.Paused ->
                        IconButton(onClick = onResume) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.tts_bar_play),
                            )
                        }
                    // Error clears the queue, so a disabled resume slot could
                    // never enable — render no control rather than lie to
                    // accessibility focus.
                    else -> Unit
                }
                IconButton(onClick = onNextSentence, enabled = navigationEnabled) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = stringResource(R.string.tts_bar_skip_next),
                    )
                }
                IconButton(onClick = onNextMessage, enabled = navigationEnabled) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.tts_bar_next_message),
                    )
                }
            }
        }
    }
}

internal fun ttsMessageIndex(state: TtsState): Int = state.messageIndex

internal fun ttsMessageCount(state: TtsState): Int = state.messageCount

internal fun ttsSentenceIndex(state: TtsState): Int = state.sentenceIndexWithinMessage

internal fun ttsSentenceCount(state: TtsState): Int = state.sentenceCountWithinMessage

internal fun ttsNavigationEnabled(state: TtsState): Boolean = state !is TtsState.Error && state !is TtsState.Idle

// A pending edge load owns the cursor: every navigation action disables so
// duplicate or conflicting requests can't queue up behind it.
internal fun ttsNavigationEnabled(
    state: TtsState,
    historyEdge: TtsHistoryEdgeState?,
): Boolean = ttsNavigationEnabled(state) && historyEdge !is TtsHistoryEdgeState.Loading

// Compact status line for a pending or failed history edge load, announced
// politely so TalkBack narrates the state change without stealing focus.
@Suppress("FunctionNaming")
@Composable
private fun HistoryEdgeStatus(historyEdge: TtsHistoryEdgeState?) {
    val (text, color) =
        when (historyEdge) {
            is TtsHistoryEdgeState.Loading ->
                stringResource(R.string.tts_bar_history_loading) to MaterialTheme.colorScheme.onSurfaceVariant

            is TtsHistoryEdgeState.Failed ->
                stringResource(R.string.tts_bar_history_error) to MaterialTheme.colorScheme.error

            null -> return
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .padding(top = 2.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
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
