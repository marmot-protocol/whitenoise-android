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
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.common.Avatar
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val POLL_ROW_MAX_WIDTH_FRACTION = 0.95f

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
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PollTimelineRow(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    selectionMode: Boolean,
) {
    val poll = item.projected?.poll
    if (poll == null) {
        Text(stringResource(R.string.poll_unsupported), Modifier.padding(16.dp))
        return
    }
    var voting by remember(item.record.messageIdHex) { mutableStateOf(false) }
    var deadlineReached by remember(poll.endsAt) { mutableStateOf(false) }
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
                    poll = poll,
                    canVote = open && controller.canSendMessages && !selectionMode && !voting,
                    onVote = { optionId ->
                        val replacement = replacementPollSelection(poll, optionId)
                        if (replacement != null) {
                            voting = true
                            appState.launchMutation {
                                try {
                                    controller.castPollVote(item.record.messageIdHex, replacement)
                                } finally {
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
                PollOptionRow(option, selected = option.id in poll.localSelection, canVote = canVote, onVote = onVote)
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
    canVote: Boolean,
    onVote: (String) -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(shape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .clickable(enabled = canVote, role = Role.Button) { onVote(option.id) }
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
}
