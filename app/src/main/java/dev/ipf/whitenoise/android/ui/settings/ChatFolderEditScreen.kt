package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.deriveRecipientCandidates
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.util.Locale

internal const val CHAT_FOLDER_EDIT_CONTENT_TAG = "chat-folder-edit-content"

/**
 * Create/edit form for one chat folder: name and description, Included Chats, the automatic rules (People,
 * Keyword, four switches) and a live Preview. Nothing persists until Save; Back asks before discarding a dirty draft,
 * and a failed save keeps every field (M027, M028).
 */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
internal fun ChatFolderEditScreen(
    appState: WhiteNoiseAppState,
    accountRef: String,
    folderId: String?,
    onClose: () -> Unit,
    initialManualChatIds: Set<String> = emptySet(),
) {
    val store = appState.chatFolderPreferences
    val storeState by store.state.collectAsState()
    val existing =
        remember(folderId, storeState) {
            folderId?.let { id -> store.foldersFor(accountRef).firstOrNull { it.id == id } }
        }
    val existingRule = remember(folderId) { folderId?.let { store.folderRule(accountRef, it) } }
    // An un-renamed default stores "" and renders a localized label — prefill
    // that label so editing it doesn't demand a name the user already sees.
    val prefillName = existing?.let { chatFolderDisplayName(it) }.orEmpty()
    val name = rememberTextFieldState(prefillName)
    val description = rememberTextFieldState(existing?.description.orEmpty())
    val keyword = rememberTextFieldState(existingRule?.keyword.orEmpty())
    var unreadOnly by rememberSaveable { mutableStateOf(existingRule?.unreadOnly ?: false) }
    var includeMuted by rememberSaveable { mutableStateOf(existingRule?.includeMuted ?: false) }
    var groupsOnly by rememberSaveable { mutableStateOf(existingRule?.groupsOnly ?: false) }
    var archivedOnly by rememberSaveable { mutableStateOf(existingRule?.archivedOnly ?: false) }
    val initialManual =
        remember {
            folderId?.let { store.membershipFor(accountRef, it) }
                ?: initialManualChatIds.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        }
    var manualChatIds by remember { mutableStateOf<Set<String>>(initialManual) }
    var memberHexes by remember { mutableStateOf(existingRule?.includeMemberPubkeys ?: emptySet()) }
    var picker by rememberSaveable { mutableStateOf<FolderPicker?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }

    val rule =
        ChatFolderRule(
            includeMemberPubkeys = memberHexes,
            unreadOnly = unreadOnly,
            includeMuted = includeMuted,
            keyword =
                keyword.text
                    .toString()
                    .trim()
                    .takeIf { it.isNotBlank() },
            groupsOnly = groupsOnly,
            archivedOnly = archivedOnly,
        )
    val initialRule = existingRule ?: ChatFolderRule()
    val missing = folderId != null && existing == null
    val dirty =
        name.text.toString() != prefillName ||
            description.text.toString() != existing?.description.orEmpty() ||
            rule != initialRule ||
            manualChatIds != initialManual

    fun back() {
        if (dirty) discard = true else onClose()
    }

    BackHandler(onBack = ::back)

    fun save() {
        val trimmedName =
            name.text
                .toString()
                .trim()
                .takeIf { it.isNotEmpty() } ?: return
        val trimmedDescription = description.text.toString().trim()
        // An untouched prefill on an un-renamed default is not a rename:
        // persisting it would freeze the localized label into the store and
        // the folder would stop following locale changes.
        val renamed = existing?.name.orEmpty().isNotEmpty() || trimmedName != prefillName
        val id =
            folderId?.also {
                if (renamed) store.renameFolder(accountRef, it, trimmedName)
                store.editFolderDescription(accountRef, it, trimmedDescription)
            } ?: store.createFolder(accountRef, trimmedName, trimmedDescription)?.id
        if (id == null) {
            failed = true
            return
        }
        val before = store.membershipFor(accountRef, id)
        (manualChatIds - before).forEach { store.setChatInFolder(accountRef, id, it, included = true) }
        (before - manualChatIds).forEach { store.setChatInFolder(accountRef, id, it, included = false) }
        store.setFolderRule(accountRef, id, rule.takeIf { it != ChatFolderRule() })
        onClose()
    }

    val groupTitleCopy = rememberGroupTitleCopy()
    val activeHex = appState.activeAccount?.accountIdHex
    val profileRevision = appState.profileRevisionForCompose
    val source = if (archivedOnly) appState.archivedChatListItems else appState.chatListItems
    val chatRows =
        remember(appState.chatListItems, profileRevision, groupTitleCopy) {
            appState.chatListItems.map { item ->
                WhiteNoisePickerItem(
                    id = item.id.lowercase(Locale.ROOT),
                    title = chatListItemDisplayTitle(item, appState, groupTitleCopy),
                    avatarSeed = item.id,
                )
            }
        }
    val memberRows =
        remember(appState.chatListItems, activeHex, profileRevision) {
            deriveRecipientCandidates(appState, activeHex).map { candidate ->
                WhiteNoisePickerItem(
                    id = candidate.accountIdHex.lowercase(Locale.ROOT),
                    title = candidate.displayName,
                    avatarSeed = candidate.accountIdHex,
                    avatarUrl = appState.avatarUrl(candidate.accountIdHex),
                )
            }
        }
    val previewRows =
        remember(source, manualChatIds, rule, activeHex, profileRevision, groupTitleCopy) {
            val ids =
                chatFolderChatIds(
                    items = source,
                    manualChatIds = manualChatIds,
                    rule = rule.takeIf { it != ChatFolderRule() },
                    activeAccountIdHex = activeHex,
                    isMuted = { groupIdHex -> source.any { it.group.groupIdHex == groupIdHex && it.engineMuted() } },
                    displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                )
            source
                .filter { it.group.groupIdHex.lowercase(Locale.ROOT) in ids }
                .map { item ->
                    WhiteNoisePickerItem(
                        id = item.id.lowercase(Locale.ROOT),
                        title = chatListItemDisplayTitle(item, appState, groupTitleCopy),
                        avatarSeed = item.id,
                    )
                }
        }

    ChatFolderEditContent(
        state =
            ChatFolderEditFormState(
                isNew = folderId == null,
                name = name,
                description = description,
                keyword = keyword,
                unreadOnly = unreadOnly,
                includeMuted = includeMuted,
                groupsOnly = groupsOnly,
                archivedOnly = archivedOnly,
                manualChatCount = manualChatIds.size,
                peopleCount = memberHexes.size,
                previewCount = previewRows.size,
                canSave = name.text.isNotBlank() && !missing,
                error =
                    when {
                        missing -> stringResource(R.string.folder_unavailable)
                        failed -> stringResource(R.string.folder_save_failed)
                        else -> null
                    },
            ),
        onUnreadOnlyChange = { unreadOnly = it },
        onIncludeMutedChange = { includeMuted = it },
        onGroupsOnlyChange = { groupsOnly = it },
        onArchivedOnlyChange = { archivedOnly = it },
        onOpenManualChats = { picker = FolderPicker.Chats },
        onOpenPeople = { picker = FolderPicker.People },
        onOpenPreview = { picker = FolderPicker.Preview },
        onSave = ::save,
        onBack = ::back,
    )

    if (discard) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.folder_discard_title)) },
            text = { Text(stringResource(R.string.folder_discard_detail)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.folder_discard)) } },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(stringResource(R.string.folder_keep_editing)) }
            },
        )
    }
    picker?.let { mode ->
        WhiteNoiseEntityPickerSheet(
            title =
                stringResource(
                    when (mode) {
                        FolderPicker.People -> R.string.chat_folder_people
                        FolderPicker.Preview -> R.string.folder_preview
                        FolderPicker.Chats -> R.string.chat_folder_manual_chats
                    },
                ),
            items =
                when (mode) {
                    FolderPicker.People -> memberRows
                    FolderPicker.Preview -> previewRows
                    FolderPicker.Chats -> chatRows
                },
            onDismiss = { picker = null },
            onSelect =
                when (mode) {
                    FolderPicker.Preview -> null
                    FolderPicker.People -> { hex -> memberHexes = memberHexes.toggled(hex) }
                    FolderPicker.Chats -> { id -> manualChatIds = manualChatIds.toggled(id) }
                },
            selectedIds = if (mode == FolderPicker.People) memberHexes else manualChatIds,
            multiple = true,
            onDone = { picker = null },
            searchTag = "folder.pickerSearch",
            rowTagPrefix = "folder.choice",
        )
    }
}

