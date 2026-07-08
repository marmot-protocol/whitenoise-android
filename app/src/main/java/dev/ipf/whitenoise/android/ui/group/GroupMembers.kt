package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.flow.filter

internal enum class GroupMemberMenuAction {
    GrantAdmin,
    RevokeAdmin,
    RemoveMember,
    StepDownAsAdmin,
}

internal fun groupMemberMenuActions(
    viewerIsMember: Boolean,
    viewerIsAdmin: Boolean,
    targetIsSelf: Boolean,
    targetIsAdmin: Boolean,
): List<GroupMemberMenuAction> =
    when {
        !viewerIsMember || !viewerIsAdmin -> emptyList()
        targetIsSelf -> if (targetIsAdmin) listOf(GroupMemberMenuAction.StepDownAsAdmin) else emptyList()
        targetIsAdmin -> listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember)
        else -> listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember)
    }

/**
 * Member rows are rendered in [Column] containers, so Compose would otherwise
 * identify each child by position. Key each row by member identity so menu and
 * avatar state move with the member when invite/profile churn re-sorts the list.
 */
@Composable
internal fun GroupMemberIdentityRows(
    members: List<AppGroupMemberRecordFfi>,
    content: @Composable (index: Int, member: AppGroupMemberRecordFfi) -> Unit,
) {
    members.forEachIndexed { index, member ->
        key(member.memberIdHex) {
            content(index, member)
        }
    }
}

/**
 * Bottom sheet listing the non-admin members eligible to receive a transferred
 * admin role, with a search/filter field. Picking a member hands selection
 * back to the caller, which confirms before mutating. Mirrors the Add member
 * sheet's structure (issue #417).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferAdminSheet(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    busy: Boolean,
    onPick: (AppGroupMemberRecordFfi) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(controller.members) { controller.transferAdminCandidates() }
    val filtered =
        remember(query, candidates) {
            val needle = query.trim()
            if (needle.isBlank()) {
                candidates
            } else {
                candidates.filter { member ->
                    controller.memberDisplayName(member).contains(needle, ignoreCase = true) ||
                        appState.npub(member.memberIdHex).contains(needle, ignoreCase = true) ||
                        member.memberIdHex.contains(needle, ignoreCase = true)
                }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.transfer_admin),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.transfer_admin_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidates.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.conversation_search_open)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                candidates.isEmpty() ->
                    Text(
                        stringResource(R.string.transfer_admin_no_candidates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                filtered.isEmpty() ->
                    Text(
                        stringResource(R.string.conversation_search_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    ) {
                        GroupMemberIdentityRows(filtered) { _, member ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy, role = Role.Button) { onPick(member) }
                                        .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(
                                    title = controller.memberDisplayName(member),
                                    seed = member.memberIdHex,
                                    size = 40.dp,
                                    pictureUrl = controller.memberAvatarUrl(member),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        controller.memberDisplayName(member),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        controller.memberSubtitle(member),
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }
}

/** The chat-list Delete flow when the active account is the sole admin (#1131). */
internal data class SoleAdminDeletePrompt(
    val item: ChatListItem,
    val candidates: List<AppGroupMemberRecordFfi>,
)

/**
 * Picker shown when deleting a group as sole admin of a 3+ member group (#1131):
 * choose who becomes admin before leaving. Self-contained (resolves member
 * identity off [appState]) so the chat-list surface doesn't depend on a
 * ConversationController the way [TransferAdminSheet] does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoleAdminDeletePicker(
    candidates: List<AppGroupMemberRecordFfi>,
    appState: WhiteNoiseAppState,
    onPick: (AppGroupMemberRecordFfi) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.transfer_admin),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.transfer_admin_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                GroupMemberIdentityRows(candidates) { _, member ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onPick(member) }
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            title = appState.displayName(member.memberIdHex),
                            seed = member.memberIdHex,
                            size = 40.dp,
                            pictureUrl = appState.avatarUrl(member.memberIdHex),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                appState.displayName(member.memberIdHex),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                appState.shortNpub(member.memberIdHex),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
