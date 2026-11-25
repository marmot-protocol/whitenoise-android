@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
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
    val progress = rememberAgentRequestProgress(controller)
    return remember(controller, showShortcut) { ConversationAgentActivity(controller, showShortcut, open, progress) }
}

@Stable
internal class ConversationAgentActivity(
    private val controller: ConversationController,
    private val showShortcut: Boolean,
    private val open: MutableState<Boolean>,
    val requestProgress: MutableMap<String, AgentRequestStatus>,
) {
    val headerSlots: Int get() = if (showShortcut) 1 else 0

    /** Chat details is a stable entry point even before the viewport finds agent items. */
    fun show() {
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
            AgentActivitySheet(onClose = { open.value = false }) {
                AgentActivityHistory(appState, controller, readOnly || controller.group.pendingConfirmation)
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
    content: @Composable () -> Unit,
) {
    WhiteNoiseModalBottomSheet(onDismissRequest = onClose, containerColor = amoledSheetContainerColor()) {
        WhiteNoiseSheetHeader(stringResource(R.string.agent_activity), onClose = onClose)
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
    modifier: Modifier = Modifier,
) {
    val latestTurn = rows.indexOfLast { it.projected?.agentItem?.itemType == "turn" }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = latestTurn.coerceAtLeast(0))
    LazyColumn(
        state = state,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * SheetListHeightShare),
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related),
    ) {
        itemsIndexed(rows, key = { _, row -> row.presentationId }) { index, row ->
            val item = remember(row) { AgentItemReader.read(row.record, row.projected) }
            if (item != null) {
                val previous = rows.getOrNull(index - 1)
                val showSenderName =
                    item.body is AgentItemBody.Turn ||
                        !item.agentPubkeyHex.equals(previous?.record?.sender, ignoreCase = true)
                AgentItemTimelineRow(
                    agentItem = item,
                    controller = controller,
                    appState = appState,
                    context = AgentItemRowContext(showSenderName = showSenderName, readOnly = readOnly),
                )
            }
        }
    }
}

private const val SheetListHeightShare = 0.7f
