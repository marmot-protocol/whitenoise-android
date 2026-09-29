package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.castPollVote
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.common.Avatar
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val POLL_ROW_MAX_WIDTH_FRACTION = 0.95f
private const val POLL_MILLIS_PER_SECOND = 1_000L

/** Applies an in-flight replacement vote to MDK's last projection for immediate feedback. */
internal fun optimisticPollProjection(
    poll: PollProjectionFfi,
    selection: List<String>,
): PollProjectionFfi {
    val previous = poll.localSelection.toSet()
    val next = selection.toSet()
    if (previous == next) return poll
    return poll.copy(
        options =
            poll.options.map { option ->
                option.copy(
                    votes =
                        when {
                            option.id in previous && option.id !in next ->
                                if (option.votes > 0uL) option.votes - 1uL else 0uL
                            option.id !in previous && option.id in next -> option.votes + 1uL
                            else -> option.votes
                        },
                )
            },
        participants = if (previous.isEmpty() && next.isNotEmpty()) poll.participants + 1uL else poll.participants,
        localSelection = selection,
    )
}

/** Fraction of participants who selected an option, bounded for stale projections. */
internal fun pollResultFraction(
    votes: ULong,
    participants: ULong,
): Float = if (participants == 0uL) 0f else (votes.toDouble() / participants.toDouble()).toFloat().coerceIn(0f, 1f)

/** Checks expiry before the first frame and again when a vote is tapped. */
internal fun pollDeadlineReached(
    endsAt: ULong?,
    nowMillis: Long,
): Boolean = endsAt != null && endsAt <= (nowMillis / POLL_MILLIS_PER_SECOND).toULong()

/** Rejects a tap against an expired projection even before Compose's deadline timer fires. */
internal fun pollVoteAllowed(
    poll: PollProjectionFfi,
    nowMillis: Long,
): Boolean = poll.open && !pollDeadlineReached(poll.endsAt, nowMillis)

/** Computes the complete replacement vote from native option ids, never an empty selection. */
internal fun replacementPollSelection(
    poll: PollProjectionFfi,
    tappedId: String,
): List<String>? {
    val selection = poll.localSelection.filter { id -> poll.options.any { it.id == id } }
    return if (poll.options.none { it.id == tappedId }) {
        null
    } else if (poll.pollType == PollTypeFfi.SINGLE_CHOICE) {
        listOf(tappedId).takeUnless { it == selection }
    } else {
        val next = if (tappedId in selection) selection - tappedId else selection + tappedId
        next.takeIf { it.isNotEmpty() && it != selection }
    }
}

/** Shows MDK's poll projection and submits replacement selections through its native vote API. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
internal fun PollTimelineRow(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    selectionMode: Boolean,
) {
    val poll = item.projected?.poll
    if (poll == null) {
        Text(stringResource(R.string.poll_preview), Modifier.padding(16.dp))
        return
    }
    var voting by remember(item.record.messageIdHex) { mutableStateOf(false) }
    var pendingSelection by remember(item.record.messageIdHex) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(poll.localSelection, poll.open) {
        if (!poll.open || pendingSelection?.toSet() == poll.localSelection.toSet()) pendingSelection = null
    }
    val displayedPoll = pendingSelection?.let { optimisticPollProjection(poll, it) } ?: poll
    var deadlineReached by remember(poll.endsAt) {
        mutableStateOf(pollDeadlineReached(poll.endsAt, System.currentTimeMillis()))
    }
    LaunchedEffect(poll.endsAt) {
        val deadline = poll.endsAt?.toLong()?.times(1000L) ?: return@LaunchedEffect
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0L) delay(remaining)
        deadlineReached = true
    }
    val open = poll.open && !deadlineReached
    val mine = controller.isMessageMine(item.record)
    val showSender = GroupProjector.shouldShowTranscriptSenderAvatar(controller.usesDirectTranscriptChrome, mine)
    Box(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth(POLL_ROW_MAX_WIDTH_FRACTION), verticalAlignment = Alignment.Bottom) {
            if (showSender) {
                PollSenderAvatar(item.record.sender, appState)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                if (showSender) {
                    Text(
                        appState.displayName(item.record.sender),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PollCard(
                    poll = displayedPoll,
                    canVote = open && controller.canSendMessages && !selectionMode && !voting,
                    onVote = { optionId ->
                        val replacement = replacementPollSelection(displayedPoll, optionId)
                        if (replacement != null && pollVoteAllowed(poll, System.currentTimeMillis())) {
                            voting = true
                            pendingSelection = replacement
                            appState.launchMutation {
                                var accepted = false
                                try {
                                    accepted = controller.castPollVote(item.record.messageIdHex, replacement)
                                } finally {
                                    if (!accepted) pendingSelection = null
                                    voting = false
                                }
                            }
                        }
                    },
                    open = open,
                )
            }
        }
    }
}

/** Uses the same tappable group sender avatar as nearby transcript rows. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun PollSenderAvatar(
    sender: String,
    appState: WhiteNoiseAppState,
) {
    Box(
        Modifier.clip(CircleShape).clickable { appState.presentProfile(appState.npub(sender)) },
    ) {
        Avatar(
            title = appState.displayName(sender),
            seed = sender,
            size = 32.dp,
            pictureUrl = appState.avatarUrl(sender),
        )
    }
}

/** Renders the projected question, live tally, local selection, and closed state. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PollCard(
    poll: PollProjectionFfi,
    canVote: Boolean,
    onVote: (String) -> Unit,
    open: Boolean = poll.open,
) {
    val locale = LocalConfiguration.current.locales[0]
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
            if (!open) Text(stringResource(R.string.poll_closed), style = MaterialTheme.typography.labelMedium)
        }
    }
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
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .clickable(enabled = canVote, role = Role.Button) { onVote(option.id) }
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
