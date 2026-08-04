package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.PillShape

private const val SUMMARY_AVATAR_OVERLAP_DP = 20
private const val SUMMARY_AVATAR_COUNT = 3

@Composable
@Suppress("FunctionNaming")
internal fun SelectedMemberSummary(
    members: List<RecipientSearch.Candidate>,
    appState: WhiteNoiseAppState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = PillShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            Box(modifier = Modifier.width(72.dp)) {
                members.take(SUMMARY_AVATAR_COUNT).forEachIndexed { index, member ->
                    Box(modifier = Modifier.padding(start = (index * SUMMARY_AVATAR_OVERLAP_DP).dp)) {
                        Avatar(
                            title = appState.displayName(member.accountIdHex),
                            seed = member.accountIdHex,
                            size = 32.dp,
                            pictureUrl = appState.avatarUrl(member.accountIdHex),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.selected), style = MaterialTheme.typography.labelLarge)
                Text(
                    members.joinToString { member -> appState.displayName(member.accountIdHex) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.Group, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun SelectedMembersReviewScreen(
    members: List<RecipientSearch.Candidate>,
    appState: WhiteNoiseAppState,
    busy: Boolean,
    onBack: () -> Unit,
    onRemove: (RecipientSearch.Candidate) -> Unit,
    onConfirm: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.selected))
                        Text(
                            contactPickerTopBarTitle(
                                pickerTitle = "",
                                selectedCount = members.size,
                                oneMember = stringResource(R.string.one_member),
                                membersFormat = stringResource(R.string.members_count),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!busy) onConfirm() },
                modifier =
                    Modifier
                        .testTag(SELECTED_MEMBERS_CONFIRM_TAG)
                        .then(if (busy) Modifier.semantics { disabled() } else Modifier),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.next))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(members, key = { it.accountIdHex }) { member ->
                ContactRow(
                    title = appState.displayName(member.accountIdHex),
                    subtitle = IdentityFormatter.short(member.npub),
                    avatarSeed = member.accountIdHex,
                    avatarUrl = appState.avatarUrl(member.accountIdHex),
                    trailing = {
                        IconButton(
                            onClick = { onRemove(member) },
                            enabled = !busy,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove_member),
                            )
                        }
                    },
                )
            }
        }
    }
}

internal const val SELECTED_MEMBERS_CONFIRM_TAG = "selected_members_confirm"
