@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun defaultSmartFolder() =
    SmartFolderFilter.Group(
        children =
            listOf(
                SmartFolderFilter.Condition(FolderField.ARCHIVED, FolderMode.NONE),
                SmartFolderFilter.Condition(FolderField.ACCEPTED),
            ),
    )

/** Immutable path edits keep the tree and unsaved form separate from the folder store. */
@Suppress("ReturnCount") // Missing paths are stale edit callbacks; reject them without touching siblings.
internal fun SmartFolderFilter.Group.updateAt(
    path: List<Int>,
    replacement: SmartFolderFilter?,
): SmartFolderFilter.Group {
    if (path.isEmpty()) return replacement as? SmartFolderFilter.Group ?: this
    val index = path.first()
    if (index !in children.indices) return this
    val updated =
        if (path.size == 1) {
            replacement
        } else {
            (children[index] as? SmartFolderFilter.Group)?.updateAt(
                path.drop(1),
                replacement,
            ) ?: return this
        }
    return copy(
        children =
            children.flatMapIndexed {
                i,
                child,
                ->
                if (i != index) listOf(child) else listOfNotNull(updated)
            },
    )
}

internal fun SmartFolderFilter.Group.nodeAt(path: List<Int>): SmartFolderFilter? =
    if (path.isEmpty()) {
        this
    } else {
        children.getOrNull(path.first())?.let {
            if (path.size == 1) it else (it as? SmartFolderFilter.Group)?.nodeAt(path.drop(1))
        }
    }

internal fun SmartFolderFilter.nodeCount(): Int =
    when (this) {
        is SmartFolderFilter.Condition -> 1
        is SmartFolderFilter.Group -> 1 + children.sumOf { it.nodeCount() }
    }

@Composable
internal fun SmartFolderEditor(
    root: SmartFolderFilter.Group,
    people: List<WhiteNoisePickerItem>,
    resolveKey: suspend (String) -> String?,
    onChange: (SmartFolderFilter.Group) -> Unit,
) {
    // '+' means append to that group; otherwise the path identifies an existing condition.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    SmartFolderGroup(
        root,
        emptyList(),
        root,
        people,
        onChange,
        onEdit = { path, add -> editing = (if (add) "+" else "") + path.joinToString(",") },
    )
    Text(stringResource(R.string.smart_folder_limit), style = MaterialTheme.typography.bodySmall)
    editing?.let { token ->
        val add = token.startsWith("+")
        val path =
            token
                .removePrefix("+")
                .split(',')
                .filter(String::isNotEmpty)
                .map(String::toInt)
        val initial =
            if (add) {
                SmartFolderFilter.Condition(FolderField.UNREAD)
            } else {
                root.nodeAt(path) as? SmartFolderFilter.Condition
            }
        if (initial != null) {
            key(token) {
                SmartFolderConditionDialog(
                    initial,
                    people,
                    resolveKey,
                    onDismiss = {
                        editing = null
                    },
                    onRemove = {
                        if (!add) onChange(root.updateAt(path, null))
                        editing = null
                    },
                    onDone = { condition ->
                        val replacement =
                            if (add) {
                                val group = root.nodeAt(path) as? SmartFolderFilter.Group
                                group?.copy(children = group.children + condition)
                            } else {
                                condition
                            }
                        if (replacement != null) onChange(root.updateAt(path, replacement))
                        editing = null
                    },
                )
            }
        }
    }
}

