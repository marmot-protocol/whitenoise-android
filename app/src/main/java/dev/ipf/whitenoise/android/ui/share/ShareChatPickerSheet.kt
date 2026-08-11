@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.localeInvariantFold
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetAvatarAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetMembersPreview
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.launch

internal fun runShareChatPickerDismissal(
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
    hideSheet: () -> Unit,
) {
    clearFocus()
    hideKeyboard()
    hideSheet()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareChatPickerSheet(
    appState: WhiteNoiseAppState,
    payload: SharePayload,
    onDismiss: () -> Unit,
    onStage: (List<String>) -> Unit,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar? = null,
) {
    val pickerState = rememberShareChatPickerState(appState, payload)
    val presentedTargets = rememberShareChatPickerPresentations(appState, pickerState)
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var dismissing by remember { mutableStateOf(false) }
    val dismissSheet: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            runShareChatPickerDismissal(
                clearFocus = { focusManager.clearFocus(force = true) },
                hideKeyboard = { keyboardController?.hide() },
                hideSheet = {
                    scope.launch {
                        try {
                            sheetState.hide()
                            if (!sheetState.isVisible) currentOnDismiss()
                        } finally {
                            if (sheetState.isVisible) dismissing = false
                        }
                    }
                },
            )
        }
    }
    LaunchedEffect(pickerState.searchFocused) {
        if (pickerState.searchFocused) sheetState.expand()
    }
    ShareChatPickerBackAwareSheet(
        sheetState = sheetState,
        overlayBack = pickerState.searchFocused,
        onDismissRequest = onDismiss,
        onBackCommit = dismissSheet,
        overlayBackRegistrar = overlayBackRegistrar,
    ) {
        ShareChatPickerContent(
            pickerState = pickerState,
            presentedTargets = presentedTargets,
            sheetExpanded =
                sheetState.currentValue == SheetValue.Expanded ||
                    sheetState.targetValue == SheetValue.Expanded,
            onDismiss = onDismiss,
            onStage = onStage,
        )
    }
}

@Composable
private fun rememberShareChatPickerPresentations(
    appState: WhiteNoiseAppState,
    pickerState: ShareChatPickerState,
): List<ShareChatPickerTargetPresentation> {
    val groupTitleCopy = rememberGroupTitleCopy()
    val memberSnapshotsRevision = appState.forwardTargetMembersRevision
    val currentTargets =
        remember(pickerState.targets, memberSnapshotsRevision) {
            val currentById = appState.forwardTargets().associateBy { it.group.groupIdHex }
            pickerState.targets.map { target ->
                currentById[target.group.groupIdHex] ?: target
            }
        }
    val unresolvedDirectGroupIds =
        remember(currentTargets, pickerState.activeAccountIdHex) {
            currentTargets
                .filter { target ->
                    target.projection?.conversationKind == ChatConversationKindFfi.DIRECT &&
                        shareTargetAccountIds(target, pickerState.activeAccountIdHex).isEmpty()
                }.map { it.group.groupIdHex }
        }
    LaunchedEffect(appState, unresolvedDirectGroupIds) {
        appState.requestForwardTargetMembers(unresolvedDirectGroupIds)
    }
    val targetAccountIds =
        remember(currentTargets, pickerState.activeAccountIdHex) {
            currentTargets
                .flatMap { shareTargetAccountIds(it, pickerState.activeAccountIdHex) }
                .distinct()
        }
    LaunchedEffect(appState, targetAccountIds) {
        appState.requestProfiles(targetAccountIds)
    }
    val aliasesByAccount = rememberShareAccountAliases(appState, targetAccountIds)
    return currentTargets.map { item ->
        key(item.group.groupIdHex) {
            val accountAliases =
                shareTargetAccountIds(item, pickerState.activeAccountIdHex)
                    .mapNotNull { accountIdHex ->
                        aliasesByAccount[accountIdHex]?.let { accountIdHex to it }
                    }.toMap()
            remember(item, accountAliases, groupTitleCopy) {
                shareTargetPresentation(
                    item = item,
                    accountAliases = accountAliases,
                    groupTitleCopy = groupTitleCopy,
                )
            }
        }
    }
}

