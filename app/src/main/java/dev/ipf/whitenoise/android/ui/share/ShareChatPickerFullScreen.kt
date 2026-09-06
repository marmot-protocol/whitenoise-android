@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetAvatarAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetMembersPreview
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.launch

internal const val SHARE_CHAT_PICKER_SCREEN_TEST_TAG = PerformanceTestTags.SHARE_PICKER
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
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val stageRejectedMessage = stringResource(R.string.no_share_target_available)
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
    val scaffoldActions =
        ShareChatPickerScaffoldActions(
            dismiss = dismissPicker,
            stage = {
                if (!finishing) {
                    if (pickerState.stage(onStage)) {
                        dismissPicker()
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(stageRejectedMessage)
                        }
                    }
                }
            },
        )
    ShareChatPickerScaffold(
        pickerState = pickerState,
        presentedTargets = presentedTargets,
        requestId = requestId,
        actions = scaffoldActions,
        snackbarHostState = snackbarHostState,
        overlayBackRegistrar = overlayBackRegistrar,
    )
    if (pickerState.accountSelectorOpen) {
        ShareChatPickerAccountSheet(
            appState = pickerState.appState,
            accounts = pickerState.accounts,
            selectedAccountRef = pickerState.selectedAccountRef,
            onChooseAccount = pickerState::chooseAccount,
            onDismiss = { pickerState.accountSelectorOpen = false },
        )
    }
}

private data class ShareChatPickerScaffoldActions(
    val dismiss: () -> Unit,
    val stage: () -> Unit,
)

