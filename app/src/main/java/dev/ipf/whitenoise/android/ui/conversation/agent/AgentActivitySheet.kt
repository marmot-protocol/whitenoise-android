@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.state.AgentRequestProgress
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.settings.SettingsGroupScope
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/** Keeps the sheet and action progress bound to the account's current conversation. */
@Composable
internal fun rememberConversationAgentActivity(controller: ConversationController): ConversationAgentActivity {
    val timeline = controller.timeline
    val hasActivity = remember(controller) { mutableStateOf(false) }
    val windowHasActivity = remember(timeline) { timeline.any { it.projected?.agentItem != null } }
    if (windowHasActivity) SideEffect { hasActivity.value = true }
    val showShortcut = windowHasActivity || hasActivity.value
    val open = remember(controller) { mutableStateOf(false) }
    val threadPath = remember(controller) { mutableStateOf(emptyList<String>()) }
    val progress = rememberAgentRequestProgress(controller)
    val projected = remember(timeline) { timeline.mapNotNull { it.projected } }
    ObserveAgentSettlements(controller, progress, projected)
    ObserveAgentSettlementEvents(controller, progress)
    return remember(controller, showShortcut) {
        ConversationAgentActivity(controller, showShortcut, open, threadPath, progress)
    }
}

@Stable
internal class ConversationAgentActivity(
    private val controller: ConversationController,
    private val showShortcut: Boolean,
    private val open: MutableState<Boolean>,
    private val threadPath: MutableState<List<String>>,
    val requestProgress: AgentRequestProgress,
) {
    val headerSlots: Int get() = if (showShortcut) 1 else 0

    /** Chat details is a stable entry point even before the viewport finds agent items. */
    fun show() {
        threadPath.value = emptyList()
        open.value = true
    }

    fun showThread(taskId: String) {
        val path = threadPath.value
        val existingIndex = path.indexOf(taskId)
        threadPath.value = if (existingIndex < 0) path + taskId else path.take(existingIndex + 1)
        open.value = true
    }

    @Composable
    fun ActivityButton(onOpen: () -> Unit) {
        if (showShortcut) {
            AgentActivityButton {
                onOpen()
                show()
            }
        }
    }

    /** Keeps the sheet in the current chat's composition and action scope. */
    @Composable
    fun ActivitySheet(
        appState: WhiteNoiseAppState,
        readOnly: Boolean,
    ) {
        if (open.value && controller.ownsRetainedForegroundPresentation) {
            val taskId = threadPath.value.lastOrNull()
            AgentActivitySheet(
                onClose = {
                    open.value = false
                    threadPath.value = emptyList()
                },
                inThread = taskId != null,
            ) {
                if (taskId != null) {
                    TextButton(onClick = { threadPath.value = threadPath.value.dropLast(1) }) {
                        val label = if (threadPath.value.size == 1) R.string.agent_thread_back else R.string.back
                        Text(stringResource(label))
                    }
                }
                AgentActivityHistory(appState, controller, readOnly || controller.group.pendingConfirmation, taskId)
            }
        }
    }
}

/** One stable entry point, independent of the number or state of incoming agent steps. */
@Composable
internal fun AgentActivityButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_history),
            contentDescription = stringResource(R.string.agent_activity),
        )
    }
}

/** Provides an entry point from Chat details even when no agent rows are loaded. */
internal fun SettingsGroupScope.agentActivityDetailsRow(onOpen: () -> Unit) {
    row("agent_activity") { context ->
        SettingsLink(
            context = context,
            title = stringResource(R.string.agent_activity),
            onClick = onOpen,
            leading = { SettingsLeadingIcon(R.drawable.ic_history) },
        )
    }
}

/** Presents the activity log without changing the chat's scroll position. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentActivitySheet(
    onClose: () -> Unit,
    inThread: Boolean = false,
    content: @Composable () -> Unit,
) {
    WhiteNoiseModalBottomSheet(onDismissRequest = onClose, containerColor = amoledSheetContainerColor()) {
        val title = stringResource(if (inThread) R.string.agent_thread_title else R.string.agent_activity)
        WhiteNoiseSheetHeader(title, onClose = onClose)
        content()
    }
}

/** Opens at the latest loaded turn, with older retained events above it. */
@Composable
internal fun AgentActivityList(
    rows: List<TimelineMessage>,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    readOnly: Boolean,
    activeSessions: Map<String, String> = emptyMap(),
) {
    val account = controller.conversationAccountRef
    val latestTurn = rows.indexOfLast { it.projected?.agentItem?.itemType == "turn" }
    val answeredBy = rememberAgentAnswerAttribution(controller, rows)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = latestTurn.coerceAtLeast(0))
    WhiteNoiseLazyColumn(
        state = state,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * SHEET_LIST_HEIGHT_SHARE),
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related),
    ) {
        itemsIndexed(rows, key = { _, row -> row.presentationId }) { index, row ->
            val item = remember(row) { AgentItemReader.read(row.record, row.projected) }
            if (item == null) {
                row.projected?.let { AgentThreadMessage(it, appState.displayNameForAccount(account, it.sender)) }
            } else {
                val previous = rows.getOrNull(index - 1)
                val showSenderName =
                    item.body is AgentItemBody.Turn ||
                        !item.agentPubkeyHex.equals(previous?.record?.sender, ignoreCase = true)
                AgentItemTimelineRow(
                    agentItem = item,
                    controller = controller,
                    appState = appState,
                    context =
                        AgentItemRowContext(
                            showSenderName = showSenderName,
                            readOnly = readOnly,
                            sessionControls = row.id == activeSessions[row.record.sender],
                            threadTaskId = item.rowId.takeIf { (item.body as? AgentItemBody.Task)?.kind == "subagent" },
                            answeredBy = answeredBy[row.id]?.let { appState.displayNameForAccount(account, it) },
                        ),
                )
            }
        }
    }
}

private const val SHEET_LIST_HEIGHT_SHARE = 0.7f
