package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationMediaViewerOpenRequest
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubble
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsQuickTransportViewportLock
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudProgress
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun timelineRowTtsHighlightPassage(
    messageIdHex: String,
    ttsState: TtsState,
): TtsPassage? =
    when (ttsState) {
        is TtsState.Speaking, is TtsState.Paused ->
            ttsState.passage?.takeIf { it.messageIdHex == messageIdHex }
        else -> null
    }

internal fun timelineRowTtsReadAloudProgress(
    messageIdHex: String,
    ttsState: TtsState,
): TtsReadAloudProgress? =
    when (ttsState) {
        is TtsState.Speaking, is TtsState.Paused -> {
            if (ttsState.passage?.messageIdHex != messageIdHex) return null
            TtsReadAloudProgress(
                sentenceIndex = ttsState.sentenceIndexWithinMessage,
                sentenceCount = ttsState.sentenceCountWithinMessage,
                messageIndex = ttsState.messageIndex,
                messageCount = ttsState.messageCount,
            )
        }
        else -> null
    }

internal fun timelineRowTtsFollowTarget(
    messageIdHex: String,
    state: TtsState,
): ConversationTtsFollowTarget? = state.conversationFollowTargetOrNull()?.takeIf { it.messageIdHex == messageIdHex }

internal data class TimelineRowTtsHighlightState(
    val passage: TtsPassage?,
    val progress: TtsReadAloudProgress?,
    val followTarget: ConversationTtsFollowTarget? = null,
)

/** Observes row-scoped TTS state and renders the bubble with conversation gesture arbitration. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun TimelineRowMessageBubble(
    messageIdHex: String,
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onOpenConversationMedia: (ConversationMediaViewerOpenRequest) -> Unit = {},
    eventCardResolver: NostrEventCardResolver? = null,
    documentSaveFallback: DocumentSaveFallback? = null,
    composerTextState: ComposerTextState,
    highlighted: Boolean,
    selectionMode: Boolean,
    textSelectionMode: Boolean,
    onTextSelectionModeChange: (Boolean) -> Unit,
    onTextSelectionBoundsChange: (Rect?) -> Unit,
    batchSelectable: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    rangeDragActive: Boolean,
    onDragSelectionStart: (Float) -> Unit,
    onDragSelection: (Float) -> Boolean,
    onDragSelectionEnd: () -> Unit,
    onDragSelectionCancel: () -> Unit,
    quickReactionEmojis: List<String>,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    isActionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
    onQuickReactionsSave: (List<String>) -> Unit,
    onQuickReactionsReset: () -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    composerGate: ComposerGate,
    onBack: () -> Unit,
    mentionCandidates: List<MentionComposer.Candidate>,
    mentionPickerEnabled: Boolean,
    showSenderName: Boolean,
    showSenderAvatar: Boolean,
    collapseLongMessages: Boolean,
    readOnly: Boolean,
    ttsQuickTransportViewportLock: TtsQuickTransportViewportLock? = null,
    ttsSentenceLayoutSink: ConversationTtsSentenceLayoutSink? = null,
    onTtsSentenceSeek: (TtsState) -> Unit = {},
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
) {
    val ttsHighlightState by rememberRowScopedTtsHighlightState(messageIdHex, appState)
    val ttsRowInstance = remember(messageIdHex) { Any() }
    val renderedInviteGroupIdHex = controller.group.groupIdHex
    val renderedInviteWelcomeMessageIdHex = controller.group.viaWelcomeMessageIdHex
    DisposableEffect(ttsSentenceLayoutSink, messageIdHex, ttsRowInstance) {
        ttsSentenceLayoutSink?.mountRow(messageIdHex, ttsRowInstance)
        onDispose {
            ttsSentenceLayoutSink?.unmountRow(messageIdHex, ttsRowInstance)
        }
    }
    MessageBubble(
        item = item,
        controller = controller,
        appState = appState,
        onOpenConversationMedia = onOpenConversationMedia,
        eventCardResolver = eventCardResolver,
        documentSaveFallback = documentSaveFallback,
        composerTextState = composerTextState,
        highlighted = highlighted,
        selectionMode = selectionMode,
        textSelectionMode = textSelectionMode,
        onTextSelectionModeChange = onTextSelectionModeChange,
        onTextSelectionBoundsChange = onTextSelectionBoundsChange,
        batchSelectable = batchSelectable,
        selected = selected,
        onToggleSelection = onToggleSelection,
        rangeDragActive = rangeDragActive,
        onDragSelectionStart = onDragSelectionStart,
        onDragSelection = onDragSelection,
        onDragSelectionEnd = onDragSelectionEnd,
        onDragSelectionCancel = onDragSelectionCancel,
        quickReactionEmojis = quickReactionEmojis,
        recentEmojis = recentEmojis,
        onEmojiUsed = onEmojiUsed,
        isActionMenuOpen = isActionMenuOpen,
        onActionMenuOpenChange = onActionMenuOpenChange,
        onQuickReactionsSave = onQuickReactionsSave,
        onQuickReactionsReset = onQuickReactionsReset,
        onReplyPreviewClick = onReplyPreviewClick,
        composerGate = composerGate,
        groupDisbanded = controller.group.disbanded,
        inviteMutationInFlight = controller.mutationInFlight,
        onJoinInvite = {
            appState.launchMutation {
                controller.acceptInvite(
                    renderedGroupIdHex = renderedInviteGroupIdHex,
                    renderedWelcomeMessageIdHex = renderedInviteWelcomeMessageIdHex,
                )
            }
        },
        onDeclineInvite = {
            appState.launchMutation {
                if (controller.declineInvite()) onBack()
            }
        },
        mentionCandidates = mentionCandidates,
        mentionPickerEnabled = mentionPickerEnabled,
        showSenderName = showSenderName,
        showSenderAvatar = showSenderAvatar,
        collapseLongMessages = collapseLongMessages,
        readOnly = readOnly,
        ttsHighlightPassage = ttsHighlightState.passage,
        ttsReadAloudProgress = ttsHighlightState.progress,
        ttsFollowTarget = ttsHighlightState.followTarget,
        ttsQuickTransportViewportLock = ttsQuickTransportViewportLock,
        ttsSentenceLayoutSink = ttsSentenceLayoutSink,
        onTtsSentenceSeek = onTtsSentenceSeek,
        ttsRowInstance = ttsRowInstance,
        parseMarkdown = parseMarkdown,
    )
}

@Composable
private fun rememberRowScopedTtsHighlightState(
    messageIdHex: String,
    appState: WhiteNoiseAppState,
) = produceState(
    initialValue = TimelineRowTtsHighlightState(passage = null, progress = null),
    key1 = appState,
    key2 = messageIdHex,
) {
    appState.ttsController.state
        .map { state ->
            TimelineRowTtsHighlightState(
                passage = timelineRowTtsHighlightPassage(messageIdHex, state),
                progress = timelineRowTtsReadAloudProgress(messageIdHex, state),
                followTarget = timelineRowTtsFollowTarget(messageIdHex, state),
            )
        }.distinctUntilChanged()
        .collect { value = it }
}
