package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import kotlinx.coroutines.delay

internal const val TTS_MESSAGE_PROGRESS_ANIMATION_MILLIS = 200
internal const val TTS_TERMINAL_COMPLETION_HOLD_MILLIS = TTS_MESSAGE_PROGRESS_ANIMATION_MILLIS + 50

internal fun ttsMessageIndex(state: TtsState): Int = state.messageIndex

internal fun ttsMessageCount(state: TtsState): Int = state.messageCount

internal fun ttsSentenceIndex(state: TtsState): Int = state.sentenceIndexWithinMessage

internal fun ttsSentenceCount(state: TtsState): Int = state.sentenceCountWithinMessage

internal fun ttsMessageProgressFraction(state: TtsState): Float = state.messageProgressFraction.coerceIn(0f, 1f)

internal fun ttsProgressAnimationKey(state: TtsState) = state.messageProgressGeneration to state.messageIndex

internal fun TtsState.Idle.isTerminalCompletion(): Boolean = messageCount > 0 && messageProgressFraction >= 1f

internal fun ttsTerminalCompletionDisplayState(
    lastActive: TtsState,
    terminalIdle: TtsState.Idle,
): TtsState.Idle =
    terminalIdle.copy(
        messageIndex = lastActive.messageIndex,
        messageCount = lastActive.messageCount,
        sentenceIndexWithinMessage = lastActive.sentenceIndexWithinMessage,
        sentenceCountWithinMessage = lastActive.sentenceCountWithinMessage,
        messagePreview = lastActive.messagePreview,
    )

@Suppress("FunctionNaming")
@Composable
internal fun rememberTtsTransportDisplayState(incoming: TtsState): TtsState? {
    var lastActive by remember { mutableStateOf<TtsState?>(null) }
    var animationFinished by remember { mutableStateOf(false) }
    val terminalIdle = incoming is TtsState.Idle && incoming.isTerminalCompletion()

    if (incoming !is TtsState.Idle) {
        SideEffect {
            lastActive = incoming
            animationFinished = false
        }
    }

    LaunchedEffect(terminalIdle, (incoming as? TtsState.Idle)?.messageProgressGeneration) {
        if (!terminalIdle) return@LaunchedEffect
        animationFinished = false
        delay(TTS_TERMINAL_COMPLETION_HOLD_MILLIS.toLong())
        animationFinished = true
    }

    return when {
        terminalIdle && lastActive != null && !animationFinished ->
            ttsTerminalCompletionDisplayState(lastActive!!, incoming)
        incoming is TtsState.Idle -> null
        else -> incoming
    }
}

internal fun ttsNavigationEnabled(state: TtsState): Boolean = state !is TtsState.Error && state !is TtsState.Idle

// A pending edge load owns the cursor: every navigation action disables so
// duplicate or conflicting requests can't queue up behind it.
internal fun ttsNavigationEnabled(
    state: TtsState,
    historyEdge: TtsHistoryEdgeState?,
): Boolean = ttsNavigationEnabled(state) && historyEdge !is TtsHistoryEdgeState.Loading
