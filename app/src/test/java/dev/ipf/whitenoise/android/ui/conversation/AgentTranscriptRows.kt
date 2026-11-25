package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.agent.LocalAgentTurnSteps
import dev.ipf.whitenoise.android.ui.conversation.agent.rememberAgentTurnSteps
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import java.util.Locale

/**
 * Draws chronological [rows] through the production `TimelineRow`, with real neighbours for grouping,
 * the transcript's row spacing and its agent turn folding.
 */
@Composable
internal fun AgentTranscriptRows(
    rows: List<TimelineMessage>,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    CompositionLocalProvider(LocalAgentTurnSteps provides rememberAgentTurnSteps(rows, "transcript")) {
        Column(verticalArrangement = Arrangement.spacedBy(CONVERSATION_TIMELINE_ROW_SPACING)) {
            rows.forEachIndexed { index, item -> AgentTranscriptRow(rows, index, item, appState, controller) }
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
