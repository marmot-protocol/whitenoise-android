package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.newchat.PersonRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * Searchable list of people or chats in a sheet. With [onSelect] and [multiple] each row toggles a checkbox; with
 * [onSelect] alone a row is chosen and the caller closes; without it the rows only read. [onDone] pins a Done button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
fun WhiteNoiseEntityPickerSheet(
    title: String,
    items: List<WhiteNoisePickerItem>,
    onDismiss: () -> Unit,
    onSelect: ((String) -> Unit)? = null,
    selectedIds: Set<String> = emptySet(),
    multiple: Boolean = false,
    onDone: (() -> Unit)? = null,
    description: String? = null,
    searchTag: String = "entity.search",
    rowTagPrefix: String = "entity.choice",
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val needle = query.trim()
    val visible = if (needle.isEmpty()) items else items.filter { it.title.contains(needle, ignoreCase = true) }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { (windowHeightPx * SHEET_HEIGHT_SHARE).toDp() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheet)
    val close = { terminal.close(onDismiss) }
    WhiteNoiseModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheet,
        interactionEnabled = terminal.acceptsInput,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .then(if (terminal.acceptsInput) Modifier else Modifier.clearAndSetSemantics {})
                .onPreviewKeyEvent { !terminal.acceptsInput }
                .pointerInput(terminal) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (!terminal.acceptsInput) event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            WhiteNoiseSheetHeader(title, onClose = close)
            if (description != null) {
                EmojiLabel(
                    description,
                    Modifier.padding(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WhiteNoiseCompactSearchField(
                value = query,
                onValueChange = { if (terminal.acceptsInput) query = it },
                placeholder = stringResource(R.string.folder_search),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                            vertical = WhiteNoiseSpacing.Related,
                        ).testTag(searchTag),
            )
            WhiteNoiseLazyColumn(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .testTag("entity.list")
                        .semantics { collectionInfo = CollectionInfo(visible.size, 1) },
                contentPadding = PaddingValues(vertical = WhiteNoiseSpacing.Related),
            ) {
                if (visible.isEmpty()) {
                    item { WhiteNoiseEmptyState(stringResource(R.string.no_matches)) }
                }
                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                    PickerRow(
                        item = item,
                        index = index,
                        count = visible.size,
                        checked = item.id in selectedIds,
                        multiple = multiple,
                        onSelect =
                            onSelect?.let { select ->
                                { id ->
                                    if (terminal.acceptsInput) {
                                        if (multiple) select(id) else terminal.close(id, select)
                                    }
                                }
                            },
                        modifier = Modifier.testTag("$rowTagPrefix.${item.id}"),
                    )
                }
            }
            if (onDone != null) {
                WhiteNoiseButton(
                    onClick = { terminal.close(onDone) },
                    modifier = Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.CompactScreenMargin),
                ) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/** One full-width row with an avatar, title and, for multi-select, a checkbox. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun PickerRow(
    item: WhiteNoisePickerItem,
    index: Int,
    count: Int,
    checked: Boolean,
    multiple: Boolean,
    onSelect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction =
        when {
            onSelect == null -> Modifier
            multiple ->
                Modifier.toggleable(
                    value = checked,
                    enabled = item.enabled,
                    role = Role.Checkbox,
                    onValueChange = { onSelect(item.id) },
                )
            else -> Modifier.toggleable(value = false, enabled = item.enabled, role = Role.Button) { onSelect(item.id) }
        }
    PersonRow(
        index = index,
        count = count,
        title = item.title,
        subtitle = null,
        avatarSeed = item.avatarSeed,
        avatarUrl = item.avatarUrl,
        avatarDomain = MediaTrustDomain.Unique,
        modifier = modifier.then(interaction).semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) },
        trailing =
            if (multiple && onSelect != null) {
                {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = item.enabled,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            } else {
                null
            },
    )
}

/** One selectable entity: a stable id, its display title and an avatar identity. */
data class WhiteNoisePickerItem(
    val id: String,
    val title: String,
    val avatarSeed: String = id,
    val avatarUrl: String? = null,
    val enabled: Boolean = true,
)

private const val SHEET_HEIGHT_SHARE = 0.88f
