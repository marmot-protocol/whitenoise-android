@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ProfileGroupPickerLoadState
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMembersReviewDialog
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.chats.newchat.PersonRow
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.chats.newchat.groupReviewAddMembersLabel
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.VISIBLE_GROUP_AVATAR_LIMIT
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.rememberChatListGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Shield
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Keyboard space is consumed outside the scaffold, matching the other full-screen creation routes. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ProfileAddToGroupsScreen(
    appState: WhiteNoiseAppState,
    target: RecipientSearch.Candidate,
    state: ProfileGroupPickerState,
    busy: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onAdd: (List<ChatListItem>) -> Unit,
    keyboardInsets: WindowInsets = WindowInsets.ime,
) {
    SettingsScaffold(
        title = stringResource(R.string.person_add_to_group),
        onBack = onClose,
        modifier = Modifier.windowInsetsPadding(keyboardInsets),
    ) {
        ProfileAddToGroupsContent(appState, target, state, busy, onClose, onRetry, onAdd)
    }
}

/** Keeps group choices scrollable while the selected-groups action stays reachable. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileAddToGroupsContent(
    appState: WhiteNoiseAppState,
    target: RecipientSearch.Candidate,
    state: ProfileGroupPickerState,
    busy: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onAdd: (List<ChatListItem>) -> Unit,
) {
    val targetName = target.displayName
    val groups = state.groups
    val groupTitleCopy = rememberGroupTitleCopy()
    val selected =
        remember(appState.activeAccountRef, appState.runtimeGeneration, target.accountIdHex) {
            mutableStateListOf<String>()
        }
    var confirmSelection by remember(appState.activeAccountRef, appState.runtimeGeneration, target.accountIdHex) {
        mutableStateOf<List<ChatListItem>?>(null)
    }
    var query by remember(appState.activeAccountRef, appState.runtimeGeneration, target.accountIdHex) {
        mutableStateOf("")
    }
    val titledGroups =
        remember(groups, groupTitleCopy) {
            groups.map { it to chatListItemDisplayTitle(it, appState, groupTitleCopy) }
        }
    val filteredGroups =
        remember(titledGroups, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledGroups
            } else {
                titledGroups.filter { (_, title) -> title.contains(needle, ignoreCase = true) }
            }
        }
    LaunchedEffect(groups) {
        val availableGroupIds = groups.mapTo(mutableSetOf()) { it.group.groupIdHex }
        selected.removeAll { it !in availableGroupIds }
    }
    val selectedGroups = groups.filter { selected.contains(it.group.groupIdHex) }
    PreparedGroupAvatarContent(
        appState,
        filteredGroups.take(VISIBLE_GROUP_AVATAR_LIMIT).mapNotNull { it.first.selectedAvatarAsset },
    ) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 12.dp).testTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WhiteNoiseLazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("profile.add_groups.list"),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmojiLabel(
                            stringResource(R.string.profile_add_to_groups_title, targetName),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        EmojiLabel(
                            stringResource(R.string.profile_add_to_groups_description, targetName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        if (state.loadState != ProfileGroupPickerLoadState.READY) {
                            ProfileGroupPickerPendingState(state.loadState, onRetry)
                        } else if (groups.isEmpty()) {
                            Text(
                                stringResource(R.string.profile_no_addable_groups),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
                            )
                        }
                        if (groups.isNotEmpty()) {
                            FlowSearchField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = stringResource(R.string.forward_search_chats),
                                modifier =
                                    Modifier.padding(horizontal = Dimens.spaceLg).padding(bottom = Dimens.spaceMd),
                            )
                        }
                    }
                }
                if (groups.isNotEmpty() && filteredGroups.isEmpty()) {
                    item {
                        WhiteNoiseEmptyState(stringResource(R.string.no_matches))
                    }
                }
                itemsIndexed(filteredGroups, key = { _, (item, _) -> item.group.groupIdHex }) { index, (item, title) ->
                    val groupId = item.group.groupIdHex
                    val isSelected = selected.contains(groupId)
                    val avatar = rememberChatListGroupAvatar(appState, item)
                    PersonRow(
                        index = index,
                        count = filteredGroups.size,
                        title = title,
                        subtitle = stringResource(R.string.members_count, item.memberCount),
                        avatarSeed = groupId,
                        avatarUrl = avatar.pictureUrl,
                        avatarImage = avatar.image,
                        groupAnimation = avatar.groupAnimation,
                        avatarDomain = avatar.pictureDomain,
                        enabled = !busy,
                        onClick = {
                            if (isSelected) selected.remove(groupId) else selected.add(groupId)
                        },
                        trailing = { SelectionIndicator(selected = isSelected) },
                    )
                }
            }
            if (groups.isEmpty() && state.loadState == ProfileGroupPickerLoadState.READY) {
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(stringResource(R.string.close))
                }
            } else if (groups.isNotEmpty()) {
                Button(
                    onClick = { confirmSelection = selectedGroups },
                    enabled = selectedGroups.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("profile.add_groups.action"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_add_to_groups_confirm_label))
                }
            }
        }
    }

    confirmSelection?.let { targets ->
        val reviewGroups =
            targets.map { item ->
                val avatar = rememberChatListGroupAvatar(appState, item)
                MemberReviewGroup(
                    item.group.groupIdHex,
                    chatListItemDisplayTitle(item, appState, groupTitleCopy),
                    avatar.pictureUrl,
                    avatar.image,
                    groupAnimation = avatar.groupAnimation,
                )
            }
        GroupMembersReviewDialog(
            appState = appState,
            groups = reviewGroups,
            members = listOf(target),
            busy = busy,
            confirmLabel = stringResource(groupReviewAddMembersLabel(1)),
            onConfirm = {
                confirmSelection = null
                onAdd(targets)
            },
            onDismiss = { confirmSelection = null },
        )
    }
}

/** Searchable picker of groups where [target] can be made an admin, with a confirmation before promoting. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileMakeAdminContent(
    appState: WhiteNoiseAppState,
    targetName: String,
    target: RecipientSearch.Candidate,
    state: ProfileGroupPickerState,
    busy: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPromote: (ChatListItem) -> Unit,
) {
    val groups = state.groups
    val groupTitleCopy = rememberGroupTitleCopy()
    var selectedGroupId by remember(appState.activeAccountRef, appState.runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var confirmGroupId by remember(appState.activeAccountRef, appState.runtimeGeneration, target.accountIdHex) {
        mutableStateOf<String?>(null)
    }
    var query by remember(appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf("") }
    val titledGroups =
        remember(groups, groupTitleCopy) {
            groups.map { it to chatListItemDisplayTitle(it, appState, groupTitleCopy) }
        }
    val filteredGroups =
        remember(titledGroups, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledGroups
            } else {
                titledGroups.filter { (_, title) -> title.contains(needle, ignoreCase = true) }
            }
        }
    LaunchedEffect(groups) {
        if (groups.none { it.group.groupIdHex == selectedGroupId }) selectedGroupId = null
        if (groups.none { it.group.groupIdHex == confirmGroupId }) confirmGroupId = null
    }
    val selectedGroup = groups.firstOrNull { it.group.groupIdHex == selectedGroupId }
    PreparedGroupAvatarContent(
        appState,
        filteredGroups.take(VISIBLE_GROUP_AVATAR_LIMIT).mapNotNull { it.first.selectedAvatarAsset },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag(PROFILE_MAKE_ADMIN_CONTENT_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmojiLabel(
                stringResource(R.string.make_admin),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
            )
            EmojiLabel(
                stringResource(
                    R.string.profile_make_admin_description,
                    if (IdentityFormatter.isNostrIdentityFallback(targetName)) {
                        IdentityFormatter.short(targetName, prefix = 10, suffix = 8)
                    } else {
                        targetName
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
            )
            if (groups.isEmpty() && state.loadState == ProfileGroupPickerLoadState.READY) {
                Text(
                    stringResource(R.string.profile_no_promotable_groups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                ) {
                    Text(stringResource(R.string.close))
                }
            } else if (groups.isEmpty()) {
                ProfileGroupPickerPendingState(state.loadState, onRetry)
            } else {
                if (state.loadState != ProfileGroupPickerLoadState.READY) {
                    ProfileGroupPickerPendingState(state.loadState, onRetry)
                }
                FlowSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.forward_search_chats),
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg),
                )
                WhiteNoiseLazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (filteredGroups.isEmpty()) {
                        item {
                            WhiteNoiseEmptyState(stringResource(R.string.no_matches))
                        }
                    }
                    itemsIndexed(
                        filteredGroups,
                        key = { _, (item, _) -> item.group.groupIdHex },
                    ) { index, (item, title) ->
                        val selected = item.group.groupIdHex == selectedGroupId
                        val avatar = rememberChatListGroupAvatar(appState, item)
                        PersonRow(
                            index = index,
                            count = filteredGroups.size,
                            title = title,
                            subtitle = stringResource(R.string.members_count, item.memberCount),
                            avatarSeed = item.group.groupIdHex,
                            avatarUrl = avatar.pictureUrl,
                            avatarImage = avatar.image,
                            groupAnimation = avatar.groupAnimation,
                            avatarDomain = avatar.pictureDomain,
                            enabled = !busy,
                            onClick = { selectedGroupId = item.group.groupIdHex },
                            trailing = { SelectionIndicator(selected = selected) },
                        )
                    }
                }
                Button(
                    onClick = { confirmGroupId = selectedGroup?.group?.groupIdHex },
                    enabled = selectedGroup != null && target.accountIdHex.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Shield, contentDescription = null)
                    }
                    Spacer(Modifier.width(Dimens.spaceSm))
                    Text(stringResource(R.string.make_admin))
                }
            }
        }
    }
    groups.firstOrNull { it.group.groupIdHex == confirmGroupId }?.let { group ->
        val avatar = rememberChatListGroupAvatar(appState, group)
        GroupMembersReviewDialog(
            appState = appState,
            groups =
                listOf(
                    MemberReviewGroup(
                        group.group.groupIdHex,
                        chatListItemDisplayTitle(group, appState, groupTitleCopy),
                        avatar.pictureUrl,
                        avatar.image,
                        groupAnimation = avatar.groupAnimation,
                    ),
                ),
            members = listOf(target),
            busy = busy,
            title = stringResource(R.string.make_admin),
            description = null,
            invitation = false,
            confirmLabel = stringResource(R.string.make_admin),
            onConfirm = {
                confirmGroupId = null
                onPromote(group)
            },
            onDismiss = { confirmGroupId = null },
        )
    }
}

/** Loading row, or a failure row with a retry button, shown while the group picker list is unavailable. */
@Composable
@Suppress("FunctionNaming")
private fun ProfileGroupPickerPendingState(
    loadState: ProfileGroupPickerLoadState,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loadState == ProfileGroupPickerLoadState.LOADING) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Text(
            stringResource(
                if (loadState == ProfileGroupPickerLoadState.FAILED) {
                    R.string.profile_addable_groups_failed
                } else {
                    R.string.profile_addable_groups_loading
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (loadState == ProfileGroupPickerLoadState.FAILED) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}
