package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import java.util.Locale

/**
 * Draws chronological [rows] through the production `TimelineRow`, with real neighbours for grouping,
 * the transcript's row spacing and activity filtering.
 */
@Composable
internal fun AgentTranscriptRows(
    rows: List<TimelineMessage>,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    val visibleRows = rows.filter(::conversationTimelineRowVisible)
    Column(verticalArrangement = Arrangement.spacedBy(CONVERSATION_TIMELINE_ROW_SPACING)) {
        visibleRows.forEachIndexed { index, item ->
            AgentTranscriptRow(visibleRows, index, item, appState, controller)
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun AgentTranscriptRow(
    rows: List<TimelineMessage>,
    index: Int,
    item: TimelineMessage,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    TimelineRow(
        item = item,
        older = rows.getOrNull(index - 1),
        newer = rows.getOrNull(index + 1),
        transcriptLocale = Locale.US,
        entryUnreadCount = 0,
        entryUnreadDividerRetired = true,
        entryFirstUnreadMessageId = null,
        onMeasured = { _, _ -> },
        appState = appState,
        controller = controller,
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
        collapseLongMessages = false,
    )
}
