@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.state.AGENT_DEMO_NAME
import dev.ipf.whitenoise.android.state.AGENT_DEMO_PEER_ID
import dev.ipf.whitenoise.android.state.AGENT_DEMO_PEER_NAME
import dev.ipf.whitenoise.android.state.AgentDemoPhase
import dev.ipf.whitenoise.android.state.AgentDemoSession
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.agentDemoController
import dev.ipf.whitenoise.android.state.conversationAttentionTimeline
import dev.ipf.whitenoise.android.state.nameAgentDemo
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.chats.ChatRowLayout
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.conversation.agent.AgentRequestTransport
import dev.ipf.whitenoise.android.ui.conversation.agent.LocalAgentRequestTransport
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar

private enum class DemoChat(
    val title: String,
    val preview: String,
) {
    Person(AGENT_DEMO_PEER_NAME, "I can review the greeting this afternoon."),
    Agent(AGENT_DEMO_NAME, "Send a prompt to start a turn"),
    Group("Release planning", "Maya: Can we try a shorter greeting?"),
}

@Composable
internal fun AgentDemoInbox(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
) {
    val viewerId = appState.activeAccount?.accountIdHex ?: return
    val scope = rememberCoroutineScope()
    val chats =
        remember(viewerId) {
            DemoChat.entries.associateWith { AgentDemoSession(scope, viewerId, parseMarkdown) }
        }
    var selected by remember(viewerId) { mutableStateOf<DemoChat?>(null) }
    val drafts = remember(viewerId) { mutableStateMapOf<DemoChat, TextFieldValue>() }
    val chat = selected
    DisposableEffect(chats) { onDispose { chats.values.forEach(AgentDemoSession::close) } }
    LaunchedEffect(chats) {
        appState.nameAgentDemo()
        chats.getValue(DemoChat.Person).addMessage(AGENT_DEMO_PEER_ID, DemoChat.Person.preview)
        chats.getValue(DemoChat.Group).addMessage(AGENT_DEMO_PEER_ID, "Can we try a shorter greeting?")
    }
    BackHandler(chat != null) { selected = null }
    if (chat == null) {
        DemoChatList(chats, onBack) { selected = it }
    } else {
        DemoConversation(chat, chats.getValue(chat), appState, drafts) { selected = null }
    }
}

@Composable
private fun DemoChatList(
    chats: Map<DemoChat, AgentDemoSession>,
    onBack: () -> Unit,
    onOpen: (DemoChat) -> Unit,
) {
    SettingsScaffold(
        title = "Demo chats",
        onBack = onBack,
        containerColor = MaterialTheme.colorScheme.surface,
        topBarContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        WhiteNoiseLazyColumn {
            items(DemoChat.entries, key = { it.name }) { chat ->
                val session = chats.getValue(chat)
                val message = session.records.lastOrNull { MessageProjector.isChatKind(it.kind) }
                val preview =
                    when {
                        session.phase == AgentDemoPhase.Running -> demoAgentPreview(session)
                        message != null -> message.plaintext
                        else -> chat.preview
                    }
                ChatRowLayout(
                    title = chat.title,
                    timestampAt = message?.recordedAt ?: 0uL,
                    rowHasUnread = false,
                    selectionMode = false,
                    selected = false,
                    leadingContent = { Avatar(chat.title, chat.name, 44.dp) },
                    supportingContent = { Text(preview, maxLines = 1) },
                    supportingMetadata = null,
                    modifier = Modifier.testTag("agent_demo.chat.${chat.name.lowercase()}"),
                    onClick = { onOpen(chat) },
                )
            }
        }
    }
}

@Composable
private fun DemoConversation(
    chat: DemoChat,
    session: AgentDemoSession,
    appState: WhiteNoiseAppState,
    drafts: MutableMap<DemoChat, TextFieldValue>,
    onBack: () -> Unit,
) {
    val viewerId = appState.activeAccount?.accountIdHex ?: return
    val controller =
        remember(chat, viewerId) {
            agentDemoController(appState, viewerId, mixed = chat == DemoChat.Group)
        }
    val transport =
        remember(session) {
            AgentRequestTransport { _, target, request ->
                SendAcceptDispositionFfi.PUBLISHED.takeIf { session.receive(target, request) }
            }
        }
    SettingsScaffold(
        title = chat.title,
        onBack = onBack,
        containerColor = MaterialTheme.colorScheme.surface,
        topBarContainerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { DemoComposer(chat, session, viewerId, drafts) },
    ) {
        CompositionLocalProvider(LocalAgentRequestTransport provides transport) {
            AgentDemoTranscript(session, viewerId, appState, controller)
        }
    }
}

@Composable
private fun DemoComposer(
    chat: DemoChat,
    session: AgentDemoSession,
    viewerId: String,
    drafts: MutableMap<DemoChat, TextFieldValue>,
) {
    ComposerBar(
        replyingTo = null,
        ownerAccountIdHex = viewerId,
        messageTextCopy = MessageTextCopy.Default,
        onCancelReply = {},
        onSend = { text, accepted ->
            if (chat == DemoChat.Person) {
                session.addMessage(viewerId, text)
                accepted()
            } else if (session.phase != AgentDemoPhase.Running) {
                session.sendPrompt(text)
                accepted()
            }
        },
        modifier = Modifier.imePadding().navigationBarsPadding().testTag("agent_demo.composer"),
        initialDraft = drafts[chat] ?: TextFieldValue(),
        draftKey = chat,
        onDraftChange = { draft, _ -> drafts[chat] = draft },
        dictationControlsVisible = false,
    )
}

private fun demoAgentPreview(session: AgentDemoSession): String {
    val timeline = session.records.map { TimelineMessage(it.messageIdHex, it, MessageStatus.Received) }
    val request =
        conversationAttentionTimeline(timeline)
            .asReversed()
            .asSequence()
            .mapNotNull { AgentItemProjector.project(it.record) }
            .firstOrNull {
                it.state == AgentItemState.Waiting &&
                    (it.body is AgentItemBody.Approval || it.body is AgentItemBody.Question)
            }
    return when (request?.body) {
        is AgentItemBody.Approval -> "Approval needed"
        is AgentItemBody.Question -> "Question waiting"
        else -> "Agent working"
    }
}
