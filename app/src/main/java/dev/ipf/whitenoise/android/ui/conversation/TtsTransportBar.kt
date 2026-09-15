package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
 * separate actions on their own row, so the layout stays usable at narrow
 * widths and large font scales without clipping. No scrub gesture, since the
 * framework offers no utterance-internal seek.
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
        modifier = modifier,
        historyEdge = historyEdge,
        onBodyClick = onBodyClick,
    )
}

/** Transport bar body: preview, rate, history edges and the playback controls. */
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
    modifier: Modifier = Modifier,
    historyEdge: TtsHistoryEdgeState? = null,
    onBodyClick: (() -> Unit)? = null,
) {
    val isError = state is TtsState.Error
    val isPreparing = state is TtsState.Preparing
    val actionableBodyClick = onBodyClick.takeUnless { isError }
    val navigationEnabled = ttsNavigationEnabled(state, historyEdge)
    val maximumHeight =
        with(LocalDensity.current) {
            (
                LocalWindowInfo.current.containerSize.height
                    .toDp() * 0.45f
            ).coerceAtLeast(96.dp)
        }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            modifier =
                Modifier
                    .heightIn(max = maximumHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag(TTS_TRANSPORT_BODY_TAG)
                            .then(bodyModifier)
                            .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        TtsTransportPreview(state, isError, isPreparing, historyEdge)
                    }
                }
                IconButton(onClick = onStop) {
                    Icon(
                        painterResource(R.drawable.ic_stop),
                        contentDescription = stringResource(R.string.tts_bar_stop),
                    )
                }
            }
            TtsTransportProgress(state, isError, isPreparing)
            FlowRow(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
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
            }
            FlowRow(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onPreviousMessage, enabled = navigationEnabled) {
                    Text(stringResource(R.string.tts_bar_previous_message))
                }
                TextButton(onClick = onNextMessage, enabled = navigationEnabled) {
                    Text(stringResource(R.string.tts_bar_next_message))
                }
                if (!isError) {
                    TtsTransportRatePicker(
                        rateOverride = rateOverride,
                        activeRate = activeRate,
                        onRateSelected = onRateSelected,
                    )
                }
            }
        }
    }
}

// Compact status line for a pending or failed history edge load, announced
// politely so TalkBack narrates the state change without stealing focus.

/** Status line for the history edge being loaded or exhausted. */
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
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .padding(top = 2.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Current passage preview with its preparing and error states. */
@Suppress("FunctionNaming")
@Composable
private fun TtsTransportPreview(
    state: TtsState,
    isError: Boolean,
    isPreparing: Boolean,
    historyEdge: TtsHistoryEdgeState?,
) {
    val showProgress = !isError && !isPreparing && ttsMessageCount(state) > 0
    val preview =
        if (isPreparing) {
            stringResource(R.string.tts_bar_preparing)
        } else if (isError) {
            stringResource(R.string.tts_bar_error)
        } else {
            state.messagePreview
        }
    if (preview.isNotBlank()) {
        val colors = MaterialTheme.colorScheme
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.error else colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (showProgress && ttsSentenceCount(state) > 0) {
        Text(
            text =
                stringResource(
                    R.string.tts_bar_progress,
                    ttsSentenceIndex(state) + 1,
                    ttsSentenceCount(state),
                    ttsMessageIndex(state) + 1,
                    ttsMessageCount(state),
                ),
            style =
                if (historyEdge == null) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    HistoryEdgeStatus(historyEdge)
}

/** Reports the native message progress at full transport width without inventing utterance seeking. */
@Suppress("FunctionNaming")
@Composable
private fun TtsTransportProgress(
    state: TtsState,
    isError: Boolean,
    isPreparing: Boolean,
) {
    val showProgress = !isError && !isPreparing && ttsMessageCount(state) > 0
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
        // The progress text above already narrates the position.
        LinearProgressIndicator(
            progress = { animatedProgress },
            drawStopIndicator = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clearAndSetSemantics {},
        )
    }
}
