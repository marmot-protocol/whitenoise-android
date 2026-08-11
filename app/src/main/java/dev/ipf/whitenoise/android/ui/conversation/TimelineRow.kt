package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import dev.ipf.whitenoise.android.core.AgentActivityPresentation
import dev.ipf.whitenoise.android.core.AgentActivityProjector
import dev.ipf.whitenoise.android.core.AgentOperationProjector
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubble
import java.util.Locale

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun TimelineRow(
    item: TimelineMessage,
    older: TimelineMessage?,
    newer: TimelineMessage?,
    transcriptLocale: Locale,
    entryUnreadCount: Int,
    unreadIncomingCount: Int,
    entryUnreadDividerRetired: Boolean,
    entryFirstUnreadMessageId: String?,
    onMeasured: (itemId: String, heightPx: Int) -> Unit,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
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
    collapseLongMessages: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        val daySeparatorLabel =
            remember(older?.record?.recordedAt, item.record.recordedAt, transcriptLocale) {
                if (older == null || differentDay(older.record.recordedAt, item.record.recordedAt)) {
                    messageDayLabel(item.record.recordedAt, transcriptLocale)
                } else {
                    null
                }
            }
        if (daySeparatorLabel != null) {
            DaySeparator(daySeparatorLabel)
        }
        if (
            shouldShowConversationEntryUnreadDivider(
                entryUnreadCount = entryUnreadCount,
                liveUnreadCount = unreadIncomingCount,
                dividerRetired = entryUnreadDividerRetired,
                messageId = item.record.messageIdHex,
                firstUnreadMessageId = entryFirstUnreadMessageId,
            )
        ) {
            UnreadMessagesDivider(count = entryUnreadCount)
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
                        onDeleteForMe =
                            if (controller.group.pendingConfirmation) {
                                null
                            } else {
                                { controller.hideMessageForMe(item.record.messageIdHex) }
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
                TimelineRowKind.AgentActivity -> {
                    val projectedDeleted = item.projected?.deleted == true
                    val optimisticallyDeleted =
                        MessageProjector.isDeleted(
                            item.record.messageIdHex,
                            controller.deletedMessageIds,
                        )
                    val invalidated = item.projected?.invalidationStatus != null
                    if (!projectedDeleted && !optimisticallyDeleted && !invalidated) {
                        val fallbackText =
                            androidx.compose.ui.res.stringResource(
                                dev.ipf.whitenoise.android.R.string.notification_channel_agent_activity,
                            )
                        val activity =
                            remember(item.record, fallbackText) {
                                AgentActivityProjector.project(item.record, fallbackText)
                            }
                                ?: AgentActivityPresentation(fallbackText, status = null)
                        AgentActivityTimelineRow(
                            item = item,
                            activity = activity,
                            controller = controller,
                            appState = appState,
                            readOnly = controller.group.pendingConfirmation,
                        )
                    }
                    return@Column
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
                    isDm = controller.isDm,
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
            MessageBubble(
                item = item,
                controller = controller,
                appState = appState,
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
                onJoinInvite = { appState.launchMutation { controller.acceptInvite() } },
                onDeclineInvite = {
                    appState.launchMutation {
                        if (controller.declineInvite()) onBack()
                    }
                },
                mentionCandidates = mentionCandidates,
                mentionPickerEnabled = mentionPickerEnabled,
                showSenderName = senderDecoration.showName,
                showSenderAvatar = senderDecoration.showAvatar,
                collapseLongMessages = collapseLongMessages,
                readOnly = controller.group.pendingConfirmation,
            )
        }
    }
}
