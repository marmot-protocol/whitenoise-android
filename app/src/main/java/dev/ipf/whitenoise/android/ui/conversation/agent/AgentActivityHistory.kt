@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.ConversationOpenModeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.agent.isAgentThreadRow
import dev.ipf.whitenoise.android.state.CONVERSATION_WINDOW_MAX_ROWS
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineSubscriptionHandle
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.TimelinePageOutcome
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isRetentionExpiredForSearch
import dev.ipf.whitenoise.android.state.openTimelineWithFallback
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sheet owns a live history window separate from the chat's viewport and paging. */
@Composable
internal fun AgentActivityHistory(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    readOnly: Boolean,
    threadTaskId: String? = null,
) {
    val account = controller.conversationAccountRef ?: return
    var attempt by remember(controller) { mutableIntStateOf(0) }
    val history = remember(controller, attempt) { ActivityHistoryWindow() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(history) {
        history.collect { openLatestAgentActivity(appState, controller, account) }
    }

    val page = history.page
    ObserveAgentSettlements(controller, LocalAgentRequestProgress.current, page?.messages.orEmpty())
    val threadParents = rememberAgentThreadParents(controller, page, threadTaskId)
    val deleted = controller.deletedMessageIds
    val rows =
        remember(page, deleted, threadTaskId, threadParents) {
            page
                ?.let { activityHistoryRows(it, deleted, threadTaskId = threadTaskId, threadParents = threadParents) }
                .orEmpty()
        }
    if (rows.isNotEmpty()) {
        val sessions =
            if (threadTaskId == null) activeAgentSessions(history, appState, controller, account) else emptyMap()
        key(threadTaskId) {
            AgentActivityList(
                rows,
                appState,
                controller,
                readOnly,
                activeSessions = sessions,
            )
        }
    }
    when {
        history.disconnected ->
            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.agent_activity_retry)) }
        page == null ->
            AgentActivityStatus(R.string.agent_activity_loading)
        rows.isEmpty() ->
            AgentActivityStatus(R.string.agent_activity_empty)
    }
    if (!history.disconnected && page != null) {
        ActivityPagingControls(page, history.paging) { older ->
            scope.launch {
                if (!history.move(older)) {
                    controller.presentConversationTransient(
                        R.string.agent_activity_page_failed,
                        TransientNoticeKind.Information,
                    )
                }
            }
        }
    }
}

/** A historical viewport does not receive new sessions announced at the live end of the chat. */
@Composable
private fun activeAgentSessions(
    history: ActivityHistoryWindow,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    account: String,
): Map<String, String> {
    val browsingOlder = history.page?.hasMoreAfter == true
    val latest = remember(history, browsingOlder) { ActivityHistoryWindow() }
    LaunchedEffect(latest) {
        if (browsingOlder) latest.collect { openLatestAgentActivity(appState, controller, account) }
    }
    if (browsingOlder && latest.page == null) return emptyMap()
    val sessions = latestAgentSessions(history.latestSessions, latest.latestSessions.values.toList())
    return sessions.mapValues { it.value.messageIdHex }
}

private suspend fun openLatestAgentActivity(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    account: String,
): ConversationTimelineSubscriptionHandle =
    appState.marmotIo {
        openTimelineWithFallback(
            account,
            controller.group.groupIdHex,
            CONVERSATION_WINDOW_MAX_ROWS,
            mode = ConversationOpenModeFfi.LATEST,
        )
    }

@Composable
private fun AgentActivityStatus(
    @StringRes label: Int,
) {
    Text(stringResource(label), Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.Section))
}

@Composable
private fun ActivityPagingControls(
    page: TimelinePageFfi,
    paging: Boolean,
    onPage: (older: Boolean) -> Unit,
) {
    if (!page.hasMoreBefore && !page.hasMoreAfter) return
    Row(Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.Related)) {
        if (page.hasMoreBefore) {
            TextButton(onClick = { onPage(true) }, enabled = !paging) {
                Text(stringResource(R.string.agent_activity_older))
            }
        }
        if (page.hasMoreAfter) {
            TextButton(onClick = { onPage(false) }, enabled = !paging) {
                Text(stringResource(R.string.agent_activity_newer))
            }
        }
    }
}

