package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

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
    PollVotesSheet(open.poll, open.owner, controller, appState, onDismissRequest = host::dismiss)
}
