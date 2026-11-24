@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.BidiFormatter
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isForwardOwnerSignedIn
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.state.requestForwardTargetMembers
import dev.ipf.whitenoise.android.ui.chats.ChatListPinnedBoundary
import dev.ipf.whitenoise.android.ui.chats.pinnedBoundaryIndex
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.BottomPanelSurface
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSearchCollapse
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoffHost
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.VISIBLE_GROUP_AVATAR_LIMIT
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBarNavigation
import dev.ipf.whitenoise.android.ui.common.controlHandoffEndpoint
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.rememberSearchCollapse
import dev.ipf.whitenoise.android.ui.common.searchCollapseHost
import dev.ipf.whitenoise.android.ui.common.searchExpandReveal
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.share.DestinationFilterIncomplete
import dev.ipf.whitenoise.android.ui.share.DestinationFolderFilterState
import dev.ipf.whitenoise.android.ui.share.DestinationFolderFilters
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerAccountSheet
import dev.ipf.whitenoise.android.ui.share.destinationFolderInputsComplete
import dev.ipf.whitenoise.android.ui.share.rememberDestinationFolderRows
import dev.ipf.whitenoise.android.ui.share.rememberShareChatPickerDataSource
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import java.util.Locale

internal const val FORWARD_CHAT_PICKER_SCREEN_TEST_TAG = "forward_chat_picker_full_screen"
internal const val FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG = "forward_chat_picker_account_row"

/** Mirrors the picker's live destination account, selection, and selected-chat titles. */
internal typealias PickerStateListener =
    (destinationAccountRef: String?, selectedGroupIds: List<String>, targetTitles: Map<String, String>) -> Unit

/** Full-screen target selection keeps row taps out of a draggable sheet gesture arena. */
@Composable
@Suppress("LongParameterList")
internal fun ForwardMessagePickerFullScreen(
    appState: WhiteNoiseAppState,
    messageCount: Int,
    attachmentCount: Int,
    originGroupIdHex: String,
    sourceAccountRef: String?,
    onDismiss: () -> Unit,
    onForward: (destinationAccountRef: String, targetGroupIds: List<String>) -> Boolean,
    origin: Rect? = null,
    initialDestinationAccountRef: String? = null,
    initialSelectedGroupIds: List<String> = emptyList(),
    onPickerStateChanged: PickerStateListener = { _, _, _ -> },
    controllerFactory: (WhiteNoiseAppState) -> ChatsController = { ChatsController(it) },
    controllerBinder: suspend (ChatsController, String) -> Unit = { controller, accountRef ->
        controller.bind(accountRef)
    },
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var dismissing by remember { mutableStateOf(false) }
    val dismissPicker = {
        if (!dismissing) {
            dismissing = true
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onDismiss()
        }
    }
    MeasuredPlaybackDialog(
        origin = origin,
        onDismissStart = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        },
        onDismissRequest = dismissPicker,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        ForwardMessagePickerContent(
            appState = appState,
            messageCount = messageCount,
            attachmentCount = attachmentCount,
            originGroupIdHex = originGroupIdHex,
            sourceAccountRef = sourceAccountRef,
            onDismiss = dismiss,
            onForward = onForward,
            initialDestinationAccountRef = initialDestinationAccountRef,
            initialSelectedGroupIds = initialSelectedGroupIds,
            onPickerStateChanged = onPickerStateChanged,
            controllerFactory = controllerFactory,
            controllerBinder = controllerBinder,
        )
    }
}

/**
 * Destination-account selection for one forwarding request. The selected
 * account defaults to the source conversation's owner, its chat list is
 * loaded only from that account (through a separate account-scoped controller
 * when it is not the active account), and chat selections are keyed to the
 * account that produced them: switching accounts always clears them.
 */
private class ForwardDestinationState(
    val accounts: List<AccountSummaryFfi>,
    val selectedAccountRef: String?,
    val selectedAccountIdHex: String?,
)

