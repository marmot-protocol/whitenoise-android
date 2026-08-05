package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.Radii

private const val SUMMARY_AVATAR_OVERLAP_DP = 20
private const val SUMMARY_AVATAR_COUNT = 3

internal fun selectedMemberDisplayName(member: RecipientSearch.Candidate): String = member.displayName

internal fun selectedMemberAvatarUrl(
    member: RecipientSearch.Candidate,
    localAvatarUrl: String?,
): String? = localAvatarUrl ?: ProfileSanitizer.imageUrl(member.searchProfile?.picture)

@Composable
@Suppress("FunctionNaming")
internal fun SelectedMemberSummary(
    members: List<RecipientSearch.Candidate>,
    appState: WhiteNoiseAppState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reviewSelectionLabel = stringResource(R.string.review_selected_members)
    val displayedAvatarCount = members.size.coerceAtMost(SUMMARY_AVATAR_COUNT)
    val avatarStackWidth =
        (32 + (displayedAvatarCount - 1).coerceAtLeast(0) * SUMMARY_AVATAR_OVERLAP_DP).dp
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radii.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs)
                .semantics { contentDescription = reviewSelectionLabel },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            Box(modifier = Modifier.width(avatarStackWidth)) {
                members.take(SUMMARY_AVATAR_COUNT).forEachIndexed { index, member ->
                    Box(modifier = Modifier.padding(start = (index * SUMMARY_AVATAR_OVERLAP_DP).dp)) {
                        Avatar(
                            title = selectedMemberDisplayName(member),
                            seed = member.accountIdHex,
                            size = 32.dp,
                            pictureUrl = selectedMemberAvatarUrl(member, appState.avatarUrl(member.accountIdHex)),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.selected_members_count, members.size, members.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    members.joinToString { member -> selectedMemberDisplayName(member) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    confirmIcon: ImageVector,
    confirmLabel: String,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.review_selection))
                        Text(
                            pluralStringResource(R.plurals.selected_members_count, members.size, members.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) onConfirm() },
                modifier =
                    Modifier
                        .testTag(SELECTED_MEMBERS_CONFIRM_TAG)
                        .then(if (busy) Modifier.semantics { disabled() } else Modifier),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(confirmIcon, contentDescription = null)
                    }
                    Spacer(Modifier.width(Dimens.spaceSm))
                    Text(confirmLabel)
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
                    title = selectedMemberDisplayName(member),
                    subtitle = IdentityFormatter.short(member.npub),
                    avatarSeed = member.accountIdHex,
                    avatarUrl = selectedMemberAvatarUrl(member, appState.avatarUrl(member.accountIdHex)),
                    trailing = {
                        FilledTonalIconButton(
                            onClick = { onRemove(member) },
                            enabled = !busy,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription =
                                    stringResource(
                                        R.string.remove_member_named,
                                        selectedMemberDisplayName(member),
                                    ),
                            )
                        }
                    },
                )
            }
        }
    }
}

internal const val SELECTED_MEMBERS_CONFIRM_TAG = "selected_members_confirm"
