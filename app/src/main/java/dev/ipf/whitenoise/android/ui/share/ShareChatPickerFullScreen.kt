@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.localeInvariantFold
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetAvatarAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetMembersPreview
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

internal const val SHARE_CHAT_PICKER_SCREEN_TEST_TAG = "share_chat_picker_full_screen"
internal const val SHARE_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG = "share_chat_picker_account_row"
internal const val SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG = "share_chat_picker_account_sheet"

/** Visible production surface, split from its modal window for deterministic screenshot capture. */
@Composable
internal fun ShareChatPickerFullScreenContent(
    appState: WhiteNoiseAppState,
    requestId: String = "",
    payload: SharePayload,
    onDismiss: () -> Unit,
    onStage: (String, List<String>) -> Boolean,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar? = null,
    controllerFactory: (WhiteNoiseAppState) -> ChatsController = { ChatsController(it) },
    controllerBinder: suspend (ChatsController, String) -> Unit = { controller, accountRef ->
        controller.bind(accountRef)
    },
) {
    val pickerState =
        rememberShareChatPickerState(
            appState = appState,
            requestId = requestId,
            payload = payload,
            controllerFactory = controllerFactory,
            controllerBinder = controllerBinder,
        )
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
        overlayBack = pickerState.searchFocused || pickerState.accountSelectorOpen,
        onBackCommit = {
            if (pickerState.accountSelectorOpen) {
                pickerState.accountSelectorOpen = false
            } else {
                dismissPicker()
            }
        },
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
                    enabled = pickerState.canStage,
                    onStage = {
                        if (!finishing && pickerState.stage(onStage)) {
                            dismissPicker()
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
    if (pickerState.accountSelectorOpen) {
        ShareChatPickerAccountSheet(
            pickerState = pickerState,
            onDismiss = { pickerState.accountSelectorOpen = false },
        )
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
    val memberSnapshotsRevision = pickerState.memberSnapshotsRevision
    val currentTargets =
        remember(pickerState.targets, memberSnapshotsRevision) {
            pickerState.targets
        }
    val unresolvedDirectGroupIds =
        remember(currentTargets, pickerState.activeAccountIdHex) {
            currentTargets
                .filter { target ->
                    target.projection?.conversationKind == ChatConversationKindFfi.DIRECT &&
                        shareTargetAccountIds(target, pickerState.activeAccountIdHex).isEmpty()
                }.map { it.group.groupIdHex }
        }
    LaunchedEffect(pickerState.selectedAccountRef, unresolvedDirectGroupIds) {
        pickerState.requestTargetMembers(unresolvedDirectGroupIds)
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
    val aliasesByAccount =
        rememberShareAccountAliases(
            appState = appState,
            ownerAccountRef = pickerState.selectedAccountRef,
            accountIds = targetAccountIds,
        )
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
    BoxWithConstraints(modifier) {
        val compactHeight = maxHeight < 480.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
        ) {
            if (compactHeight) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShareChatPickerPreview(
                        previewText = pickerState.previewText,
                        attachmentCount = pickerState.attachmentCount,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    ShareChatPickerAccountRow(
                        pickerState = pickerState,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                ShareChatPickerPreview(
                    previewText = pickerState.previewText,
                    attachmentCount = pickerState.attachmentCount,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                )
                ShareChatPickerAccountRow(
                    pickerState = pickerState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                )
            }
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
}

@Composable
private fun ShareChatPickerPreview(
    previewText: String,
    attachmentCount: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier,
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
            maxLines = if (compact) 1 else 3,
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
        if (pickerState.isLoading && pickerState.targets.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingScreen()
                }
            }
        } else if (pickerState.error != null && pickerState.targets.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize()) {
                    ErrorContent(
                        title = stringResource(R.string.couldnt_load_chats),
                        error = pickerState.error,
                        onRetry = pickerState::retryLoad,
                    )
                }
            }
        } else if (pickerState.targets.isEmpty() || filteredTargets.isEmpty()) {
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
            pickerState.error?.let { failure ->
                item(key = "share-picker-load-error") {
                    InlineErrorBanner(error = failure, onRetry = pickerState::retryLoad)
                }
            }
            item { SectionHeader(stringResource(R.string.recent_chats)) }
            items(filteredTargets, key = { it.item.group.groupIdHex }) { target ->
                ShareTargetRow(
                    item = target.item,
                    title = target.title,
                    selected = pickerState.selected.contains(target.item.group.groupIdHex),
                    activeAccountIdHex = pickerState.activeAccountIdHex,
                    ownerAccountRef = pickerState.selectedAccountRef,
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
    enabled: Boolean,
    onStage: () -> Unit,
) {
    StickyFormActionBar {
        Button(
            onClick = onStage,
            enabled = enabled,
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
    val accounts: List<AccountSummaryFfi>,
    val selectedAccountRef: String?,
    val activeAccountIdHex: String?,
    val isLoading: Boolean,
    val error: ErrorPresentation?,
    val memberSnapshotsRevision: Long,
    private val selectedAccountRefState: MutableState<String?>,
    private val accountController: ChatsController?,
    val previewText: String,
    val attachmentCount: Int,
    private val queryState: MutableState<String>,
    private val selectedState: MutableState<ArrayList<String>>,
    private val searchFocusedState: MutableState<Boolean>,
    private val accountSelectorOpenState: MutableState<Boolean>,
) {
    var query: String
        get() = queryState.value
        set(value) {
            queryState.value = value
        }
    var searchFocused: Boolean
        get() = searchFocusedState.value
        set(value) {
            searchFocusedState.value = value
        }
    var accountSelectorOpen: Boolean
        get() = accountSelectorOpenState.value
        set(value) {
            accountSelectorOpenState.value = value
        }
    val selected: List<String>
        get() = selectedState.value
    val canStage: Boolean
        get() {
            if (selected.isEmpty()) return false
            val targetIds = targets.mapTo(hashSetOf()) { it.group.groupIdHex }
            return selected.all(targetIds::contains)
        }

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

    fun chooseAccount(accountRef: String) {
        if (accountRef == selectedAccountRef || accounts.none { it.label == accountRef }) return
        selectedAccountRefState.value = accountRef
        selectedState.value = arrayListOf()
        accountSelectorOpen = false
    }

    fun requestTargetMembers(groupIds: Iterable<String>) {
        if (selectedAccountRef == appState.activeAccountRef) {
            appState.requestForwardTargetMembers(groupIds)
        } else {
            accountController?.requestMemberSnapshots(groupIds)
        }
    }

    fun retryLoad() {
        accountController?.retryLoad()
    }

    fun stage(onStage: (String, List<String>) -> Boolean): Boolean {
        val accountRef = selectedAccountRef ?: return false
        if (accounts.none { it.label == accountRef && it.isSignedInSigningAccount() }) return false
        if (!canStage) return false
        return onStage(accountRef, selected.toList())
    }
}

@Composable
private fun ShareChatPickerAccountRow(
    pickerState: ShareChatPickerState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val account = pickerState.accounts.firstOrNull { it.label == pickerState.selectedAccountRef } ?: return
    val accountTitle = pickerState.appState.networkDisplayName(account.accountIdHex)
    val multipleAccounts = pickerState.accounts.size > 1
    Surface(
        onClick = { if (multipleAccounts) pickerState.accountSelectorOpen = true },
        enabled = multipleAccounts,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier.testTag(SHARE_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                title = accountTitle,
                seed = account.accountIdHex,
                size = if (compact) 32.dp else 40.dp,
                pictureUrl = pickerState.appState.avatarUrl(account.accountIdHex),
            )
            Column(Modifier.weight(1f)) {
                if (compact) {
                    Text(
                        text = "${stringResource(R.string.share_sending_as)}: $accountTitle",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.share_sending_as),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(accountTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pickerState.appState.shortNpub(account.accountIdHex),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (multipleAccounts) {
                Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.share_choose_sending_account))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareChatPickerAccountSheet(
    pickerState: ShareChatPickerState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        ShareChatPickerAccountSheetContent(
            appState = pickerState.appState,
            accounts = pickerState.accounts,
            selectedAccountRef = pickerState.selectedAccountRef,
            onChooseAccount = pickerState::chooseAccount,
        )
    }
}

@Composable
internal fun ShareChatPickerAccountSheetContent(
    appState: WhiteNoiseAppState,
    accounts: List<AccountSummaryFfi>,
    selectedAccountRef: String?,
    onChooseAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.share_choose_sending_account), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.share_choose_sending_account_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(accounts, key = AccountSummaryFfi::label) { account ->
                val selected = account.label == selectedAccountRef
                val accountTitle = appState.networkDisplayName(account.accountIdHex)
                ListItem(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { onChooseAccount(account.label) }
                            .semantics { this.selected = selected },
                    colors =
                        ListItemDefaults.colors(
                            containerColor =
                                if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                    leadingContent = {
                        Avatar(
                            title = accountTitle,
                            seed = account.accountIdHex,
                            size = 44.dp,
                            pictureUrl = appState.avatarUrl(account.accountIdHex),
                        )
                    },
                    headlineContent = { Text(accountTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            appState.shortNpub(account.accountIdHex),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.selected))
                        }
                    },
                )
            }
        }
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
    controllerFactory: (WhiteNoiseAppState) -> ChatsController,
    controllerBinder: suspend (ChatsController, String) -> Unit,
): ShareChatPickerState {
    val accounts = appState.accounts.filter(AccountSummaryFfi::isSignedInSigningAccount)
    val initialAccountRef =
        appState.activeAccountRef?.takeIf { active -> accounts.any { it.label == active } }
            ?: accounts.firstOrNull()?.label
    val selectedAccountRefState =
        rememberSaveable(requestId, payload) {
            mutableStateOf(initialAccountRef)
        }
    val selectedAccountRef =
        selectedAccountRefState.value.takeIf { selected -> accounts.any { it.label == selected } }
            ?: initialAccountRef
    val queryState = rememberSaveable(requestId, payload) { mutableStateOf("") }
    val selectedState =
        rememberSaveable(requestId, payload) {
            mutableStateOf(arrayListOf<String>())
        }
    val searchFocusedState = remember(requestId, payload) { mutableStateOf(false) }
    val accountSelectorOpenState = remember(requestId, payload) { mutableStateOf(false) }
    LaunchedEffect(selectedAccountRef, selectedAccountRefState.value) {
        if (selectedAccountRefState.value != selectedAccountRef) {
            selectedAccountRefState.value = selectedAccountRef
            selectedState.value = arrayListOf()
        }
    }
    val accountController =
        if (selectedAccountRef != null && selectedAccountRef != appState.activeAccountRef) {
            remember(appState, selectedAccountRef) { controllerFactory(appState) }
        } else {
            null
        }
    DisposableEffect(accountController) {
        onDispose { accountController?.onCleared() }
    }
    LaunchedEffect(accountController, selectedAccountRef) {
        if (accountController != null && selectedAccountRef != null) {
            controllerBinder(accountController, selectedAccountRef)
        }
    }
    val targets =
        if (accountController == null) {
            appState.forwardTargets()
        } else {
            accountController.forwardTargets()
        }
    val selectedAccount = accounts.firstOrNull { it.label == selectedAccountRef }
    LaunchedEffect(selectedAccountRef, accountController?.isLoading, targets) {
        if (accountController?.isLoading == true) return@LaunchedEffect
        val targetIds = targets.mapTo(hashSetOf()) { it.group.groupIdHex }
        val validSelection = selectedState.value.filterTo(arrayListOf(), targetIds::contains)
        if (validSelection != selectedState.value) selectedState.value = validSelection
    }
    return remember(
        appState,
        requestId,
        payload,
        targets,
        accounts,
        selectedAccountRef,
        accountController,
        accountController?.isLoading,
        accountController?.error,
        accountController?.memberSnapshotsRevision,
        queryState,
        selectedState,
        searchFocusedState,
        accountSelectorOpenState,
    ) {
        ShareChatPickerState(
            appState = appState,
            payload = payload,
            targets = targets,
            accounts = accounts,
            selectedAccountRef = selectedAccountRef,
            activeAccountIdHex = selectedAccount?.accountIdHex,
            isLoading = accountController?.isLoading == true,
            error = accountController?.error,
            memberSnapshotsRevision =
                accountController?.memberSnapshotsRevision ?: appState.forwardTargetMembersRevision,
            selectedAccountRefState = selectedAccountRefState,
            accountController = accountController,
            previewText = payload.text?.trim().orEmpty(),
            attachmentCount = payload.streamUris.size,
            queryState = queryState,
            selectedState = selectedState,
            searchFocusedState = searchFocusedState,
            accountSelectorOpenState = accountSelectorOpenState,
        )
    }
}

@Composable
private fun ShareTargetRow(
    item: ChatListItem,
    title: String,
    selected: Boolean,
    activeAccountIdHex: String?,
    ownerAccountRef: String?,
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
        remember(item, ownerAccountRef, memberRevisions) {
            forwardTargetMembersPreview(item, activeAccountIdHex) { memberIdHex ->
                appState.contactDisplayNameCached(ownerAccountRef, memberIdHex)
            }
        }
    ContactRow(
        title = title,
        subtitle = membersPreview,
        avatarSeed = avatarAccount ?: item.group.groupIdHex,
        avatarUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
        avatarImage = rememberEncryptedGroupAvatar(appState, item.group, ownerAccountRef),
        modifier = Modifier.semantics { this.selected = selected },
        onClick = { onToggle(groupId) },
        trailing = { SelectionIndicator(selected = selected) },
    )
}
