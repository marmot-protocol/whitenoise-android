package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.newchat.PersonRow
import dev.ipf.whitenoise.android.ui.chats.newchat.personKeyPlacement
import dev.ipf.whitenoise.android.ui.common.PersonAvatar
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetInset
import dev.ipf.whitenoise.android.ui.common.abbreviatesPublicKey
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal =
        rememberSheetTerminalMotion(sheetState, Triple(appState, appState.activeAccountRef, appState.runtimeGeneration))
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

    WhiteNoiseModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { terminal.close(onDismiss) },
    ) {
        WhiteNoiseSheetHeader(stringResource(R.string.transfer_admin))
        val sheetPadding = Modifier.padding(horizontal = WhiteNoiseSheetInset)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = WhiteNoiseSpacing.FormField),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.transfer_admin_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = sheetPadding,
            )
            if (candidates.isNotEmpty()) {
                WhiteNoiseCompactSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.conversation_search_open),
                    modifier = sheetPadding.fillMaxWidth(),
                    outlined = true,
                )
            }
            when {
                candidates.isEmpty() ->
                    Text(
                        stringResource(R.string.transfer_admin_no_candidates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = sheetPadding,
                    )
                filtered.isEmpty() ->
                    WhiteNoiseEmptyState(stringResource(R.string.no_matches))
                else ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WhiteNoiseSheetInset - WhiteNoiseSpacing.CompactScreenMargin)
                            .heightIn(max = 360.dp)
                            .fadingVerticalScroll(rememberScrollState()),
                    ) {
                        GroupMemberIdentityRows(filtered) { index, member ->
                            val memberName = controller.memberDisplayName(member)
                            val keys =
                                personKeyPlacement(
                                    title = memberName,
                                    subtitle = controller.memberSubtitle(member).takeIf { it.isNotBlank() },
                                    npub = appState.npubForDisplay(member.memberIdHex),
                                )
                            PersonRow(
                                title = memberName,
                                subtitle = keys.subtitle,
                                avatarSeed = member.memberIdHex,
                                avatarUrl = controller.memberAvatarUrl(member),
                                index = index,
                                count = filtered.size,
                                followedAccount = member.memberIdHex,
                                enabled = !busy,
                                onClick = { terminal.close(member, onPick) },
                                publicKey = keys.publicKey,
                                publicKeyInTitle = keys.inTitle,
                                avatarDomain = AvatarImageLoader.publicProfiles(),
                            )
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

private fun soleAdminEmptyPickerTag(empty: Boolean): Modifier =
    if (empty) {
        Modifier.testTag("sole-admin-delete-picker-empty").exposePerformanceTestTags()
    } else {
        Modifier
    }

/**
 * Picker shown when deleting a group as sole admin of a 3+ member group (#1131):
 * choose who becomes admin before leaving. Self-contained (resolves member
 * identity off [appState]) so the chat-list surface doesn't depend on a
 * ConversationController the way [TransferAdminSheet] does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// The member list and selection completion share one sheet terminal-motion lifetime.
@Suppress("LongMethod", "FunctionNaming")
internal fun SoleAdminDeletePicker(
    candidates: List<AppGroupMemberRecordFfi>,
    appState: WhiteNoiseAppState,
    onPick: (AppGroupMemberRecordFfi) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal =
        rememberSheetTerminalMotion(sheetState, Triple(appState, appState.activeAccountRef, appState.runtimeGeneration))
    WhiteNoiseModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { terminal.close(onDismiss) },
        modifier = soleAdminEmptyPickerTag(candidates.isEmpty()),
    ) {
        WhiteNoiseSheetHeader(stringResource(R.string.transfer_admin))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSheetInset)
                .padding(bottom = WhiteNoiseSpacing.FormField),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.transfer_admin_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).fadingVerticalScroll(rememberScrollState()),
            ) {
                GroupMemberIdentityRows(candidates) { _, member ->
                    val title = appState.displayName(member.memberIdHex)
                    val npub = appState.npubForDisplay(member.memberIdHex)
                    val titleIsKey = abbreviatesPublicKey(title, npub)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { terminal.close(member, onPick) }
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonAvatar(
                            title = appState.displayName(member.memberIdHex),
                            seed = member.memberIdHex,
                            size = 40.dp,
                            pictureUrl = appState.contactAvatarSource(member.memberIdHex),
                            followedAccount = member.memberIdHex,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (titleIsKey) {
                                PublicKeyCopyText(title, npub)
                            } else {
                                EmojiLabel(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                PublicKeyCopyText(
                                    appState.shortNpub(member.memberIdHex),
                                    npub,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