/** Owns the saveable destination-account choice and its signed-in fallback. */
@Composable
private fun rememberForwardDestinationState(
    appState: WhiteNoiseAppState,
    originGroupIdHex: String,
    sourceAccountRef: String?,
    initialDestinationAccountRef: String?,
    selectedGroups: androidx.compose.runtime.MutableState<ArrayList<String>>,
): Pair<ForwardDestinationState, androidx.compose.runtime.MutableState<String?>> {
    val accounts = appState.accounts.filter(AccountSummaryFfi::isSignedInSigningAccount)

    /** Keeps a candidate only while it names a signed-in signing account. */
    fun valid(candidate: String?): String? = candidate?.takeIf { ref -> accounts.any { it.label == ref } }
    val defaultAccountRef =
        valid(sourceAccountRef) ?: valid(appState.activeAccountRef) ?: accounts.firstOrNull()?.label
    // Plain remember on purpose: process recreation restores through the
    // encrypted pending-request store, and account identifiers must never be
    // serialized into the plain saved-state Bundle.
    val selectedAccountRefState =
        remember(originGroupIdHex) {
            mutableStateOf(valid(initialDestinationAccountRef) ?: defaultAccountRef)
        }
    val selectedAccountRef = valid(selectedAccountRefState.value) ?: defaultAccountRef
    LaunchedEffect(selectedAccountRef, selectedAccountRefState.value) {
        // A signed-out selection falls back to a valid owner; selections made
        // under the vanished account can never silently transfer to it.
        if (selectedAccountRefState.value != selectedAccountRef) {
            selectedAccountRefState.value = selectedAccountRef
            selectedGroups.value = arrayListOf()
        }
    }
    val selectedAccount = accounts.firstOrNull { it.label == selectedAccountRef }
    return ForwardDestinationState(
        accounts = accounts,
        selectedAccountRef = selectedAccountRef,
        selectedAccountIdHex = selectedAccount?.accountIdHex,
    ) to selectedAccountRefState
}