@Composable
private fun ShareChatPickerContent(
    pickerState: ShareChatPickerState,
    presentedTargets: List<ShareChatPickerTargetPresentation>,
    sheetExpanded: Boolean,
    onDismiss: () -> Unit,
    onStage: (List<String>) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.share_to),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        ShareChatPickerPreview(
            previewText = pickerState.previewText,
            attachmentCount = pickerState.attachmentCount,
        )
        FlowSearchField(
            value = pickerState.query,
            onValueChange = { pickerState.query = it },
            placeholder = stringResource(R.string.share_search_chats),
            modifier =
                Modifier
                    .padding(horizontal = Dimens.spaceLg)
                    .onFocusChanged { pickerState.searchFocused = it.isFocused },
        )
        ShareChatPickerTargetList(
            pickerState = pickerState,
            filteredTargets = pickerState.filtered(presentedTargets),
            sheetExpanded = sheetExpanded,
        )
        ShareChatPickerFooter(
            selectedCount = pickerState.selected.size,
            onStage = {
                if (pickerState.stage(onStage)) onDismiss()
            },
        )
    }
}

@Composable
private fun ShareChatPickerPreview(
    previewText: String,
    attachmentCount: Int,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
    ) {
        Text(
            when {
                previewText.isNotEmpty() && attachmentCount > 0 ->
                    stringResource(R.string.share_preview_text_and_attachments, previewText, attachmentCount)
                previewText.isNotEmpty() -> previewText
                attachmentCount > 0 ->
                    pluralStringResource(
                        R.plurals.share_preview_attachments_count,
                        attachmentCount,
                        attachmentCount,
                    )
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ShareChatPickerTargetList(
    pickerState: ShareChatPickerState,
    filteredTargets: List<ShareChatPickerTargetPresentation>,
    sheetExpanded: Boolean,
) {
    val targetListMaxHeight = if (sheetExpanded) 420.dp else 152.dp
    LazyColumn(
        modifier =
            Modifier
                .heightIn(max = targetListMaxHeight)
                .fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Dimens.spaceLg),
    ) {
        if (pickerState.targets.isEmpty() || filteredTargets.isEmpty()) {
            item {
                Text(
                    stringResource(
                        if (pickerState.targets.isEmpty()) R.string.share_no_chats else R.string.share_no_matches,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                )
            }
        } else {
            item { SectionHeader(stringResource(R.string.recent_chats)) }
            items(filteredTargets, key = { it.item.group.groupIdHex }) { target ->
                ShareTargetRow(
                    item = target.item,
                    title = target.title,
                    selected = pickerState.selected.contains(target.item.group.groupIdHex),
                    activeAccountIdHex = pickerState.activeAccountIdHex,
                    appState = pickerState.appState,
                    onToggle = pickerState::toggleSelection,
                )
            }
        }
    }
}

@Composable
private fun ShareChatPickerFooter(
    selectedCount: Int,
    onStage: () -> Unit,
) {
    Surface(
        color = amoledSheetContainerColor(),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().imePadding(),
    ) {
        Button(
            onClick = onStage,
            enabled = selectedCount > 0,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.spaceLg, vertical = 12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (selectedCount == 0) {
                    stringResource(R.string.share)
                } else {
                    pluralStringResource(
                        R.plurals.share_to_chats_count,
                        selectedCount,
                        selectedCount,
                    )
                },
            )
        }
    }
}

private class ShareChatPickerState(
    val appState: WhiteNoiseAppState,
    val payload: SharePayload,
    val targets: List<ChatListItem>,
    private val activeAccountRef: String?,
    val activeAccountIdHex: String?,
    val previewText: String,
    val attachmentCount: Int,
) {
    var query by mutableStateOf("")
    var searchFocused by mutableStateOf(false)
    val selected = mutableStateListOf<String>()

    fun filtered(presentedTargets: List<ShareChatPickerTargetPresentation>): List<ShareChatPickerTargetPresentation> {
        val needle = query.trim()
        return if (needle.isEmpty()) {
            presentedTargets
        } else {
            val foldedNeedle = localeInvariantFold(needle)
            val matchIdentityAliases = looksLikeShareIdentityNeedle(foldedNeedle)
            presentedTargets.filter { target ->
                val humanMatch = target.foldedHumanSearchValues.any { it.contains(foldedNeedle) }
                val identityMatch =
                    matchIdentityAliases &&
                        target.foldedIdentitySearchValues.any { it.contains(foldedNeedle) }
                humanMatch || identityMatch
            }
        }
    }

    fun toggleSelection(groupId: String) {
        if (selected.contains(groupId)) selected.remove(groupId) else selected.add(groupId)
    }

    fun stage(onStage: (List<String>) -> Unit): Boolean {
        if (!sharePickerAccountStillActive(activeAccountRef, appState.activeAccountRef)) return false
        onStage(selected.toList())
        return true
    }
}

private data class ShareChatPickerTargetPresentation(
    val item: ChatListItem,
    val title: String,
    val foldedHumanSearchValues: List<String>,
    val foldedIdentitySearchValues: List<String>,
)

private fun shareTargetPresentation(
    item: ChatListItem,
    accountAliases: Map<String, ShareAccountAliases>,
    groupTitleCopy: GroupTitleCopy,
): ShareChatPickerTargetPresentation {
    val projectedTitle =
        item.sanitizedNamedTitle ?: GroupProjector.displayTitle(
            group = item.group,
            otherMemberAccount = item.presentationOtherMemberAccount,
            memberCount = item.presentationMemberCount,
            memberTitle = { accountIdHex ->
                accountAliases[accountIdHex]?.displayName ?: groupTitleCopy.unknownTitle
            },
            copy = groupTitleCopy,
            conversationKind = item.projection?.conversationKind,
            soleSelfMember = item.presentationActiveAccountIsSoleMember,
        )
    val title =
        when {
            item.sanitizedNamedTitle != null || projectedTitle != groupTitleCopy.unknownTitle -> projectedTitle
            accountAliases.isNotEmpty() -> accountAliases.values.first().displayName
            else -> "${groupTitleCopy.unknownTitle} · ${IdentityFormatter.short(item.group.groupIdHex)}"
        }
    val searchableTitle =
        title.takeIf {
            item.sanitizedNamedTitle != null ||
                title == groupTitleCopy.groupOfPeople(item.presentationMemberCount) ||
                accountAliases.values.any { aliases -> title in aliases.human }
        }
    val humanSearchValues =
        (listOfNotNull(searchableTitle) + accountAliases.values.flatMap { it.human })
            .mapNotNull(ProfileSanitizer::displayName)
            .distinct()
    return ShareChatPickerTargetPresentation(
        item = item,
        title = title,
        foldedHumanSearchValues = humanSearchValues.map(::localeInvariantFold),
        foldedIdentitySearchValues =
            (listOf(item.group.groupIdHex) + accountAliases.values.flatMap { it.identity })
                .map(::localeInvariantFold)
                .distinct(),
    )
}

private fun shareTargetAccountIds(
    item: ChatListItem,
    activeAccountIdHex: String?,
): List<String> =
    buildList {
        item.presentationOtherMemberAccount?.let(::add)
        val directTarget =
            item.presentationOtherMemberAccount != null ||
                item.projection?.conversationKind == ChatConversationKindFfi.DIRECT
        if (directTarget) {
            item.memberSnapshot
                ?.members
                .orEmpty()
                .forEach { add(it.memberIdHex) }
            item.group.welcomerAccountIdHex?.let(::add)
            item.latest?.sender?.let(::add)
        }
    }.filter { it.isNotBlank() && it != activeAccountIdHex }.distinct()

@Composable
private fun rememberShareChatPickerState(
    appState: WhiteNoiseAppState,
    payload: SharePayload,
): ShareChatPickerState {
    val targets = remember(appState) { appState.forwardTargets() }
    return remember(appState, payload, targets) {
        ShareChatPickerState(
            appState = appState,
            payload = payload,
            targets = targets,
            activeAccountRef = appState.activeAccountRef,
            activeAccountIdHex = appState.activeAccount?.accountIdHex,
            previewText = payload.text?.trim().orEmpty(),
            attachmentCount = payload.streamUris.size,
        )
    }
}

@Composable
private fun ShareTargetRow(
    item: ChatListItem,
    title: String,
    selected: Boolean,
    activeAccountIdHex: String?,
    appState: WhiteNoiseAppState,
    onToggle: (String) -> Unit,
) {
    val groupId = item.group.groupIdHex
    val avatarAccount = forwardTargetAvatarAccount(item)
    val memberIds =
        remember(item, activeAccountIdHex) {
            item.memberSnapshot
                ?.members
                .orEmpty()
                .map { it.memberIdHex }
                .filter { it.isNotBlank() && it != activeAccountIdHex }
                .distinct()
        }
    val memberRevisions = memberIds.map(appState::profileAccountRevisionForCompose)
    val membersPreview =
        remember(item, memberRevisions) {
            forwardTargetMembersPreview(item, activeAccountIdHex) { memberIdHex ->
                appState.contactDisplayNameCached(memberIdHex)
            }
        }
    ContactRow(
        title = title,
        subtitle = membersPreview,
        avatarSeed = avatarAccount ?: item.group.groupIdHex,
        avatarUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
        avatarImage = rememberEncryptedGroupAvatar(appState, item.group),
        onClick = { onToggle(groupId) },
        trailing = { SelectionIndicator(selected = selected) },
    )
}
