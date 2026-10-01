package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val POLL_MILLIS_PER_SECOND = 1_000L

/** Poll content inside the ordinary message action/gesture surface; voting has its own deadline. */
@Composable
@Suppress("FunctionNaming")
internal fun PollMessageContent(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    canVote: Boolean,
    modifier: Modifier = Modifier,
) {
    val poll = item.projected?.poll ?: return
    val owner =
        remember(controller, item.record.messageIdHex) {
            PollMessageActionOwner(controller.boundAccountRef, controller.group.groupIdHex, item.record.messageIdHex)
        }
    var deadlineReached by remember(owner, poll.endsAt) {
        mutableStateOf(pollDeadlineReached(poll.endsAt, System.currentTimeMillis()))
    }
    LaunchedEffect(owner, poll.endsAt) {
        val deadline =
            poll.endsAt
                ?.toLong()
                ?.plus(1L)
                ?.times(POLL_MILLIS_PER_SECOND) ?: return@LaunchedEffect
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0L) delay(remaining)
        deadlineReached = true
    }
    Box(modifier.fillMaxWidth()) {
        key(owner) {
            PollVotingCard(
                poll = poll,
                voteKey = owner.accountRef to owner.messageId,
                canVote = canVote && !deadlineReached,
                open = poll.open && !deadlineReached,
                submitVote = { replacement, onCompleted ->
                    appState.launchMutation {
                        submitOwnedPollVote(controller, owner, replacement, onCompleted)
                    }
                },
            )
        }
    }
}

/** Keeps the pending and failed vote visible, including when a native call waits on publication. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PollVotingCard(
    poll: PollProjectionFfi,
    voteKey: Pair<String?, String>,
    canVote: Boolean,
    submitVote: (List<String>, (SendAcceptDispositionFfi?) -> Unit) -> Unit,
    open: Boolean = poll.open,
) {
    var voting by remember(voteKey) { mutableStateOf(false) }
    var pendingSelection by remember(voteKey) { mutableStateOf<List<String>?>(null) }
    var status by remember(voteKey) { mutableStateOf(PollVoteStatus.IDLE) }
    LaunchedEffect(voteKey, poll.localSelection, open) {
        if (!open || pendingSelection?.toSet() == poll.localSelection.toSet()) {
            pendingSelection = null
            status = PollVoteStatus.IDLE
        }
    }
    val displayedPoll = pendingSelection?.let { optimisticPollProjection(poll, it) } ?: poll
    val effectiveCanVote = canVote && open
    PollCard(
        poll = displayedPoll,
        canVote = effectiveCanVote && !voting,
        open = open,
        status = if (voting) PollVoteStatus.SUBMITTING else status,
        onVote = vote@{ optionId ->
            if (voting || !effectiveCanVote || !pollVoteAllowed(poll, System.currentTimeMillis())) return@vote
            val replacement = replacementPollSelection(displayedPoll, optionId) ?: return@vote
            voting = true
            status = PollVoteStatus.SUBMITTING
            pendingSelection = replacement
            submitVote(replacement) { outcome ->
                status = if (pendingSelection == null) PollVoteStatus.IDLE else outcome.voteStatus()
                if (outcome == null) pendingSelection = null
                voting = false
            }
        },
    )
}

internal enum class PollVoteStatus { IDLE, SUBMITTING, UNCONFIRMED, FAILED }

private fun SendAcceptDispositionFfi?.voteStatus(): PollVoteStatus =
    when (this) {
        SendAcceptDispositionFfi.PUBLISHED -> PollVoteStatus.IDLE
        SendAcceptDispositionFfi.ACCEPTED_PENDING -> PollVoteStatus.SUBMITTING
        SendAcceptDispositionFfi.COMPLETION_UNKNOWN -> PollVoteStatus.UNCONFIRMED
        null -> PollVoteStatus.FAILED
    }

/** Renders the projected question, live tally, local selection, and closed state. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PollCard(
    poll: PollProjectionFfi,
    canVote: Boolean,
    onVote: (String) -> Unit,
    open: Boolean = poll.open,
    status: PollVoteStatus = PollVoteStatus.IDLE,
) {
    val locale = LocalConfiguration.current.locales[0]
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                poll.question,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            )
            Text(
                stringResource(
                    if (poll.pollType == PollTypeFfi.SINGLE_CHOICE) {
                        R.string.poll_single_choice
                    } else {
                        R.string.poll_multiple_choice
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            poll.options.forEach { option ->
                PollOptionRow(
                    option,
                    selected = option.id in poll.localSelection,
                    fraction = pollResultFraction(option.votes, poll.participants),
                    canVote = canVote,
                    onVote = onVote,
                )
            }
            Text(
                pluralStringResource(R.plurals.poll_participants, poll.participants.toInt(), poll.participants.toInt()),
                style = MaterialTheme.typography.labelSmall,
            )
            val endsAt = poll.endsAt
            if (open && endsAt != null) {
                val deadline =
                    DateFormat
                        .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                        .format(Date(endsAt.toLong() * 1_000L))
                Text(stringResource(R.string.poll_ends_at, deadline), style = MaterialTheme.typography.labelSmall)
            }
            PollVoteStatusLabel(status)
            if (!open) Text(stringResource(R.string.poll_closed), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Shows only presentation feedback; MDK remains authoritative for the selection and tally. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun PollVoteStatusLabel(status: PollVoteStatus) {
    val label =
        when (status) {
            PollVoteStatus.IDLE -> return
            PollVoteStatus.SUBMITTING -> R.string.sending
            PollVoteStatus.UNCONFIRMED -> R.string.delivery_not_confirmed
            PollVoteStatus.FAILED -> R.string.poll_vote_failed
        }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color =
            if (status == PollVoteStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** Keeps vote counts readable in both live and closed polls while retaining a clear tap target. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun PollOptionRow(
    option: PollOptionResultFfi,
    selected: Boolean,
    fraction: Float,
    canVote: Boolean,
    onVote: (String) -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(shape)
                .border(
                    BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shape,
                ).clickable(enabled = canVote, role = Role.Button) { onVote(option.id) }
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (selected) "✓  " else "") + option.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            )
            Text(
                pluralStringResource(R.plurals.poll_option_votes, option.votes.toInt(), option.votes.toInt()),
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
            )
        }
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        ) {
            Surface(Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
            Surface(Modifier.fillMaxWidth(fraction).height(4.dp), color = MaterialTheme.colorScheme.primary) {}
        }
    }
}