/** Builds the edge-to-edge modal surface and exports its first-frame benchmark selector. */
@Composable
private fun ShareChatPickerScaffold(
    pickerState: ShareChatPickerState,
    presentedTargets: List<ShareChatPickerTargetPresentation>,
    requestId: String,
    actions: ShareChatPickerScaffoldActions,
    snackbarHostState: SnackbarHostState,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar?,
) {
    val shareToTitle = stringResource(R.string.share_to)
    ShareChatPickerBackAwareScreen(
        overlayBack = pickerState.searchFocused || pickerState.accountSelectorOpen,
        onBackCommit = {
            if (pickerState.accountSelectorOpen) {
                pickerState.accountSelectorOpen = false
            } else {
                actions.dismiss()
            }
        },
        overlayBackRegistrar = overlayBackRegistrar,
    ) {
        Scaffold(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG)
                    .exposePerformanceTestTags()
                    .semantics {
                        isTraversalGroup = true
                        paneTitle = shareToTitle
                    },
            containerColor = amoledSheetContainerColor(),
            snackbarHost = { WhiteNoiseSnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(shareToTitle) },
                    navigationIcon = { ShareChatPickerCloseButton(actions.dismiss) },
                )
            },
            bottomBar = {
                ShareChatPickerFooter(
                    selectedCount = pickerState.selected.size,
                    enabled = pickerState.canStage,
                    onStage = actions.stage,
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
        remember(currentTargets, pickerState.selectedAccountIdHex) {
            currentTargets
                .filter { target ->
                    target.projection?.conversationKind == ChatConversationKindFfi.DIRECT &&
                        shareTargetAccountIds(target, pickerState.selectedAccountIdHex).isEmpty()
                }.map { it.group.groupIdHex }
        }
    LaunchedEffect(pickerState.selectedAccountRef, unresolvedDirectGroupIds) {
        pickerState.requestTargetMembers(unresolvedDirectGroupIds)
    }
    val targetAccountIds =
        remember(currentTargets, pickerState.selectedAccountIdHex) {
            currentTargets
                .flatMap { shareTargetAccountIds(it, pickerState.selectedAccountIdHex) }
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
                shareTargetAccountIds(item, pickerState.selectedAccountIdHex)
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
        val selectedAccount = pickerState.selectedAccount
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
        ) {
            if (compactHeight && selectedAccount != null) {
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
                        account = selectedAccount,
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
                selectedAccount?.let { account ->
                    ShareChatPickerAccountRow(
                        pickerState = pickerState,
                        account = account,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                    )
                }
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

/** Presents cached recipients immediately, including direct empty and inline failure states. */
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
        if (pickerState.targets.isEmpty()) {
            pickerState.error?.let { failure ->
                item(key = "share-picker-load-error") {
                    InlineErrorBanner(error = failure, onRetry = pickerState::retryLoad)
                }
            }
            item {
                Text(
                    stringResource(R.string.share_no_chats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                )
            }
        } else if (filteredTargets.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.share_no_matches),
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
                    selectedAccountIdHex = pickerState.selectedAccountIdHex,
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
    val targets: List<ChatListItem>,
    private val targetGroupIds: Set<String>,
    val accounts: List<AccountSummaryFfi>,
    val selectedAccountRef: String?,
    val selectedAccountIdHex: String?,
    val isLoading: Boolean,
    val error: ErrorPresentation?,
    val memberSnapshotsRevision: Long,
    private val selectedAccountRefState: MutableState<String?>,
    private val accountController: ChatsController?,
    private val retryLoadAction: () -> Unit,
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
    val selectedAccount: AccountSummaryFfi?
        get() = accounts.firstOrNull { it.label == selectedAccountRef }
    val canStage: Boolean
        get() = selected.isNotEmpty() && selected.all(targetGroupIds::contains)

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
        retryLoadAction()
    }

    fun stage(onStage: (String, List<String>) -> Boolean): Boolean =
        selectedAccountRef?.let { accountRef ->
            accounts.any { it.label == accountRef && it.isSignedInSigningAccount() } &&
                canStage &&
                onStage(accountRef, selected.toList())
        } == true
}

@Composable
private fun ShareChatPickerAccountRow(
    pickerState: ShareChatPickerState,
    account: AccountSummaryFfi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    ChatPickerSendingAccountRow(
        appState = pickerState.appState,
        account = account,
        multipleAccounts = pickerState.accounts.size > 1,
        onOpenSelector = { pickerState.accountSelectorOpen = true },
        modifier = modifier,
        compact = compact,
    )
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
    selectedAccountIdHex: String?,
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
    }.filter { it.isNotBlank() && it != selectedAccountIdHex }.distinct()

@Composable
@Suppress("LongMethod") // Centralizes request-keyed Compose state and controller ownership.
private fun rememberShareChatPickerState(
    appState: WhiteNoiseAppState,
    requestId: String,
    payload: SharePayload,
    controllerFactory: (WhiteNoiseAppState) -> ChatsController,
    controllerBinder: suspend (ChatsController, String) -> Unit,
): ShareChatPickerState {
    val accounts = appState.accounts.filter(AccountSummaryFfi::isSignedInSigningAccount)
    val selectionState =
        rememberShareChatPickerSelectionState(
            accounts = accounts,
            activeAccountRef = appState.activeAccountRef,
            requestId = requestId,
            payload = payload,
        )
    val selectedAccountRef = selectionState.selectedAccountRef
    val dataSource =
        rememberShareChatPickerDataSource(
            appState = appState,
            selectedAccountRef = selectedAccountRef,
            controllerFactory = controllerFactory,
            controllerBinder = controllerBinder,
        )
    val targets = dataSource.targets
    val targetGroupIds = remember(targets) { targets.mapTo(hashSetOf()) { it.group.groupIdHex } }
    val selectedAccount = accounts.firstOrNull { it.label == selectedAccountRef }
    LaunchedEffect(selectedAccountRef, dataSource.isLoading, targetGroupIds) {
        if (dataSource.isLoading) return@LaunchedEffect
        val validSelection = selectionState.selectedState.value.filterTo(arrayListOf(), targetGroupIds::contains)
        if (validSelection != selectionState.selectedState.value) selectionState.selectedState.value = validSelection
    }
    return remember(
        appState,
        requestId,
        payload,
        targets,
        accounts,
        selectedAccountRef,
        targetGroupIds,
        dataSource,
        selectionState,
    ) {
        ShareChatPickerState(
            appState = appState,
            targets = targets,
            targetGroupIds = targetGroupIds,
            accounts = accounts,
            selectedAccountRef = selectedAccountRef,
            selectedAccountIdHex = selectedAccount?.accountIdHex,
            isLoading = dataSource.isLoading,
            error = dataSource.error,
            memberSnapshotsRevision = dataSource.memberSnapshotsRevision,
            selectedAccountRefState = selectionState.selectedAccountRefState,
            accountController = dataSource.controller,
            retryLoadAction = dataSource.retryLoad,
            previewText = payload.text?.trim().orEmpty(),
            attachmentCount = payload.streamUris.size,
            queryState = selectionState.queryState,
            selectedState = selectionState.selectedState,
            searchFocusedState = selectionState.searchFocusedState,
            accountSelectorOpenState = selectionState.accountSelectorOpenState,
        )
    }
}

@Composable
private fun ShareTargetRow(
    item: ChatListItem,
    title: String,
    selected: Boolean,
    selectedAccountIdHex: String?,
    ownerAccountRef: String?,
    appState: WhiteNoiseAppState,
    onToggle: (String) -> Unit,
) {
    val groupId = item.group.groupIdHex
    val avatarAccount = forwardTargetAvatarAccount(item)
    val memberIds =
        remember(item, selectedAccountIdHex) {
            item.memberSnapshot
                ?.members
                .orEmpty()
                .map { it.memberIdHex }
                .filter { it.isNotBlank() && it != selectedAccountIdHex }
                .distinct()
        }
    val memberRevisions = memberIds.map(appState::profileAccountRevisionForCompose)
    val membersPreview =
        remember(item, ownerAccountRef, memberRevisions) {
            forwardTargetMembersPreview(item, selectedAccountIdHex) { memberIdHex ->
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