/** Everything the editor renders; the text fields are shared state so typing needs no round trip. */
internal data class ChatFolderEditFormState(
    val isNew: Boolean,
    val name: TextFieldState,
    val description: TextFieldState,
    val keyword: TextFieldState,
    val unreadOnly: Boolean,
    val includeMuted: Boolean,
    val groupsOnly: Boolean,
    val archivedOnly: Boolean,
    val manualChatCount: Int,
    val peopleCount: Int,
    val previewCount: Int,
    val canSave: Boolean,
    val error: String? = null,
)

/** The form without any store access, so tests can render every draft. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun ChatFolderEditContent(
    state: ChatFolderEditFormState,
    onUnreadOnlyChange: (Boolean) -> Unit,
    onIncludeMutedChange: (Boolean) -> Unit,
    onGroupsOnlyChange: (Boolean) -> Unit,
    onArchivedOnlyChange: (Boolean) -> Unit,
    onOpenManualChats: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPreview: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(if (state.isNew) R.string.chat_folder_new else R.string.chat_folder_edit_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
        topBarActions = {
            TextButton(enabled = state.canSave, onClick = onSave) { Text(stringResource(R.string.save)) }
        },
    ) {
        SettingsList(modifier = Modifier.testTag(CHAT_FOLDER_EDIT_CONTENT_TAG)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                ) {
                    WhiteNoiseTextField(
                        state = state.name,
                        modifier = Modifier.fillMaxWidth().testTag("folder.name"),
                        label = { Text(stringResource(R.string.chat_folder_name)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                    WhiteNoiseTextField(
                        state = state.description,
                        modifier = Modifier.fillMaxWidth().testTag("folder.description"),
                        label = { Text(stringResource(R.string.chat_folder_description_label)) },
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 4),
                    )
                    state.error?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            item {
                SettingsGroup {
                    row("chats") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_folder_manual_chats),
                            onClick = onOpenManualChats,
                            value = state.manualChatCount.toString(),
                        )
                    }
                }
                SettingsExplainer(stringResource(R.string.folder_manual_hint))
                SettingsSection(stringResource(R.string.folder_rules))
                SettingsGroup {
                    row("people") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_folder_people),
                            onClick = onOpenPeople,
                            value = state.peopleCount.toString(),
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)) {
                    WhiteNoiseTextField(
                        state = state.keyword,
                        modifier = Modifier.fillMaxWidth().testTag("folder.keyword"),
                        label = { Text(stringResource(R.string.chat_folder_keyword_label)) },
                        supportingText = { Text(stringResource(R.string.folder_keyword_hint)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                }
            }
            item {
                SettingsGroup {
                    row("unread") { context ->
                        val title = stringResource(R.string.chat_folder_unread_only)
                        SettingsSwitch(context, title, state.unreadOnly, onUnreadOnlyChange)
                    }
                    row("groups") { context ->
                        val title = stringResource(R.string.chat_folder_groups_only)
                        SettingsSwitch(context, title, state.groupsOnly, onGroupsOnlyChange)
                    }
                    row("archived") { context ->
                        val title = stringResource(R.string.chat_folder_archived_only)
                        SettingsSwitch(context, title, state.archivedOnly, onArchivedOnlyChange)
                    }
                    row("muted") { context ->
                        val title = stringResource(R.string.chat_folder_include_muted)
                        SettingsSwitch(context, title, state.includeMuted, onIncludeMutedChange)
                    }
                }
                SettingsExplainer(stringResource(R.string.folder_rule_hint))
                SettingsSection(stringResource(R.string.folder_preview))
                SettingsGroup {
                    row("preview") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.folder_preview),
                            onClick = onOpenPreview,
                            subtitle =
                                pluralStringResource(
                                    R.plurals.chat_folder_chat_count,
                                    state.previewCount,
                                    state.previewCount,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Adds [id] when absent and removes it when present. */
private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/** Which picker sheet is open. */
private enum class FolderPicker { Chats, People, Preview }
