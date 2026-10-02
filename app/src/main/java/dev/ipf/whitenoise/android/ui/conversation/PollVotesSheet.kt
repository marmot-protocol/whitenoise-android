package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PollVoteRow
import dev.ipf.whitenoise.android.state.PollVotesPager
import dev.ipf.whitenoise.android.state.PollVotesPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.pollVoteRows
import dev.ipf.whitenoise.android.state.pollVotesPage
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingBottomSheet
import kotlinx.coroutines.launch

internal const val POLL_VOTES_SHEET_TAG = "poll-votes-sheet"
internal const val POLL_VOTES_LOAD_MORE_TAG = "poll-votes-load-more"
internal const val POLL_VOTES_RETRY_TAG = "poll-votes-retry"
private val VoterAvatarSize = 40.dp

/**
 * Per-voter results for one poll in a bottom sheet. The pager is recreated whenever the account, chat, poll
 * or its projection changes, so paging restarts from the first page and late results are rejected.
 */
@Composable
@Suppress("FunctionNaming")
internal fun PollVotesSheet(
    poll: PollProjectionFfi,
    owner: PollMessageActionOwner,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onDismissRequest: () -> Unit,
) {
    val pager =
        remember(owner, poll, controller) {
            PollVotesPager(
                reader = { afterVotedAt, afterVoter, limit ->
                    controller.pollVotesPage(owner.messageId, afterVotedAt, afterVoter, limit)
                },
                isCurrent = { controller.acceptsConversationActionOwner(owner.accountRef, owner.groupId) },
            )
        }
    LaunchedEffect(pager) { pager.refresh() }
    val scope = rememberCoroutineScope()
    val blockedUsers = appState.runtimeMirrors.blocks
    val rows =
        pollVoteRows(
            votes = pager.votes,
            options = poll.options,
            displayName = appState::displayName,
            avatarUrl = appState::avatarUrl,
            // Reading the mirror's observable list subscribes the sheet to live block changes.
            isBlocked = { id -> blockedUsers.users.isNotEmpty() && blockedUsers.isBlocked(id) },
        )
    KeyboardPreservingBottomSheet(
        paneTitle = stringResource(R.string.poll_votes_title),
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag(POLL_VOTES_SHEET_TAG),
    ) {
        PollVotesContent(
            rows = rows,
            phase = pager.phase,
            hasMore = pager.hasMore,
            onRetry = {
                scope.launch { if (pager.phase == PollVotesPhase.FAILED) pager.refresh() else pager.loadMore() }
            },
            onLoadMore = { scope.launch { pager.loadMore() } },
        )
    }
}

/** Stateless sheet body, so every paging state renders without a live engine. */
@Composable
@Suppress("FunctionNaming")
internal fun PollVotesContent(
    rows: List<PollVoteRow>,
    phase: PollVotesPhase,
    hasMore: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.poll_votes_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.poll_votes_not_anonymous),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        when {
            phase == PollVotesPhase.LOADING -> CenteredProgress()
            phase == PollVotesPhase.FAILED -> PollVotesFailure(onRetry)
            rows.isEmpty() -> {
                Text(
                    stringResource(R.string.poll_votes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            else ->
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(rows, key = { it.voterAccountIdHex }) { row -> PollVoterItem(row) }
                    item { PollVotesFooter(phase, hasMore, onRetry, onLoadMore) }
                }
        }
    }
}

/** Shows the paging tail: progress, a failed-page retry, or an explicit load-more action. */
@Composable
@Suppress("FunctionNaming")
private fun PollVotesFooter(
    phase: PollVotesPhase,
    hasMore: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        phase == PollVotesPhase.LOADING_MORE -> CenteredProgress()
        phase == PollVotesPhase.MORE_FAILED -> PollVotesFailure(onRetry)
        hasMore ->
            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().testTag(POLL_VOTES_LOAD_MORE_TAG)) {
                Text(stringResource(R.string.poll_votes_load_more))
            }
    }
}

/** An inline error with Retry, so a failed page never looks like an empty poll. */
@Composable
@Suppress("FunctionNaming")
private fun PollVotesFailure(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.poll_votes_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(POLL_VOTES_RETRY_TAG)) {
            Text(stringResource(R.string.retry))
        }
    }
}

/** A busy indicator that keeps the sheet height stable while a page is read. */
@Composable
@Suppress("FunctionNaming")
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** One voter with their chosen option labels, marked when the account blocks them. */
@Composable
@Suppress("FunctionNaming")
private fun PollVoterItem(row: PollVoteRow) {
    ListItem(
        headlineContent = {
            Text(
                row.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
        },
        supportingContent = {
            Text(
                row.choices.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            )
        },
        leadingContent = {
            Avatar(
                title = row.displayName,
                seed = row.voterAccountIdHex,
                size = VoterAvatarSize,
                pictureUrl = row.avatarUrl,
            )
        },
        trailingContent =
            if (row.blocked) {
                { Text(stringResource(R.string.poll_voter_blocked), style = MaterialTheme.typography.labelSmall) }
            } else {
                null
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("poll-voter-${row.voterAccountIdHex}"),
    )
}
