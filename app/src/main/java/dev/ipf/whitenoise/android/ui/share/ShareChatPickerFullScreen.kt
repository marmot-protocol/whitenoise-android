@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetAvatarAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetMembersPreview
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

internal const val SHARE_CHAT_PICKER_SCREEN_TEST_TAG = "share_chat_picker_full_screen"

/** Visible production surface, split from its modal window for deterministic screenshot capture. */
@Composable
internal fun ShareChatPickerFullScreenContent(
    appState: WhiteNoiseAppState,
    requestId: String = "",
    payload: SharePayload,
    onDismiss: () -> Unit,
    onStage: (List<String>) -> Unit,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar? = null,
) {
    val pickerState = rememberShareChatPickerState(appState, requestId, payload)
    val presentedTargets = rememberShareChatPickerPresentations(appState, pickerState)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shareToTitle = stringResource(R.string.share_to)
    var finishing by remember(requestId) { mutableStateOf(false) }
    val dismissPicker: () -> Unit = {
        if (!finishing) {
            finishing = true
            runShareChatPickerDismissal(
                clearFocus = { focusManager.clearFocus(force = true) },
                hideKeyboard = { keyboardController?.hide() },
                dismiss = onDismiss,
            )
        }
    }
    ShareChatPickerBackAwareScreen(
        overlayBack = pickerState.searchFocused,
        onBackCommit = dismissPicker,
        overlayBackRegistrar = overlayBackRegistrar,
    ) {
        Scaffold(
            modifier =
                Modifier.fillMaxSize().testTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG).semantics {
                    isTraversalGroup = true
                    paneTitle = shareToTitle
                },
            containerColor = amoledSheetContainerColor(),
            topBar = {
                TopAppBar(
                    title = { Text(shareToTitle) },
                    navigationIcon = { ShareChatPickerCloseButton(dismissPicker) },
                )
            },
            bottomBar = {
                ShareChatPickerFooter(
                    selectedCount = pickerState.selected.size,
                    onStage = {
                        if (!finishing && pickerState.stage(onStage)) {
                            finishing = true
                            onDismiss()
                        }
                    },
                )
            },
        ) { padding ->
            key(requestId) {
                ShareChatPickerContent(
                    pickerState = pickerState,
                    presentedTargets = presentedTargets,
                    modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                )
            }
        }
    }
}

@Composable
private fun ShareChatPickerCloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            modifier = Modifier.weight(1f),
            listState = listState,
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
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
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
    StickyFormActionBar {
        Button(
            onClick = onStage,
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth(),
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
    private val queryState: MutableState<String>,
    private val selectedState: MutableState<ArrayList<String>>,
) {
    var query: String
        get() = queryState.value
        set(value) {
            queryState.value = value
        }
    var searchFocused by mutableStateOf(false)
    val selected: List<String>
        get() = selectedState.value

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
        selectedState.value =
            ArrayList(selected).apply {
                if (contains(groupId)) remove(groupId) else add(groupId)
            }
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
    requestId: String,
    payload: SharePayload,
): ShareChatPickerState {
    val targets = remember(appState) { appState.forwardTargets() }
    val activeAccountRef = appState.activeAccountRef
    val queryState = rememberSaveable(requestId, activeAccountRef, payload) { mutableStateOf("") }
    val selectedState =
        rememberSaveable(requestId, activeAccountRef, payload) {
            mutableStateOf(arrayListOf<String>())
        }
    return remember(appState, requestId, payload, targets, queryState, selectedState) {
        ShareChatPickerState(
            appState = appState,
            payload = payload,
            targets = targets,
            activeAccountRef = activeAccountRef,
            activeAccountIdHex = appState.activeAccount?.accountIdHex,
            previewText = payload.text?.trim().orEmpty(),
            attachmentCount = payload.streamUris.size,
            queryState = queryState,
            selectedState = selectedState,
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
        modifier = Modifier.semantics { this.selected = selected },
        onClick = { onToggle(groupId) },
        trailing = { SelectionIndicator(selected = selected) },
    )
}
