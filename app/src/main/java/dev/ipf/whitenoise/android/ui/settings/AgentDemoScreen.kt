@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AgentDemoPhase
import dev.ipf.whitenoise.android.state.AgentDemoSession
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.agentDemoController
import dev.ipf.whitenoise.android.state.conversationAttentionTimeline
import dev.ipf.whitenoise.android.state.nameAgentDemo
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.conversation.TimelineRow
import dev.ipf.whitenoise.android.ui.conversation.agent.AgentRequestTransport
import dev.ipf.whitenoise.android.ui.conversation.agent.LocalAgentRequestTransport
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate

/**
 * Developer Tools' agent demo: a direct chat with a scripted agent that runs on this phone.
 *
 * The rows are the conversation's own: the same timeline rows, folding and request buttons. Only
 * the source differs: records come from [AgentDemoSession] and answers go back to it instead of a
 * relay, so approvals, questions and Stop can be tried without a running agent.
 */
@Composable
internal fun AgentDemoScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
) {
    val viewerId = appState.activeAccount?.accountIdHex
    if (viewerId == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    val session =
        remember(viewerId) {
            AgentDemoSession(scope, viewerId, parseMarkdown)
        }
    val controller = remember(viewerId) { agentDemoController(appState, viewerId) }
    val transport =
        remember(session) {
            AgentRequestTransport { _, target, request ->
                SendAcceptDispositionFfi.PUBLISHED.takeIf { session.receive(target, request) }
            }
        }
    DisposableEffect(session) { onDispose(session::close) }
    LaunchedEffect(session) {
        appState.nameAgentDemo()
        if (session.phase == AgentDemoPhase.Idle) session.start()
    }
    SettingsScaffold(
        title = stringResource(R.string.agent_demo_title),
        onBack = onBack,
        containerColor = MaterialTheme.colorScheme.surface,
        topBarContainerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { AgentDemoFooter(session.phase, onRestart = session::start) },
    ) {
        CompositionLocalProvider(LocalAgentRequestTransport provides transport) {
            AgentDemoTranscript(session, viewerId, appState, controller)
        }
    }
}

@Composable
internal fun AgentDemoTranscript(
    session: AgentDemoSession,
    viewerId: String,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    val rows by remember(session) {
        derivedStateOf {
            conversationAttentionTimeline(
                session.records.map { record ->
                    val status = if (record.sender == viewerId) MessageStatus.Sent else MessageStatus.Received
                    TimelineMessage(id = "msg:${record.messageIdHex}", record = record, status = status)
                },
            )
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    WhiteNoiseLazyColumn(
        state = listState,
        // The conversation screen insets its transcript by the same 12 dp.
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("agent_demo.transcript"),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
            AgentDemoRow(row, rows.getOrNull(index - 1), rows.getOrNull(index + 1), appState, controller)
        }
    }
}

/** One production timeline row, read-only except for the agent rows' own buttons. */
@Composable
@Suppress("LongMethod")
private fun AgentDemoRow(
    row: TimelineMessage,
    older: TimelineMessage?,
    newer: TimelineMessage?,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    TimelineRow(
        item = row,
        older = older,
        newer = newer,
        transcriptLocale = LocalConfiguration.current.locales[0],
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

/** Where the composer would be: what this chat is, and a way to run the turn again. */
@Composable
private fun AgentDemoFooter(
    phase: AgentDemoPhase,
    onRestart: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AgentDemoFooterRow(phase, onRestart)
        }
    }
}

@Composable
private fun AgentDemoFooterRow(
    phase: AgentDemoPhase,
    onRestart: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.agent_demo_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRestart,
            enabled = phase != AgentDemoPhase.Idle,
            modifier = Modifier.testTag("agent_demo.restart"),
        ) {
            Text(stringResource(R.string.agent_demo_restart))
        }
    }
}
