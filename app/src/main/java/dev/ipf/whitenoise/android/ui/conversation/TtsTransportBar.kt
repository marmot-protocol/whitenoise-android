package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanation
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.ArrowForward

internal const val TTS_TRANSPORT_BODY_TAG = "tts-transport-body"

/**
 * Shared read-aloud controls hosted by the app shell, normal chat-list flow,
 * or the modal text reader, on the same one-row strip as the voice player.
 * Sentence steps stay on the row and message steps sit under More options.
 * The row scrolls horizontally only when large text leaves no room. No scrub
 * gesture, since the framework offers no utterance-internal seek.
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

/**
 * Compact read-aloud body on the shared [PlaybackStrip]: play/pause, the remaining-time or status line,
 * previous and next sentence, the speech rate, message steps under More options, and stop. A pending or failed
 * history edge adds one announced status line under the row while it applies.
 */
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
    val navigationEnabled = ttsNavigationEnabled(state, historyEdge)
    val showProgress = !isError && !isPreparing && ttsMessageCount(state) > 0
    val targetProgress = if (showProgress) ttsMessageProgressFraction(state) else 0f
    val animatedProgress =
        key(ttsProgressAnimationKey(state)) {
            val progress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = TTS_MESSAGE_PROGRESS_ANIMATION_MILLIS),
                label = "ttsMessageProgress",
            )
            progress
        }
    PlaybackStrip(
        progress = { animatedProgress }.takeIf { showProgress },
        modifier = modifier,
        bodyModifier = Modifier.testTag(TTS_TRANSPORT_BODY_TAG),
        onBodyClick = onBodyClick.takeUnless { isError },
        // The remaining-time label already speaks the position; the bar would repeat it as a percentage.
        announceProgress = false,
        status =
            if (historyEdge != null) {
                { HistoryEdgeStatus(historyEdge) }
            } else {
                null
            },
        leading = { TtsLeadingControl(state, onPause, onResume) },
        info = { PlaybackStripInfo(title = null) { TtsStatusText(isError, isPreparing, remainingSeconds) } },
        actions = {
            TtsNavigationButton(
                onClick = onPreviousSentence,
                enabled = navigationEnabled,
                label = R.string.tts_bar_skip_previous,
                explanation = R.string.explanation_conversation_tts_previous_sentence,
            ) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.tts_bar_skip_previous)) }
            TtsNavigationButton(
                onClick = onNextSentence,
                enabled = navigationEnabled,
                label = R.string.tts_bar_skip_next,
                explanation = R.string.explanation_conversation_tts_next_sentence,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.tts_bar_skip_next)) }
            if (!isError) {
                TtsTransportRatePicker(
                    rateOverride = rateOverride,
                    activeRate = activeRate,
                    onRateSelected = onRateSelected,
                )
            }
            TtsMessageMenu(
                enabled = navigationEnabled,
                onPreviousMessage = onPreviousMessage,
                onNextMessage = onNextMessage,
            )
            PlaybackStripCloseButton(stringResource(R.string.tts_bar_stop), onStop)
        },
    )
}

/** Pause or resume while a queue is live; a spinner while speech prepares, an error glyph after a failure. */
@Suppress("FunctionNaming")
@Composable
private fun TtsLeadingControl(
    state: TtsState,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    when (state) {
        is TtsState.Speaking ->
            PlaybackStripPlayButton(playing = true, stringResource(R.string.tts_bar_pause), onPause)
        is TtsState.Paused ->
            PlaybackStripPlayButton(playing = false, stringResource(R.string.tts_bar_play), onResume)
        is TtsState.Preparing ->
            PlaybackStripLeadingSlot {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        is TtsState.Error ->
            PlaybackStripLeadingSlot {
                Icon(painterResource(R.drawable.ic_error), null, tint = MaterialTheme.colorScheme.error)
            }
        // Native terminal states have no resumable queue; the slot stays so the row does not shift.
        else -> PlaybackStripLeadingSlot()
    }
}

@Suppress("FunctionNaming")
@Composable
private fun TtsNavigationButton(
    onClick: () -> Unit,
    enabled: Boolean,
    @StringRes label: Int,
    @StringRes explanation: Int,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.explainOnLongPress(composerExplanation(label, explanation)),
        content = icon,
    )
}

/** Message jumps are available from the read-aloud strip’s More options menu. */
@Suppress("FunctionNaming")
@Composable
private fun TtsMessageMenu(
    enabled: Boolean,
    onPreviousMessage: () -> Unit,
    onNextMessage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        WhiteNoiseDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            items =
                listOf(
                    WhiteNoiseMenuItem(
                        label = stringResource(R.string.tts_bar_previous_message),
                        onClick = onPreviousMessage,
                        enabled = enabled,
                        modifier =
                            Modifier.explainOnLongPress(
                                composerExplanation(
                                    R.string.tts_bar_previous_message,
                                    R.string.explanation_conversation_tts_previous_message,
                                ),
                            ),
                    ),
                    WhiteNoiseMenuItem(
                        label = stringResource(R.string.tts_bar_next_message),
                        onClick = onNextMessage,
                        enabled = enabled,
                        modifier =
                            Modifier.explainOnLongPress(
                                composerExplanation(
                                    R.string.tts_bar_next_message,
                                    R.string.explanation_conversation_tts_next_message,
                                ),
                            ),
                    ),
                ),
        )
    }
}

/** Remaining time, or the preparing/failed status in its place. */
@Suppress("FunctionNaming")
@Composable
private fun TtsStatusText(
    isError: Boolean,
    isPreparing: Boolean,
    remainingSeconds: Int?,
) {
    val style = MaterialTheme.typography.labelMedium
    when {
        isError ->
            Text(stringResource(R.string.tts_bar_error), style = style, color = MaterialTheme.colorScheme.error)
        isPreparing ->
            Text(
                stringResource(R.string.tts_bar_preparing),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        remainingSeconds != null -> {
            val resources = LocalResources.current
            val description = ttsRemainingDescription(resources, remainingSeconds)
            Text(
                ttsRemainingLabel(resources, remainingSeconds),
                modifier = Modifier.semantics { contentDescription = description },
                // Direction follows the label's own script, so in an RTL layout an English
                // estimate keeps its leading ~ instead of moving it to the far end.
                style = style.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else ->
            Text(
                stringResource(R.string.read_aloud),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/** Status line for the history edge being loaded or exhausted, announced politely without stealing focus. */
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