/**
 * Picker body: compact source summary, sending-avatar account sheet,
 * account-scoped search/folders/chat rows, and the confirm bar that binds the
 * explicit destination account to the accepted selection. Folder preference
 * changes refresh the current account's shortcuts while the picker stays open.
 * The shared top bar leads with Close, which always closes the picker; system Back closes an open search first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun ForwardMessagePickerContent(
    appState: WhiteNoiseAppState,
    messageCount: Int,
    attachmentCount: Int,
    originGroupIdHex: String,
    sourceAccountRef: String?,
    onDismiss: () -> Unit,
    onForward: (destinationAccountRef: String, targetGroupIds: List<String>) -> Boolean,
    initialDestinationAccountRef: String? = null,
    initialSelectedGroupIds: List<String> = emptyList(),
    onPickerStateChanged: PickerStateListener = { _, _, _ -> },
    controllerFactory: (WhiteNoiseAppState) -> ChatsController = { ChatsController(it) },
    controllerBinder: suspend (ChatsController, String) -> Unit = { controller, accountRef ->
        controller.bind(accountRef)
    },
) {
    val titleCopy = rememberGroupTitleCopy()
    // Plain remember throughout: the encrypted pending-request store owns
    // recreation, so chat and account identifiers stay out of the plain
    // saved-state Bundle. Selections restored for a destination that is no
    // longer signed in are dropped rather than re-owned by the fallback.
    var query by remember(originGroupIdHex) { mutableStateOf("") }
    var searchOpen by remember(originGroupIdHex) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = {
        query = ""
        searchOpen = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    BackHandler(searchOpen, onBack = closeSearch)
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }
    val searchCollapse = rememberSearchCollapse(searchOpen)
    val searchHandoff = rememberMeasuredControlHandoff(originGroupIdHex, searchOpen)
    val selectedState =
        remember(originGroupIdHex) {
            val seedIsSafe =
                initialDestinationAccountRef == null ||
                    appState.isForwardOwnerSignedIn(initialDestinationAccountRef)
            mutableStateOf(ArrayList(if (seedIsSafe) initialSelectedGroupIds else emptyList()))
        }
    var selected by selectedState
    var startFailed by remember(originGroupIdHex) { mutableStateOf(false) }
    var accountSelectorOpen by remember(originGroupIdHex) { mutableStateOf(false) }
    val (destination, selectedAccountRefState) =
        rememberForwardDestinationState(
            appState = appState,
            originGroupIdHex = originGroupIdHex,
            sourceAccountRef = sourceAccountRef,
            initialDestinationAccountRef = initialDestinationAccountRef,
            selectedGroups = selectedState,
        )
    val dataSource =
        rememberShareChatPickerDataSource(
            appState = appState,
            selectedAccountRef = destination.selectedAccountRef,
            controllerFactory = controllerFactory,
            controllerBinder = controllerBinder,
        )
    val targetLoading = dataSource.isLoading
    val targetError = dataSource.error
    val memberRevision = dataSource.memberSnapshotsRevision
    val targets =
        remember(dataSource.targets, dataSource.targetsRevision, originGroupIdHex) {
            dataSource.targets
                .filterNot { it.group.groupIdHex.equals(originGroupIdHex, ignoreCase = true) }
        }
    val targetIds = remember(targets) { targets.mapTo(hashSetOf()) { it.group.groupIdHex.lowercase(Locale.ROOT) } }
    LaunchedEffect(destination.selectedAccountRef, targetLoading, targetIds) {
        if (!targetLoading) {
            selected = ArrayList(selected.filter(targetIds::contains))
            if (dataSource.controller != null) {
                dataSource.controller.requestMemberSnapshots(targetIds)
            } else {
                appState.requestForwardTargetMembers(targetIds)
            }
        }
    }
    val titleOwner = destination.selectedAccountRef
    val titledTargets =
        remember(targets, titleOwner, titleCopy, memberRevision, appState.profileRevisionForCompose) {
            targets.map {
                it to forwardTargetDisplayTitle(it, appState, titleOwner, titleCopy)
            }
        }
    LaunchedEffect(destination.selectedAccountRef, selected, titledTargets) {
        val selectedSet = selected.toHashSet()
        val selectedTitles =
            titledTargets
                .filter { (item, _) -> item.group.groupIdHex.lowercase(Locale.ROOT) in selectedSet }
                .associate { (item, title) -> item.group.groupIdHex.lowercase(Locale.ROOT) to title }
        onPickerStateChanged(destination.selectedAccountRef, selected.toList(), selectedTitles)
    }
    val filter = remember(destination.selectedAccountRef, originGroupIdHex) { DestinationFolderFilterState() }
    val folderRows =
        rememberDestinationFolderRows(
            appState,
            targets,
            destination.selectedAccountRef,
            destination.selectedAccountIdHex,
            memberRevision,
        )
    val folderComplete =
        dataSource.targetsComplete &&
            destinationFolderInputsComplete(
                appState,
                targets,
                destination.selectedAccountRef,
                filter.folderId,
                folderRows,
            )
    val filteredTargets =
        remember(titledTargets, query, folderRows, filter.folderId, filter.reviewingSelected, selected) {
            val needle = query.trim()
            titledTargets.filter { (item, title) ->
                (needle.isEmpty() || title.contains(needle, ignoreCase = true)) &&
                    filter.accepts(item.group.groupIdHex, folderRows, selected)
            }
        }
    val sendingAccount = destination.accounts.firstOrNull { it.label == destination.selectedAccountRef }
    val forwardTitle = stringResource(R.string.forward)

    WhiteNoiseScaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG)
                .semantics {
                    isTraversalGroup = true
                    paneTitle = forwardTitle
                },
        containerColor = amoledSheetContainerColor(),
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            // The scaffold places app banners below this bar rather than over it (#3312).
            MeasuredControlHandoffHost(
                state = searchHandoff,
                destination = searchOpen,
                outlineVisible = { searchOpen },
            ) {
                WhiteNoiseTopBar(
                    title = forwardTitle,
                    onBack = onDismiss,
                    modifier = Modifier.searchCollapseHost(searchCollapse),
                    containerColor = amoledSheetContainerColor(),
                    navigation = WhiteNoiseTopBarNavigation.Close,
                    titleContent = {
                        if (searchOpen) {
                            CompositionLocalProvider(LocalSearchCollapse provides searchCollapse) {
                                WhiteNoiseCompactSearchField(
                                    value = query,
                                    onValueChange = {
                                        query = it
                                        startFailed = false
                                    },
                                    placeholder = stringResource(R.string.forward_search_chats),
                                    clearDescription = stringResource(R.string.chat_list_search_clear),
                                    outlined = true,
                                    onClear = closeSearch,
                                    emptyTrailingIcon = {
                                        IconButton(onClick = closeSearch) {
                                            Icon(
                                                painterResource(R.drawable.ic_close),
                                                stringResource(R.string.conversation_search_close),
                                            )
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .searchExpandReveal(query)
                                            .controlHandoffEndpoint(searchHandoff, true)
                                            .focusRequester(searchFocus)
                                            .testTag("forward.searchField"),
                                )
                            }
                        } else {
                            Column {
                                Text(
                                    forwardTitle,
                                    modifier = Modifier.testTag("forward.title"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ForwardSelectionSummary(messageCount, attachmentCount)
                            }
                        }
                    },
                    actions = {
                        if (!searchOpen) {
                            IconButton(
                                onClick = { searchOpen = true },
                                modifier = Modifier.controlHandoffEndpoint(searchHandoff, false),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_search),
                                    contentDescription = stringResource(R.string.forward_search_chats),
                                )
                            }
                        }
                    },
                    navigationAccessory = {
                        sendingAccount?.let { account ->
                            val name = appState.networkDisplayName(account.accountIdHex)
                            val description = stringResource(R.string.share_sending_as_value, name)
                            val avatarModifier =
                                Modifier
                                    .size(48.dp)
                                    .testTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG)
                                    .semantics { contentDescription = description }
                            if (destination.accounts.size > 1) {
                                IconButton(
                                    onClick = { accountSelectorOpen = true },
                                    modifier = avatarModifier,
                                ) {
                                    Avatar(
                                        name,
                                        account.accountIdHex,
                                        size = 32.dp,
                                        pictureUrl = appState.avatarUrl(account.accountIdHex),
                                        pictureDomain = AvatarImageLoader.publicProfiles(),
                                    )
                                }
                            } else {
                                Box(avatarModifier, contentAlignment = Alignment.Center) {
                                    Avatar(
                                        name,
                                        account.accountIdHex,
                                        size = 32.dp,
                                        pictureUrl = appState.avatarUrl(account.accountIdHex),
                                        pictureDomain = AvatarImageLoader.publicProfiles(),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            BottomPanelSurface(Modifier.navigationBarsPadding().imePadding()) {
                Box(Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd)) {
                    Button(
                        onClick = {
                            val destinationAccountRef =
                                destination.selectedAccountRef?.takeIf { appState.isForwardOwnerSignedIn(it) }
                            val recipients = forwardRecipientGroupIds(selected, originGroupIdHex)
                            startFailed =
                                destinationAccountRef == null ||
                                !confirmForwardTargets(
                                    targets = recipients,
                                    start = { targetIds -> onForward(destinationAccountRef, targetIds) },
                                    dismiss = onDismiss,
                                )
                        },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("forward.confirm"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_forward),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selected.isEmpty()) {
                                stringResource(R.string.forward)
                            } else {
                                pluralStringResource(R.plurals.forward_to_chats_count, selected.size, selected.size)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                key(destination.selectedAccountRef) {
                    DestinationFolderFilters(
                        rows = folderRows,
                        state = filter,
                        selected = selected,
                        onReviewSelected = { query = "" },
                        modifier = Modifier.weight(1f),
                        alwaysShow = true,
                    )
                }
                if (filter.folderId != null || filter.reviewingSelected || query.isNotBlank()) {
                    ForwardVisibleSelectionAction(
                        selected = selected,
                        visibleIds =
                            filteredTargets.map {
                                it.first.group.groupIdHex
                                    .lowercase(Locale.ROOT)
                            },
                        complete = folderComplete && dataSource.targetsComplete,
                        onSelectionChange = { selected = ArrayList(it) },
                    )
                }
            }
            if (startFailed) {
                Text(
                    text = stringResource(R.string.forward_start_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(horizontal = Dimens.spaceLg).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
            ForwardTargetList(
                appState = appState,
                targets = targets,
                filteredTargets = filteredTargets,
                searching = query.isNotBlank(),
                targetLoading = targetLoading,
                targetsComplete = folderComplete,
                browsingFiltered = filter.folderId != null || filter.reviewingSelected || query.isNotBlank(),
                targetError = targetError,
                retryLoad = dataSource.retryLoad,
                ownerAccountRef = destination.selectedAccountRef,
                ownerAccountIdHex = destination.selectedAccountIdHex,
                selected = selected,
                onSelectionChange = { selected = ArrayList(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (accountSelectorOpen) {
        ShareChatPickerAccountSheet(
            appState = appState,
            accounts = destination.accounts,
            selectedAccountRef = destination.selectedAccountRef,
            onChooseAccount = { accountRef ->
                if (accountRef != destination.selectedAccountRef &&
                    destination.accounts.any { it.label == accountRef }
                ) {
                    selectedAccountRefState.value = accountRef
                    selected = arrayListOf()
                }
                accountSelectorOpen = false
            },
            onDismiss = { accountSelectorOpen = false },
        )
    }
}

@Composable
private fun ForwardSelectionSummary(
    messageCount: Int,
    attachmentCount: Int,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bidiFormatter = remember(rtl) { BidiFormatter.Builder(rtl).build() }
    val summary =
        buildList {
            add(pluralStringResource(R.plurals.forward_message_count, messageCount, messageCount))
            if (attachmentCount > 0) {
                add(pluralStringResource(R.plurals.forward_attachment_count, attachmentCount, attachmentCount))
            }
        }.joinToString(" · ") { bidiFormatter.unicodeWrap(it) }
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Destination rows and their loading/empty states share the remaining screen height. */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // Explicit loaded, partial and failed states.
private fun ForwardTargetList(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    filteredTargets: List<Pair<ChatListItem, String>>,
    searching: Boolean,
    targetLoading: Boolean,
    targetsComplete: Boolean,
    browsingFiltered: Boolean,
    targetError: ErrorPresentation?,
    retryLoad: () -> Unit,
    ownerAccountRef: String?,
    ownerAccountIdHex: String?,
    selected: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val awaitingRows = awaitingForwardFilterRows(targets.isEmpty(), filteredTargets.isEmpty(), browsingFiltered)
    PreparedGroupAvatarContent(
        appState,
        filteredTargets.take(VISIBLE_GROUP_AVATAR_LIMIT).mapNotNull { it.first.selectedAvatarAsset },
        ownerAccountRef,
    ) {
        WhiteNoiseLazyColumn(
            modifier = modifier.fillMaxWidth().testTag("forward.destinations"),
            contentPadding = PaddingValues(bottom = Dimens.spaceLg),
        ) {
            if (targetLoading && awaitingRows) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingScreen()
                    }
                }
            } else if (targetError != null && awaitingRows) {
                item {
                    Box(Modifier.fillParentMaxSize()) {
                        ErrorContent(
                            title = stringResource(R.string.couldnt_load_chats),
                            error = targetError,
                            onRetry = retryLoad,
                        )
                    }
                }
            } else if (
                targets.isEmpty() || filteredTargets.isEmpty()
            ) {
                item {
                    ForwardEmptyTargets(browsingFiltered, targetsComplete, targets.isEmpty(), retryLoad)
                }
            } else {
                targetError?.let { failure ->
                    item(key = "forward-picker-load-error") {
                        InlineErrorBanner(error = failure, onRetry = retryLoad)
                    }
                }
                if (browsingFiltered && !targetsComplete && filteredTargets.isNotEmpty()) {
                    item { DestinationFilterIncomplete(retryLoad) }
                }
                if (browsingFiltered && filteredTargets.isEmpty()) {
                    item {
                        if (!targetsComplete) {
                            DestinationFilterIncomplete(retryLoad)
                            return@item
                        }
                        Text(
                            stringResource(R.string.forward_no_matches),
                            modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                        )
                    }
                }
                val pinnedBoundary =
                    if (searching) {
                        null
                    } else {
                        pinnedBoundaryIndex(
                            filteredTargets.map { it.first.pinned() },
                            showArchived = false,
                        )
                    }
                items(filteredTargets, key = { (item, _) -> item.group.groupIdHex }) { (item, title) ->
                    if (
                        pinnedBoundary != null &&
                        filteredTargets[pinnedBoundary].first.group.groupIdHex == item.group.groupIdHex
                    ) {
                        ChatListPinnedBoundary()
                    }
                    ForwardTargetRow(
                        appState = appState,
                        item = item,
                        title = title,
                        ownerAccountRef = ownerAccountRef,
                        ownerAccountIdHex = ownerAccountIdHex,
                        selected = item.group.groupIdHex.lowercase(Locale.ROOT) in selected,
                        onToggle = { groupId ->
                            onSelectionChange(toggleForwardTargetSelection(selected, groupId))
                        },
                    )
                }
            }
        }
    }
}

