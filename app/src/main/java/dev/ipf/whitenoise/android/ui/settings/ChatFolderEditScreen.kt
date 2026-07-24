package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.localeInvariantFold
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.deriveRecipientCandidates
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.sectionPanelColor
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import java.util.Locale

/**
 * Create/edit form for one custom chat folder: name, description, manual
 * chat selection, and the automatic rule (people, keyword, unread-only,
 * include-muted). Nothing persists until Save, which writes the folder,
 * diffs the manual membership, and stores the rule (or clears it when every
 * rule field is at its default).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val existing =
        remember(folderId) {
            folderId?.let { id -> store.foldersFor(accountRef).firstOrNull { it.id == id } }
        }
    val existingRule = remember(folderId) { folderId?.let { store.folderRule(accountRef, it) } }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var keyword by remember { mutableStateOf(existingRule?.keyword.orEmpty()) }
    var unreadOnly by remember { mutableStateOf(existingRule?.unreadOnly ?: false) }
    var includeMuted by remember { mutableStateOf(existingRule?.includeMuted ?: false) }
    var manualChatIds by
        remember {
            mutableStateOf(
                folderId?.let { store.membershipFor(accountRef, it) }
                    ?: initialManualChatIds.mapTo(HashSet()) { it.lowercase(Locale.ROOT) },
            )
        }
    var memberHexes by remember { mutableStateOf(existingRule?.includeMemberPubkeys ?: emptySet()) }
    var showChatPicker by remember { mutableStateOf(false) }
    var showMemberPicker by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    fun save() {
        val trimmedName = name.trim().takeIf { it.isNotEmpty() } ?: return
        val trimmedDescription = description.trim()
        val id =
            folderId?.also {
                store.renameFolder(accountRef, it, trimmedName)
                store.editFolderDescription(accountRef, it, trimmedDescription)
            } ?: store.createFolder(accountRef, trimmedName, trimmedDescription)?.id
        if (id != null) {
            val before = store.membershipFor(accountRef, id)
            (manualChatIds - before).forEach { store.setChatInFolder(accountRef, id, it, included = true) }
            (before - manualChatIds).forEach { store.setChatInFolder(accountRef, id, it, included = false) }
            val rule =
                ChatFolderRule(
                    includeMemberPubkeys = memberHexes,
                    unreadOnly = unreadOnly,
                    includeMuted = includeMuted,
                    keyword = keyword.trim().takeIf { it.isNotBlank() },
                )
            // An all-defaults rule is inert — store nothing so the folder
            // reads back exactly like a pre-rules manual folder.
            store.setFolderRule(accountRef, id, rule.takeIf { it != ChatFolderRule() })
        }
        onClose()
    }

    val groupTitleCopy = rememberGroupTitleCopy()
    val activeHex = appState.activeAccount?.accountIdHex
    val chatRows =
        remember(appState.chatListItems, appState.profileRevisionForCompose, groupTitleCopy) {
            appState.chatListItems.map { item ->
                FolderPickRow(
                    id = item.id.lowercase(Locale.ROOT),
                    title = chatListItemDisplayTitle(item, appState, groupTitleCopy),
                    subtitle = null,
                    avatarSeed = item.id,
                )
            }
        }
    val memberRows =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose) {
            deriveRecipientCandidates(appState, activeHex).map { candidate ->
                FolderPickRow(
                    id = candidate.accountIdHex.lowercase(Locale.ROOT),
                    title = candidate.displayName,
                    subtitle = IdentityFormatter.short(candidate.npub),
                    avatarSeed = candidate.accountIdHex,
                    avatarUrl = appState.avatarUrl(candidate.accountIdHex),
                )
            }
        }
    val selectedPeople =
        remember(memberHexes, appState.profileRevisionForCompose) {
            memberHexes.joinToString(", ") { appState.displayName(it) }
        }
    val canSave = name.isNotBlank()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (folderId == null) R.string.chat_folder_new else R.string.chat_folder_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Button(
                    onClick = { save() },
                    enabled = canSave,
                    shape = RoundedCornerShape(18.dp),
                    modifier =
                        Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd)
                            .height(56.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(Dimens.spaceSm))
                    Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            FolderEditorIntro()
            FolderEditorPanel {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text(stringResource(R.string.chat_folder_name_label)) },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    label = { Text(stringResource(R.string.chat_folder_description_label)) },
                )
            }
            FolderEditorActionCard(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(R.string.chat_folder_manual_chats),
                value = pluralStringResource(R.plurals.chat_folder_chat_count, manualChatIds.size, manualChatIds.size),
                onClick = { showChatPicker = true },
            )
            FolderEditorActionCard(
                icon = Icons.Default.People,
                title = stringResource(R.string.chat_folder_people),
                value = selectedPeople.ifEmpty { stringResource(R.string.chat_folder_people_subtitle) },
                onClick = { showMemberPicker = true },
            )
            FolderEditorPanel {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    label = { Text(stringResource(R.string.chat_folder_keyword_label)) },
                    supportingText = { Text(stringResource(R.string.chat_folder_keyword_hint)) },
                )
                FolderRuleToggle(
                    title = stringResource(R.string.chat_folder_unread_only),
                    subtitle = stringResource(R.string.chat_folder_unread_only_subtitle),
                    checked = unreadOnly,
                    onCheckedChange = { unreadOnly = it },
                )
                FolderRuleToggle(
                    title = stringResource(R.string.chat_folder_include_muted),
                    subtitle = null,
                    checked = includeMuted,
                    onCheckedChange = { includeMuted = it },
                )
            }
        }
    }

    if (showChatPicker) {
        FolderMultiSelectSheet(
            title = stringResource(R.string.chat_folder_manual_chats),
            searchPlaceholder = stringResource(R.string.chat_list_search_hint),
            rows = chatRows,
            selectedIds = manualChatIds,
            onToggle = { id ->
                manualChatIds = if (id in manualChatIds) manualChatIds - id else manualChatIds + id
            },
            onDismiss = { showChatPicker = false },
        )
    }
    if (showMemberPicker) {
        FolderMultiSelectSheet(
            title = stringResource(R.string.chat_folder_people),
            searchPlaceholder = stringResource(R.string.search_people_hint),
            rows = memberRows,
            selectedIds = memberHexes,
            onToggle = { hex ->
                memberHexes = if (hex in memberHexes) memberHexes - hex else memberHexes + hex
            },
            onDismiss = { showMemberPicker = false },
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun FolderEditorIntro() {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(shape),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.spaceLg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(
                    stringResource(R.string.chat_folders_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.chat_folders_settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun FolderEditorPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(shape),
        shape = shape,
        colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
            content = content,
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun FolderEditorActionCard(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(shape),
        shape = shape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.spaceLg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun FolderRuleToggle(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerColor by
        animateColorAsState(
            targetValue =
                if (checked) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            label = "folderRuleContainer",
        )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

internal data class FolderPickRow(
    val id: String,
    val title: String,
    val subtitle: String?,
    val avatarSeed: String,
    val avatarUrl: String? = null,
)

/** Searchable multi-select bottom sheet shared by the chat and people pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun FolderMultiSelectSheet(
    title: String,
    searchPlaceholder: String,
    rows: List<FolderPickRow>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(rows, query) {
            val needle = localeInvariantFold(query.trim())
            if (needle.isEmpty()) rows else rows.filter { needle in localeInvariantFold(it.title) }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
            FlowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = searchPlaceholder,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(filtered, key = { it.id }) { row ->
                    ListItem(
                        modifier = Modifier.clickable { onToggle(row.id) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Avatar(title = row.title, seed = row.avatarSeed, size = 40.dp, pictureUrl = row.avatarUrl)
                        },
                        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent =
                            row.subtitle?.let {
                                { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                        trailingContent = {
                            Checkbox(checked = row.id in selectedIds, onCheckedChange = null)
                        },
                    )
                }
            }
        }
    }
}