@Composable
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // One bounded visual tree; each property edits in its own dialog.
private fun SmartFolderGroup(
    group: SmartFolderFilter.Group,
    path: List<Int>,
    root: SmartFolderFilter.Group,
    people: List<WhiteNoisePickerItem>,
    onChange: (SmartFolderFilter.Group) -> Unit,
    onEdit: (List<Int>, Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(path.isEmpty()) }
    val mode = stringResource(if (group.all) R.string.smart_folder_all else R.string.smart_folder_any)
    Card(Modifier.fillMaxWidth().testTag("folder.group." + path.joinToString("."))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (path.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (if (group.not) stringResource(R.string.smart_folder_not) + " · " else "") +
                            stringResource(R.string.smart_folder_group_summary, mode, group.children.size),
                    )
                }
            }
            if (expanded) {
                FolderChoice(
                    mode,
                    listOf(
                        true to stringResource(R.string.smart_folder_all),
                        false to stringResource(R.string.smart_folder_any),
                    ),
                    "folder.match." + path.joinToString("."),
                ) {
                    onChange(root.updateAt(path, group.copy(all = it)))
                }
                FolderChoice(
                    if (group.not) {
                        stringResource(R.string.smart_folder_not_hint)
                    } else {
                        stringResource(R.string.smart_folder_include_match)
                    },
                    listOf(
                        false to stringResource(R.string.smart_folder_include_match),
                        true to stringResource(R.string.smart_folder_not_hint),
                    ),
                    "folder.not." + path.joinToString("."),
                ) { onChange(root.updateAt(path, group.copy(not = it))) }
                if (group.children.isEmpty()) {
                    Text(
                        stringResource(
                            if (path.isEmpty()) R.string.smart_folder_empty else R.string.smart_folder_empty_group,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                group.children.forEachIndexed { index, child ->
                    when (child) {
                        is SmartFolderFilter.Group ->
                            SmartFolderGroup(
                                child,
                                path + index,
                                root,
                                people,
                                onChange,
                                onEdit,
                            )
                        is SmartFolderFilter.Condition ->
                            TextButton(
                                onClick = { onEdit(path + index, false) },
                                modifier =
                                    Modifier.fillMaxWidth().testTag(
                                        "folder.condition." + (path + index).joinToString("."),
                                    ),
                            ) {
                                Text(conditionSummary(child, people), modifier = Modifier.fillMaxWidth())
                            }
                    }
                }
                if (path.size < SmartFolderCodec.MAX_DEPTH && root.nodeCount() < SmartFolderCodec.MAX_NODES) {
                    TextButton(
                        onClick = {
                            onEdit(path, true)
                        },
                        modifier = Modifier.testTag("folder.add." + path.joinToString(".")),
                    ) {
                        Text(stringResource(R.string.smart_folder_add))
                    }
                }
                if (path.size < SmartFolderCodec.MAX_DEPTH - 1 &&
                    root.nodeCount() < SmartFolderCodec.MAX_NODES - 1
                ) {
                    TextButton(onClick = {
                        onChange(
                            root.updateAt(
                                path,
                                group.copy(children = group.children + SmartFolderFilter.Group()),
                            ),
                        )
                    }) { Text(stringResource(R.string.smart_folder_group)) }
                }
                if (path.isNotEmpty()) {
                    TextButton(
                        onClick = { onChange(root.updateAt(path, null)) },
                    ) { Text(stringResource(R.string.smart_folder_remove_group)) }
                }
            }
        }
    }
}

