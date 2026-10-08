package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.tts.TtsSpeechEngine
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import java.util.Locale

/** Shared production-row fixture for paint and viewport tests, without a second bubble implementation. */
internal class PaintTimelineRow(
    private val controller: ConversationController,
    private val appState: WhiteNoiseAppState,
    private val sentenceLayouts: ConversationTtsSentenceLayoutRegistry,
) {
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    @Composable
    @Suppress("FunctionNaming", "LongMethod")
    fun Render(
        item: TimelineMessage,
        collapseLongMessages: Boolean = false,
    ) {
        TimelineRowMessageBubble(
            messageIdHex = item.record.messageIdHex,
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = false,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            onBack = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderName = false,
            showSenderAvatar = false,
            collapseLongMessages = collapseLongMessages,
            readOnly = false,
            ttsSentenceLayoutSink = sentenceLayouts,
            parseMarkdown = { item.record.contentTokens },
        )
    }
}

internal class FakePaintTtsSpeechEngine : TtsSpeechEngine {
    private val spoken = mutableListOf<String>()
    private var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null

    override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

    override fun setSpeechRate(rate: Float) = Unit

    override fun setCallbacks(
        onStart: (String?) -> Unit,
        onDone: (String?) -> Unit,
        onError: (String?, Int) -> Unit,
        onRangeStart: (String?, Int, Int, Int) -> Unit,
        onStop: (String?, Boolean) -> Unit,
    ) {
        rangeCallback = onRangeStart
    }

    override fun clearCallbacks() {
        rangeCallback = null
    }

    override fun speak(
        text: String,
        utteranceId: String,
    ): Int {
        spoken += utteranceId
        return TextToSpeech.SUCCESS
    }

    override fun stop() = Unit

    fun range(
        index: Int,
        start: Int,
        end: Int,
    ) {
        rangeCallback?.invoke(spoken[index], start, end, 0)
    }
}
