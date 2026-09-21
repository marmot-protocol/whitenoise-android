package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal const val TTS_TRANSPORT_BODY_TAG = "tts-transport-body"

/**
 * Read-aloud transport strip rendered beneath the conversation's top bar in
 * every top-bar state (default, selection, search): speech continues through
 * all of them, so the controls must too. Sentence and message navigation are
 * separate compact actions above message progress. The controls scroll
 * horizontally at narrow widths or large font scales without making the
 * transport taller. No scrub gesture, since the framework offers no
 * utterance-internal seek.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TtsTransportBar(
    appState: WhiteNoiseAppState,
    modifier: Modifier = Modifier,
    onBodyClick: (() -> Unit)? = null,
) {
    val state by appState.ttsController.state.collectAsState()
    val displayState = rememberTtsTransportDisplayState(state) ?: return
    val rateOverride by appState.ttsRatePreferences.rateOverride.collectAsState()
    val historyEdge by appState.ttsHistorySession.edgeState.collectAsState()
    val activeRate = rateOverride ?: appState.ttsRatePreferences.resolvedRate()
    // The estimate takes the controller lock, which the speech callback thread
    // also holds during playback, so it must not run on every recomposition.
    // Its only inputs are the playback state and the active rate — recompute
    // when one of those changes and reuse the result otherwise.
    val remainingSeconds =
        remember(state, activeRate) {
            appState.ttsController.estimatedMessageRemainingSeconds()
        }

    TtsTransportBarContent(
        state = displayState,
        rateOverride = rateOverride,
        activeRate = activeRate,
        onPause = { appState.ttsController.pause() },
        onResume = { appState.ttsController.resume() },
        // Navigation routes through the history session so an edge tap pages
        // the conversation instead of completing or clamping the queue.
        onPreviousSentence = { appState.ttsHistorySession.previousSentence() },
        onNextSentence = { appState.ttsHistorySession.nextSentence() },
        onPreviousMessage = { appState.ttsHistorySession.previousMessage() },
        onNextMessage = { appState.ttsHistorySession.nextMessage() },
        onRateSelected = appState::setTtsRateOverride,
        onStop = { appState.stopSpeaking() },
        remainingSeconds = remainingSeconds,
        modifier = modifier,
        historyEdge = historyEdge,
        onBodyClick = onBodyClick,
    )
}

/** Compact transport body: playback controls, progress, remaining time and history edges. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun TtsTransportBarContent(
    state: TtsState,
    rateOverride: Float?,
    activeRate: Float,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPreviousMessage: () -> Unit,
    onNextMessage: () -> Unit,
    onRateSelected: (Float?) -> Unit,
    onStop: () -> Unit,
    remainingSeconds: Int? = null,
    modifier: Modifier = Modifier,
    historyEdge: TtsHistoryEdgeState? = null,
    onBodyClick: (() -> Unit)? = null,
) {
    val isError = state is TtsState.Error
    val isPreparing = state is TtsState.Preparing
    val actionableBodyClick = onBodyClick.takeUnless { isError }
    val navigationEnabled = ttsNavigationEnabled(state, historyEdge)
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        val bodyModifier =
            if (actionableBodyClick == null) {
                Modifier
            } else {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.tts_bar_return_to_source),
                    onClick = actionableBodyClick,
                )
            }
        Box(modifier = Modifier.testTag(TTS_TRANSPORT_BODY_TAG).then(bodyModifier)) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = onPreviousMessage, enabled = navigationEnabled) {
                        Icon(Icons.Default.SkipPrevious, stringResource(R.string.tts_bar_previous_message))
                    }
                    IconButton(onClick = onPreviousSentence, enabled = navigationEnabled) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.tts_bar_skip_previous),
                        )
                    }
                    when (state) {
                        is TtsState.Speaking ->
                            FilledTonalIconButton(onClick = onPause) {
                                Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.tts_bar_pause))
                            }
                        is TtsState.Paused ->
                            FilledTonalIconButton(onClick = onResume) {
                                Icon(painterResource(R.drawable.ic_play_arrow), stringResource(R.string.tts_bar_play))
                            }
                        // Native terminal/error states have no resumable queue.
                        else -> Unit
                    }
                    IconButton(onClick = onNextSentence, enabled = navigationEnabled) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.tts_bar_skip_next),
                        )
                    }
                    IconButton(onClick = onNextMessage, enabled = navigationEnabled) {
                        Icon(Icons.Default.SkipNext, stringResource(R.string.tts_bar_next_message))
                    }
                    if (!isError) {
                        TtsTransportRatePicker(
                            rateOverride = rateOverride,
                            activeRate = activeRate,
                            onRateSelected = onRateSelected,
                        )
                    }
                    IconButton(onClick = onStop) {
                        Icon(
                            painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(R.string.tts_bar_stop),
                        )
                    }
                }
                TtsTransportProgress(state, isError, isPreparing, historyEdge, remainingSeconds)
            }
        }
    }
}

// Compact status line for a pending or failed history edge load, announced
// politely so TalkBack narrates the state change without stealing focus.

/** Status line for the history edge being loaded or exhausted. */
@Suppress("FunctionNaming")
@Composable
private fun HistoryEdgeStatus(historyEdge: TtsHistoryEdgeState) {
    val (text, color) =
        when (historyEdge) {
            is TtsHistoryEdgeState.Loading ->
                stringResource(R.string.tts_bar_history_loading) to MaterialTheme.colorScheme.onSurfaceVariant

            is TtsHistoryEdgeState.Failed ->
                stringResource(R.string.tts_bar_history_error) to MaterialTheme.colorScheme.error
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier =
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Reports the native message progress at full transport width without inventing utterance seeking. */
@Suppress("FunctionNaming")
@Composable
private fun TtsTransportProgress(
    state: TtsState,
    isError: Boolean,
    isPreparing: Boolean,
    historyEdge: TtsHistoryEdgeState?,
    remainingSeconds: Int?,
) {
    val showProgress = !isError && !isPreparing && ttsMessageCount(state) > 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showProgress) {
            val targetProgress = ttsMessageProgressFraction(state)
            val animatedProgress =
                key(ttsProgressAnimationKey(state)) {
                    val progress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = tween(durationMillis = 200),
                        label = "ttsMessageProgress",
                    )
                    progress
                }
            LinearProgressIndicator(
                progress = { animatedProgress },
                drawStopIndicator = {},
                modifier =
                    Modifier
                        .weight(1f)
                        .clearAndSetSemantics {},
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }
        if (historyEdge != null) {
            HistoryEdgeStatus(historyEdge)
        } else {
            when {
                isError -> Text(stringResource(R.string.tts_bar_error), color = MaterialTheme.colorScheme.error)
                isPreparing -> Text(stringResource(R.string.tts_bar_preparing))
                remainingSeconds != null ->
                    Text(
                        stringResource(R.string.tts_bar_seconds_remaining, remainingSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}