/** Never treats an incomplete account read as evidence that a folder has no destinations. */
@Composable
private fun ForwardEmptyTargets(
    filtered: Boolean,
    complete: Boolean,
    targetsEmpty: Boolean,
    onRetry: () -> Unit,
) {
    if (filtered && !complete) {
        DestinationFilterIncomplete(onRetry)
    } else {
        Text(
            stringResource(if (targetsEmpty) R.string.forward_no_chats else R.string.forward_no_matches),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
        )
    }
}

/** One selectable destination chat row resolved through the owning account. */
@Composable
@Suppress("LongParameterList")
internal fun ForwardTargetRow(
    appState: WhiteNoiseAppState,
    item: ChatListItem,
    title: String,
    ownerAccountRef: String?,
    ownerAccountIdHex: String?,
    selected: Boolean,
    onToggle: (String) -> Unit,
) {
    // Use the actual home-row component, including its spacing, previews and badges.
    // Selection mode disables avatar navigation, swipe and long-press actions.
    dev.ipf.whitenoise.android.ui.chats.ChatRow(
        item = item,
        appState = appState,
        accountRef = ownerAccountRef,
        ownerAccountIdHex = ownerAccountIdHex,
        titleOverride = title,
        isMuted = item.engineMuted(),
        selectionMode = true,
        selected = selected,
        onClick = { onToggle(item.group.groupIdHex) },
        onOpenProfile = {},
    )
}

/** A loading/error projection cannot prove that the selected folder is empty. */
private fun awaitingForwardFilterRows(
    targetsEmpty: Boolean,
    filteredEmpty: Boolean,
    browsingFiltered: Boolean,
): Boolean = targetsEmpty || (browsingFiltered && filteredEmpty)