@Composable
private fun <T> FolderChoice(
    label: String,
    options: List<Pair<T, String>>,
    tag: String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

private const val KEY_PREVIEW_LENGTH = 12

@Composable
internal fun conditionSummary(
    condition: SmartFolderFilter.Condition,
    people: List<WhiteNoisePickerItem>,
): String {
    val field = stringResource(fieldLabel(condition.field))
    val detail =
        when (condition.field) {
            FolderField.PARTICIPANTS ->
                stringResource(modeLabel(condition.mode)) + ": " +
                    condition.values.joinToString { hex ->
                        people
                            .firstOrNull {
                                it.id == hex
                            }?.title ?: hex.take(KEY_PREVIEW_LENGTH) + "…"
                    }
            FolderField.TITLE -> condition.values.firstOrNull().orEmpty()
            else -> stringResource(modeLabel(condition.mode))
        }
    return (if (condition.not) stringResource(R.string.smart_folder_not) + " · " else "") + "$field: $detail"
}

@Composable
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // Transient form state and property controls share one dialog lifetime.
private fun SmartFolderConditionDialog(
    initial: SmartFolderFilter.Condition,
    people: List<WhiteNoisePickerItem>,
    resolveKey: suspend (String) -> String?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDone: (SmartFolderFilter.Condition) -> Unit,
) {
    var field by rememberSaveable { mutableStateOf(initial.field) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var not by rememberSaveable { mutableStateOf(initial.not) }
    var keys by rememberSaveable { mutableStateOf(initial.values.toList()) }
    val keyword =
        rememberTextFieldState(
            if (initial.field == FolderField.TITLE) initial.values.firstOrNull().orEmpty() else "",
        )
    val publicKey = rememberTextFieldState()
    var picking by rememberSaveable { mutableStateOf(false) }
    var invalidKey by rememberSaveable { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val modes =
        when (field) {
            FolderField.PARTICIPANTS -> listOf(FolderMode.ANY_OF, FolderMode.ALL_OF, FolderMode.EXCLUDES)
            FolderField.TYPE -> listOf(FolderMode.DIRECT, FolderMode.GROUP)
            FolderField.TITLE -> listOf(FolderMode.CONTAINS)
            else -> listOf(FolderMode.PRESENT, FolderMode.NONE)
        }
    val condition =
        SmartFolderFilter.Condition(
            field,
            mode,
            when (field) {
                FolderField.PARTICIPANTS -> keys.toSet()
                FolderField.TITLE -> setOf(keyword.text.toString().trim())
                else -> emptySet()
            },
            not,
        )
    val valid = SmartFolderCodec.valid(SmartFolderFilter.Group(children = listOf(condition)))
    if (picking) {
        val keyRows =
            people +
                keys
                    .filter { key ->
                        people.none {
                            it.id == key
                        }
                    }.map {
                        WhiteNoisePickerItem(
                            id = it,
                            title = it.take(16) + "…",
                            avatarSeed = it,
                        )
                    }
        WhiteNoiseEntityPickerSheet(
            title = stringResource(R.string.smart_folder_select_people),
            items = keyRows,
            selectedIds = keys.toSet(),
            multiple = true,
            onSelect = { id ->
                keys =
                    if (id in keys) {
                        keys - id
                    } else {
                        (keys + id).take(SmartFolderCodec.MAX_VALUES)
                    }
            },
            onDismiss = { picking = false },
            onDone = { picking = false },
        )
    } else {
        WhiteNoiseAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.smart_folder_edit_condition)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderChoice(
                        stringResource(fieldLabel(field)),
                        FolderField.entries.map { it to stringResource(fieldLabel(it)) },
                        "folder.property",
                    ) { selected ->
                        field = selected
                        mode =
                            when (selected) {
                                FolderField.PARTICIPANTS -> FolderMode.ANY_OF
                                FolderField.TYPE -> FolderMode.DIRECT
                                FolderField.TITLE -> FolderMode.CONTAINS
                                else -> FolderMode.PRESENT
                            }
                        keys = emptyList()
                    }
                    if (field != FolderField.TITLE) {
                        FolderChoice(
                            stringResource(modeLabel(mode)),
                            modes.map {
                                it to stringResource(modeLabel(it))
                            },
                            "folder.mode",
                        ) {
                            mode =
                                it
                        }
                    }
                    if (field == FolderField.TITLE) {
                        WhiteNoiseTextField(
                            state = keyword,
                            label = {
                                Text(stringResource(R.string.smart_folder_keyword))
                            },
                            supportingText = {
                                Text(stringResource(R.string.smart_folder_keyword_hint))
                            },
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                    }
                    if (field == FolderField.PARTICIPANTS) {
                        TextButton(
                            onClick = {
                                picking = true
                            },
                            modifier = Modifier.testTag("folder.choosePeople"),
                        ) {
                            Text(
                                stringResource(R.string.smart_folder_select_people) + " (${keys.size})",
                            )
                        }
                        Text(
                            keys.joinToString { hex ->
                                people
                                    .firstOrNull {
                                        it.id == hex
                                    }?.title ?: hex.take(KEY_PREVIEW_LENGTH) + "…"
                            },
                        )
                        WhiteNoiseTextField(
                            state = publicKey,
                            label = { Text(stringResource(R.string.smart_folder_key)) },
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                        TextButton(
                            enabled =
                                !resolving &&
                                    keys.size < SmartFolderCodec.MAX_VALUES,
                            onClick = {
                                resolving = true
                                val input = publicKey.text.toString()
                                scope.launch {
                                    val hex =
                                        withContext(Dispatchers.IO) {
                                            resolveKey(input)
                                        }?.lowercase(java.util.Locale.ROOT)
                                    invalidKey = hex == null ||
                                        hex.length != SmartFolderCodec.PUBKEY_HEX_LENGTH ||
                                        hex.any {
                                            it !in '0'..'9' &&
                                                it !in 'a'..'f'
                                        }
                                    val currentInput =
                                        field == FolderField.PARTICIPANTS &&
                                            publicKey.text.toString() == input
                                    if (!invalidKey && hex != null && currentInput) {
                                        keys = (keys + hex).distinct()
                                    }
                                    resolving = false
                                }
                            },
                        ) { Text(stringResource(R.string.smart_folder_add_key)) }
                        if (invalidKey) {
                            Text(
                                stringResource(R.string.smart_folder_invalid_key),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (field == FolderField.UNREAD) {
                        Text(
                            stringResource(R.string.smart_folder_unread_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (field == FolderField.PENDING_SEND) {
                        Text(
                            stringResource(R.string.smart_folder_pending_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FolderChoice(
                        if (not) {
                            stringResource(R.string.smart_folder_negate_condition)
                        } else {
                            stringResource(R.string.smart_folder_include_match)
                        },
                        listOf(
                            false to stringResource(R.string.smart_folder_include_match),
                            true to stringResource(R.string.smart_folder_negate_condition),
                        ),
                        "folder.conditionNot",
                    ) {
                        not =
                            it
                    }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.smart_folder_ignore)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid && !resolving,
                    onClick = {
                        onDone(condition)
                    },
                    modifier = Modifier.testTag("folder.conditionDone"),
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun fieldLabel(field: FolderField): Int =
    when (field) {
        FolderField.UNREAD -> R.string.smart_folder_unread
        FolderField.MENTIONS -> R.string.smart_folder_mentions
        FolderField.PARTICIPANTS -> R.string.smart_folder_participants
        FolderField.DRAFT -> R.string.smart_folder_draft
        FolderField.PENDING_SEND -> R.string.smart_folder_pending
        FolderField.MUTED -> R.string.smart_folder_muted
        FolderField.ARCHIVED -> R.string.smart_folder_archived
        FolderField.ACCEPTED -> R.string.smart_folder_accepted
        FolderField.TYPE -> R.string.smart_folder_type
        FolderField.TITLE -> R.string.smart_folder_title
        FolderField.PINNED -> R.string.smart_folder_pinned
    }

private fun modeLabel(mode: FolderMode): Int =
    when (mode) {
        FolderMode.PRESENT -> R.string.smart_folder_present
        FolderMode.NONE -> R.string.smart_folder_none
        FolderMode.ANY_OF -> R.string.smart_folder_any_people
        FolderMode.ALL_OF -> R.string.smart_folder_all_people
        FolderMode.EXCLUDES -> R.string.smart_folder_excludes
        FolderMode.DIRECT -> R.string.smart_folder_direct
        FolderMode.GROUP -> R.string.smart_folder_chat_group
        FolderMode.CONTAINS -> R.string.smart_folder_keyword_hint
    }
