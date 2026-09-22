package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.core.AgentOperationProjector
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLoadFailureEdge
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationMediaViewerOpenRequest
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsQuickTransportViewportLock
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import java.util.Locale

/** Renders one projected timeline item and delegates bubble gestures to the conversation owner. */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun TimelineRow(
    item: TimelineMessage,
    older: TimelineMessage?,
    newer: TimelineMessage?,
    transcriptLocale: Locale,
    entryUnreadCount: Int,
    entryUnreadDividerRetired: Boolean,
    entryFirstUnreadMessageId: String?,
    onMeasured: (itemId: String, heightPx: Int) -> Unit,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
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
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    composerGate: ComposerGate,
    onBack: () -> Unit,
    mentionCandidates: List<MentionComposer.Candidate>,
    mentionPickerEnabled: Boolean,
    collapseLongMessages: Boolean,
    ttsQuickTransportViewportLock: TtsQuickTransportViewportLock? = null,
    ttsSentenceLayoutSink: ConversationTtsSentenceLayoutSink? = null,
    onTtsSentenceSeek: (TtsState) -> Unit = {},
    onWave: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        val daySeparatorLabel =
            remember(older?.record?.recordedAt, item.record.recordedAt, transcriptLocale) {
                if (older == null || differentDay(older.record.recordedAt, item.record.recordedAt)) {
                    messageDayLabel(item.record.recordedAt, transcriptLocale)
                } else {
                    null
                }
            }
        val followsGroupEvent = older?.record?.let { MessageProjector.isGroupSystem(it) } == true
        val showUnreadDivider =
            shouldShowConversationEntryUnreadDivider(
                entryUnreadCount = entryUnreadCount,
                dividerRetired = entryUnreadDividerRetired,
                messageId = item.record.messageIdHex,
                firstUnreadMessageId = entryFirstUnreadMessageId,
            )
        if (daySeparatorLabel != null) {
            val hasLeadingHeader =
                controller.hasMoreBefore ||
                    controller.isLoadingOlder ||
                    (controller.error != null && controller.errorEdge == ConversationLoadFailureEdge.TOP) ||
                    controller.groupRecoveryReadFailed ||
                    controller.groupRecoveryStatus?.hasVisibleRecoveryState() == true
            DaySeparator(
                label = daySeparatorLabel,
                atTranscriptStart = older == null && !hasLeadingHeader,
                followsGroupEvent = followsGroupEvent,
            )
        }
        if (showUnreadDivider) {
            UnreadMessagesDivider(
                count = entryUnreadCount,
                followsDayHeader = daySeparatorLabel != null,
                followsGroupEvent = followsGroupEvent,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) onMeasured(item.record.messageIdHex, size.height)
                },
        ) {
            if (item.id.startsWith(ConversationController.STREAM_DEBUG_ID_PREFIX)) {
                if (appState.streamingDebugEnabled) {
                    StreamDebugEventRow(record = item.record)
                }
                return@Column
            }
            when (timelineRowKind(item.record, appState.streamingDebugEnabled)) {
                TimelineRowKind.GroupSystem -> {
                    GroupSystemRow(
                        record = item.record,
                        appState = appState,
                        groupSystem = item.projected?.groupSystem,
                        onWave = onWave,
                        onDeleteForMe =
                            if (controller.group.pendingConfirmation) {
                                null
                            } else {
                                {
                                    appState.launchMutation {
                                        controller.hideMessageForMe(item.record.messageIdHex)
                                    }
                                }
                            },
                    )
                    return@Column
                }
                TimelineRowKind.AgentOperation -> {
                    val projectedDeleted = item.projected?.deleted == true
                    val optimisticallyDeleted =
                        MessageProjector.isDeleted(
                            item.record.messageIdHex,
                            controller.deletedMessageIds,
                        )
                    val invalidated = item.projected?.invalidationStatus != null
                    if (
                        shouldRenderDedicatedAgentOperationRow(
                            projectedDeleted = projectedDeleted,
                            optimisticallyDeleted = optimisticallyDeleted,
                            invalidated = invalidated,
                        )
                    ) {
                        val operation = remember(item.record) { AgentOperationProjector.project(item.record) }
                        if (operation != null) {
                            AgentOperationTimelineRow(
                                item = item,
                                operation = operation,
                                controller = controller,
                                appState = appState,
                                readOnly = controller.group.pendingConfirmation,
                            )
                            return@Column
                        }
                    }
                }
                TimelineRowKind.DebugRow -> {
                    MessageDebugRow(
                        style = MessageDebugClassifier.debugStyle(item.record),
                        record = item.record,
                    )
                    return@Column
                }
                TimelineRowKind.Bubble -> Unit
            }
            val sameSenderAsOlderBubble =
                older?.let { candidate ->
                    conversationBubbleRowsShareSenderRun(
                        first = candidate,
                        second = item,
                        streamingDebugEnabled = appState.streamingDebugEnabled,
                        deletedMessageIds = controller.deletedMessageIds,
                    )
                } == true
            val sameSenderAsNewerBubble =
                newer?.let { candidate ->
                    conversationBubbleRowsShareSenderRun(
                        first = item,
                        second = candidate,
                        streamingDebugEnabled = appState.streamingDebugEnabled,
                        deletedMessageIds = controller.deletedMessageIds,
                    )
                } == true
            val senderDecoration =
                GroupProjector.transcriptSenderDecoration(
                    isDm = controller.usesDirectTranscriptChrome,
                    mine = controller.isMessageMine(item.record),
                    sameSenderAsOlderBubble = sameSenderAsOlderBubble,
                    sameSenderAsNewerBubble = sameSenderAsNewerBubble,
                )
            DismissMessageActionMenuOnDispose(
                messageId = item.record.messageIdHex,
                isOpen = isActionMenuOpen,
            ) {
                onActionMenuOpenChange(false)
            }
            // The prototype opens each sender cluster with a 12 dp gap; rows inside a run keep the list spacing,
            // and a row the unread divider already separated keeps the divider's interval alone.
            Box(
                Modifier.padding(
                    top =
                        conversationClusterTopGap(
                            sameSenderAsOlderBubble = sameSenderAsOlderBubble,
                            followsUnreadDivider = showUnreadDivider,
                        ),
                ),
            ) {
                key(item.record.messageIdHex) {
                    TimelineRowMessageBubble(
                        messageIdHex = item.record.messageIdHex,
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
                        onReplyPreviewClick = onReplyPreviewClick,
                        composerGate = composerGate,
                        onBack = onBack,
                        mentionCandidates = mentionCandidates,
                        mentionPickerEnabled = mentionPickerEnabled,
                        showSenderName = senderDecoration.showName,
                        showSenderAvatar = senderDecoration.showAvatar,
                        collapseLongMessages = collapseLongMessages,
                        readOnly = controller.group.pendingConfirmation,
                        ttsQuickTransportViewportLock = ttsQuickTransportViewportLock,
                        ttsSentenceLayoutSink = ttsSentenceLayoutSink,
                        onTtsSentenceSeek = onTtsSentenceSeek,
                    )
                }
            }
        }
    }
}
