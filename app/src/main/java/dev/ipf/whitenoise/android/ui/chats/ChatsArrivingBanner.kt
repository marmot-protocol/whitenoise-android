@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatHistoryTransfer
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatHistoryTransfer
import dev.ipf.whitenoise.android.state.isJoiningChats
import dev.ipf.whitenoise.android.state.monotonicNowMs
import dev.ipf.whitenoise.android.ui.conversation.rememberChatHistoryImports
import dev.ipf.whitenoise.android.ui.devices.linkChatProgressText
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.delay

internal const val CHATS_ARRIVING_TAG = "chats.arriving"

/** How long after this device is linked the list says chats are on their way while MDK has no count yet. */
internal const val CHAT_COUNT_WAIT_MS = 3 * 60 * 1000L

/** What the chat list says while this device is still receiving the account's chats or their history. */
internal sealed interface ChatsArriving {
    /** This device was just linked and MDK has no count of its chats yet. */
    data object Linked : ChatsArriving

    /** MDK's count of the chats this newly linked device still expects to join. */
    data class Joining(
        val progress: LinkChatProgressFfi,
    ) : ChatsArriving

    /** Earlier messages of at least one chat are still arriving from another device, or MDK will try again. */
    data class History(
        val transfer: ChatHistoryTransfer,
    ) : ChatsArriving
}

/**
 * The notice for MDK's link [progress] on this device, a link still [awaiting] its first count, and its per-chat
 * history [imports], or null once all are done. Chats come before their history; a retry shows only when no chat
 * is still receiving.
 */
internal fun chatsArriving(
    progress: LinkChatProgressFfi?,
    imports: Collection<ChatHistoryImportStateFfi>,
    awaiting: Boolean = false,
): ChatsArriving? {
    val transfers = imports.mapNotNull(::chatHistoryTransfer)
    return when {
        progress != null -> ChatsArriving.Joining(progress)
        awaiting -> ChatsArriving.Linked
        ChatHistoryTransfer.Receiving in transfers -> ChatsArriving.History(ChatHistoryTransfer.Receiving)
        ChatHistoryTransfer.Retrying in transfers -> ChatsArriving.History(ChatHistoryTransfer.Retrying)
        else -> null
    }
}

/**
 * Keeps a newly linked device's chat list honest while chats or their history are still on their way, which the
 * rows alone cannot show. The empty list already says chats are arriving, so [hasChats] holds that back there.
 */
@Composable
internal fun ChatsArrivingNotice(
    appState: WhiteNoiseAppState,
    hasChats: Boolean,
) {
    val account = appState.activeAccountRef ?: return
    val progress =
        appState.deviceLinkRequests.snapshots[account]
            ?.chatProgress
            ?.firstOrNull { it.onNewDevice && it.isJoiningChats() }
            ?.takeIf { hasChats }
    val awaiting = rememberAwaitingChatCount(appState.deviceLinkRequests.linkedAwaitingCount[account]) && hasChats
    val imports = rememberChatHistoryImports(appState, account)
    chatsArriving(progress, imports.byGroup.values, awaiting)?.let { ChatsArrivingBanner(it) }
}

/**
 * Whether a link seen becoming Linked at [linkedAtMs] still awaits MDK's first chat count, which holds for
 * [CHAT_COUNT_WAIT_MS] at most: an approving device that never sends one must not leave the notice up for good.
 */
@Composable
internal fun rememberAwaitingChatCount(linkedAtMs: Long?): Boolean {
    var awaiting by remember(linkedAtMs) {
        mutableStateOf(linkedAtMs != null && monotonicNowMs() - linkedAtMs < CHAT_COUNT_WAIT_MS)
    }
    LaunchedEffect(linkedAtMs) {
        val left = (linkedAtMs ?: return@LaunchedEffect) + CHAT_COUNT_WAIT_MS - monotonicNowMs()
        if (left > 0) delay(left)
        awaiting = false
    }
    return awaiting
}

/** One line on what is still arriving; joining also shows MDK's count as a bar. Screen readers hear each change. */
@Composable
internal fun ChatsArrivingBanner(
    arriving: ChatsArriving,
    modifier: Modifier = Modifier,
) {
    val text =
        when (arriving) {
            ChatsArriving.Linked -> stringResource(R.string.chats_arriving_detail)
            is ChatsArriving.Joining -> linkChatProgressText(arriving.progress)
            is ChatsArriving.History ->
                stringResource(
                    if (arriving.transfer == ChatHistoryTransfer.Retrying) {
                        R.string.history_retrying
                    } else {
                        R.string.history_receiving
                    },
                )
        }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(CHATS_ARRIVING_TAG),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = amoledOutlineBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (arriving is ChatsArriving.Joining) {
                val progress = arriving.progress
                val left = minOf(progress.remaining, progress.total)
                val done = (progress.total - left).toFloat() / progress.total.toFloat()
                LinearProgressIndicator(progress = { done }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