internal class ActivityHistoryWindow {
    private var subscription: ConversationTimelineSubscriptionHandle? = null
    var page by mutableStateOf<TimelinePageFfi?>(null)
        private set
    var paging by mutableStateOf(false)
        private set
    var disconnected by mutableStateOf(false)
        private set
    var latestSessions by mutableStateOf(emptyMap<String, TimelineMessageRecordFfi>())
        private set

    suspend fun collect(open: suspend () -> ConversationTimelineSubscriptionHandle) {
        runCatchingCancellable {
            // Keep ownership of the native handle if the sheet closes during open.
            val active = withContext(NonCancellable) { open() }
            subscription = active
            try {
                currentCoroutineContext().ensureActive()
                active.snapshot()?.let { install(active, it) }
                while (true) {
                    val replacement = active.nextWindow() ?: break
                    install(active, replacement)
                }
            } finally {
                subscription = null
                page = null
                release(active)
            }
        }
        disconnected = true
    }

    private suspend fun release(active: ConversationTimelineSubscriptionHandle) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                active.cancel()
            } finally {
                active.close()
            }
        }
    }

    suspend fun move(older: Boolean): Boolean {
        val active = subscription ?: return false
        return paging || page(active, older)
    }

    private suspend fun page(
        active: ConversationTimelineSubscriptionHandle,
        older: Boolean,
    ): Boolean {
        paging = true
        try {
            val result =
                runCatchingCancellable {
                    if (older) {
                        active.paginateBackwards(CONVERSATION_WINDOW_MAX_ROWS / 2u)
                    } else {
                        active.paginateForwards(CONVERSATION_WINDOW_MAX_ROWS / 2u)
                    }
                }.getOrNull()
            return when (result) {
                is TimelinePageOutcome.Advanced -> {
                    install(active, result.page)
                    true
                }
                else -> false
            }
        } finally {
            paging = false
        }
    }

    private fun install(
        active: ConversationTimelineSubscriptionHandle,
        replacement: TimelinePageFfi,
    ) {
        if (subscription !== active) return
        val installed = active.latestInstalledWindow()?.page ?: replacement
        latestSessions = latestAgentSessions(latestSessions, installed.messages)
        page = installed
    }
}

/** Paging starts at the newest rows. The active session's first event may be on an older page. */
internal fun latestAgentSessions(
    known: Map<String, TimelineMessageRecordFfi>,
    rows: List<TimelineMessageRecordFfi>,
): Map<String, TimelineMessageRecordFfi> {
    val latest = known.toMutableMap()
    rows
        .filter { it.agentItem?.itemType == "session" }
        .forEach { row ->
            val previous = latest[row.sender]
            if (previous == null || SESSION_ORDER.compare(row, previous) >= 0) latest[row.sender] = row
        }
    return latest
}

private val SESSION_ORDER = compareBy<TimelineMessageRecordFfi>({ it.timelineAt }, { it.messageIdHex })

internal fun activityHistoryRows(
    page: TimelinePageFfi,
    deletedMessageIds: Set<String>,
    nowMillis: Long = System.currentTimeMillis(),
    threadTaskId: String? = null,
    threadParents: Map<String, TimelineMessageRecordFfi> = emptyMap(),
): List<TimelineMessage> {
    val recordsById =
        if (threadTaskId == null) emptyMap() else threadParents + page.messages.associateBy { it.messageIdHex }
    return page.messages.mapNotNull { record ->
        val deleted = record.deleted || record.messageIdHex in deletedMessageIds
        val belongs =
            if (threadTaskId == null) {
                record.agentItem != null && !record.isAgentThreadRow()
            } else {
                record.isAgentThreadRow() && record.agentThreadTask(recordsById) == threadTaskId
            }
        if (!belongs ||
            deleted ||
            record.invalidationStatus != null ||
            isRetentionExpiredForSearch(record, nowMillis)
        ) {
            null
        } else {
            TimelineMessage(
                id = record.messageIdHex,
                record = TimelineProjector.toAppMessageRecord(record),
                status = MessageStatus.Received,
                projected = record,
            )
        }
    }
}
