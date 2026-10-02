package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.RetainedRowWatch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay

/** The poll whose per-voter sheet is open, with the latest projection the card showed for it. */
internal data class OpenPollVotes(
    val owner: PollMessageActionOwner,
    val poll: PollProjectionFfi,
)

/**
 * Screen-level state for the one open View votes sheet. Poll rows live in a lazy list and are disposed when
 * they scroll away, so the conversation screen hosts the sheet and it survives that.
 */
internal class PollVotesHostState {
    /** The open sheet's poll, or null while closed. */
    var open: OpenPollVotes? by mutableStateOf(null)
        private set

    /** Opens the sheet for [owner], replacing any other open poll. */
    fun show(
        owner: PollMessageActionOwner,
        poll: PollProjectionFfi,
    ) {
        open = OpenPollVotes(owner, poll)
    }

    /** Keeps the open sheet's projection current when the same poll row is reprojected. */
    fun update(
        owner: PollMessageActionOwner,
        poll: PollProjectionFfi,
    ) {
        open?.takeIf { it.owner == owner && it.poll != poll }?.let { open = it.copy(poll = poll) }
    }

    /** Closes the sheet only when it shows the poll of [owner], so another poll's sheet is left alone. */
    fun dismissIf(owner: PollMessageActionOwner) {
        if (open?.owner == owner) open = null
    }

    /** Closes the sheet. */
    fun dismiss() {
        open = null
    }
}

/** Where poll rows ask the conversation screen to show their votes, null outside a conversation screen. */
internal val LocalPollVotesHost = compositionLocalOf<PollVotesHostState?> { null }

/** Renders the open poll's per-voter sheet at conversation level, independent of the poll row's lifetime. */
@Composable
@Suppress("FunctionNaming")
internal fun PollVotesHost(
    host: PollVotesHostState,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
) {
    val open = host.open ?: return
    // Expired disappearing polls leave the timeline without an engine event, so watch the deadline here.
    // Waits are capped like the controller sweep and re-read the wall clock, because this timer does not
    // count deep sleep. A row absent from the bounded window is not expiry until its remembered deadline has
    // passed. The read watermark is a key so a received poll's deferred deadline is re-evaluated once read.
    // The remembered deadline outlives effect restarts, so a restart after the row was trimmed still knows it.
    val remembered = remember(open.owner) { arrayOfNulls<Long>(1) }
    LaunchedEffect(open.owner, open.poll, controller.lastReadMessageId) {
        var deadlineMillis: Long? = remembered[0]
        while (true) {
            when (val watch = controller.watchRetainedRow(open.owner.messageId, deadlineMillis)) {
                RetainedRowWatch.Gone -> {
                    host.dismissIf(open.owner)
                    return@LaunchedEffect
                }
                RetainedRowWatch.Unwatched -> return@LaunchedEffect
                is RetainedRowWatch.Waiting -> {
                    deadlineMillis = watch.deadlineMillis
                    remembered[0] = deadlineMillis
                    delay(watch.delayMillis)
                }
            }
        }
    }
    PollVotesSheet(
        open.poll,
        open.owner,
        controller,
        appState,
        onDismissRequest = host::dismiss,
        onPollEnded = { host.dismissIf(open.owner) },
    )
}
