package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.core.AgentOperationProjector
import dev.ipf.whitenoise.android.core.DateTimeFormatState
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLoadFailureEdge
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatHistoryTransfer
import dev.ipf.whitenoise.android.state.importedHistoryBoundary
import dev.ipf.whitenoise.android.state.isLoadingOlder
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.conversation.agent.AgentItemRowContext
import dev.ipf.whitenoise.android.ui.conversation.agent.AgentItemTimelineRow
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationMediaViewerOpenRequest
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsQuickTransportViewportLock
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.reactions.LocalReactionMotion
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionMotionRow
import dev.ipf.whitenoise.android.ui.conversation.reactions.rememberLocalReactionMotion
import dev.ipf.whitenoise.android.ui.group.isCurrentGroupMutationOwner
import java.util.Locale

/** Renders one projected timeline item with its leading separators and delegates bubble gestures to the owner. */
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
    onForwardMenuHandoff: () -> Unit = {},
    onQuickReactionsSave: (List<String>) -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    composerGate: ComposerGate,
    collapseLongMessages: Boolean,
    ttsQuickTransportViewportLock: TtsQuickTransportViewportLock? = null,
    ttsSentenceLayoutSink: ConversationTtsSentenceLayoutSink? = null,
    onTtsSentenceSeek: (TtsState) -> Unit = {},
    onWave: (suspend (String, () -> Unit) -> Unit)? = null,
    onBeforeBodyExpand: () -> Unit = {},
    onBeforeBodyCollapse: () -> Unit = {},
    reactionViewport: () -> Rect? = { null },
    modifier: Modifier = Modifier,
    senderGapMeasurements: ConversationSenderGapMeasurements? = null,
) = traceConversationUiSection(ConversationUiTrace.ROW) {
    val reactionMotion = rememberTimelineReactionMotion(item, controller, appState, composerGate, selectionMode)
    ReactionMotionRow(reactionMotion, modifier, reactionViewport) {
        val dateFormat = DateTimeFormatState.dateFormat
        val daySeparatorLabel =
            remember(older?.record?.recordedAt, item.record.recordedAt, transcriptLocale, dateFormat) {
                if (older == null || differentDay(older.record.recordedAt, item.record.recordedAt)) {
                    messageDayLabel(item.record.recordedAt, transcriptLocale, dateFormat)
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
        val historyImport = LocalChatHistoryImport.current()
        val historyBoundary = importedHistoryBoundary(older?.projected, item.projected, historyImport)
        // Draw the import line ahead of the day separator to close the imported rows.
        ImportedHistoryMarkers(
            transfer = if (older == null && !controller.hasMoreBefore) chatHistoryTransfer(historyImport) else null,
            boundary = historyBoundary,
        )
        if (daySeparatorLabel != null) {
            val hasLeadingHeader =
                controller.hasMoreBefore ||
                    controller.isLoadingOlder ||
                    (controller.error != null && controller.errorEdge == ConversationLoadFailureEdge.TOP) ||
                    controller.conversationGroupRecoveryRowVisible()
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
        val hasLeadingSeparator = daySeparatorLabel != null || showUnreadDivider || historyBoundary != null
        val senderGap =
            if (!hasLeadingSeparator &&
                conversationBubbleSenderChanges(
                    older,
                    item,
                    appState.streamingDebugEnabled,
                    controller.deletedMessageIds,
                )
            ) {
                CONVERSATION_SENDER_CHANGE_EXTRA_SPACING
            } else {
                0.dp
            }
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) onMeasured(item.record.messageIdHex, size.height)
                }.measureConversationSenderGap(senderGapMeasurements, item.presentationId, senderGap)
                .padding(top = senderGap),
        ) {
            when (timelineRowKind(item.record, appState.streamingDebugEnabled)) {
                TimelineRowKind.GroupSystem -> {
                    GroupSystemTimelineRow(
                        item = item,
                        controller = controller,
                        appState = appState,
                        quickReactionEmojis = quickReactionEmojis,
                        recentEmojis = recentEmojis,
                        onEmojiUsed = onEmojiUsed,
                        isActionMenuOpen = isActionMenuOpen,
                        onActionMenuOpenChange = onActionMenuOpenChange,
                        readOnly = composerGate != ComposerGate.COMPOSER || selectionMode,
                        onWave = onWave,
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
                TimelineRowKind.AgentItem -> {
                    val agentItem =
                        remember(item.record, item.projected) { AgentItemReader.read(item.record, item.projected) }
                    if (agentItem != null) {
                        AgentItemTimelineRow(
                            agentItem = agentItem,
                            controller = controller,
                            appState = appState,
                            context =
                                AgentItemRowContext(
                                    showSenderName =
                                        older?.let { candidate ->
                                            conversationBubbleRowsShareSenderRun(
                                                first = candidate,
                                                second = item,
                                                streamingDebugEnabled = appState.streamingDebugEnabled,
                                                deletedMessageIds = controller.deletedMessageIds,
                                            )
                                        } != true,
                                    readOnly =
                                        composerGate != ComposerGate.COMPOSER ||
                                            selectionMode ||
                                            controller.group.pendingConfirmation,
                                    showSenderAvatar =
                                        newer?.let { candidate ->
                                            conversationBubbleRowsShareSenderRun(
                                                first = item,
                                                second = candidate,
                                                streamingDebugEnabled = appState.streamingDebugEnabled,
                                                deletedMessageIds = controller.deletedMessageIds,
                                            )
                                        } != true,
                                ),
                        )
                        return@Column
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
                messageId = item.presentationId,
                isOpen = isActionMenuOpen,
            ) {
                onActionMenuOpenChange(false)
            }
            key(controller.boundAccountRef, controller.group.groupIdHex, item.presentationId) {
                TimelineRowMessageBubble(
                    messageIdHex = item.record.messageIdHex,
                    item = item,
                    controller = controller,
                    appState = appState,
                    onOpenConversationMedia = onOpenConversationMedia,
                    eventCardResolver = eventCardResolver,
                    documentSaveFallback = documentSaveFallback,
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
                    onForwardMenuHandoff = onForwardMenuHandoff,
                    onQuickReactionsSave = onQuickReactionsSave,
                    onReplyPreviewClick = onReplyPreviewClick,
                    onBeforeBodyExpand = onBeforeBodyExpand,
                    onBeforeBodyCollapse = onBeforeBodyCollapse,
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

/** Decorative ownership follows the same current targets as the row's existing action dispatch. */
@Composable
private fun rememberTimelineReactionMotion(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    gate: ComposerGate,
    selectionMode: Boolean,
): LocalReactionMotion {
    val runtime = remember(controller) { appState.runtimeGeneration }
    val targetAvailable =
        when {
            MessageProjector.isGroupSystem(item.record) ->
                currentGroupSystemReactionTarget(
                    controller,
                    GroupSystemReactionOwner(
                        controller.boundAccountRef,
                        item.record.groupIdHex,
                        item.record.messageIdHex,
                    ),
                ) != null
            MessageProjector.isPollKind(item.record.kind) ->
                currentPollActionTarget(
                    controller,
                    PollMessageActionOwner(
                        controller.boundAccountRef,
                        item.record.groupIdHex,
                        item.record.messageIdHex,
                    ),
                ) != null
            else ->
                item.projected?.deleted != true &&
                    item.projected?.invalidationStatus == null &&
                    !MessageProjector.isDeleted(item.record.messageIdHex, controller.deletedMessageIds)
        }
    val currentOwner =
        !appState.appLock.screenVisible &&
            controller.isCurrentGroupMutationOwner(appState, runtime) &&
            controller.acceptsConversationActionOwner(controller.boundAccountRef, controller.group.groupIdHex)
    val readOnly =
        if (MessageProjector.isGroupSystem(item.record)) {
            gate != ComposerGate.COMPOSER
        } else {
            controller.group.pendingConfirmation
        }
    val canReact = !readOnly && !selectionMode
    val available = targetAvailable && canReact && currentOwner
    return rememberLocalReactionMotion(
        controller to item.presentationId,
        enabled = rememberConversationMotionEnabled() && available,
    )
}
