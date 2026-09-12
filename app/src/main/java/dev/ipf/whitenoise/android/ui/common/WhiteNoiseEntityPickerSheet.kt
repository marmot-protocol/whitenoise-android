package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

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
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            WhiteNoiseSheetHeader(title, onClose = onDismiss)
            if (description != null) {
                Text(
                    description,
                    Modifier.padding(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WhiteNoiseCompactSearchField(
                value = query,
                onValueChange = { query = it },
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
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().testTag("entity.list"),
                contentPadding =
                    PaddingValues(
                        horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                        vertical = WhiteNoiseSpacing.Related,
                    ),
                verticalArrangement = Arrangement.spacedBy(PickerRowGap),
            ) {
                if (visible.isEmpty()) {
                    item { Text(stringResource(R.string.no_results), Modifier.padding(WhiteNoiseSpacing.Related)) }
                }
                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                    PickerRow(
                        item = item,
                        index = index,
                        count = visible.size,
                        checked = item.id in selectedIds,
                        multiple = multiple,
                        onSelect = onSelect,
                        modifier = Modifier.testTag("$rowTagPrefix.${item.id}"),
                    )
                }
            }
            if (onDone != null) {
                WhiteNoiseButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.CompactScreenMargin),
                ) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/** One picker row on the segmented group shape: avatar, title and, for multi-select, a checkbox. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val shape = ListItemDefaults.segmentedShapes(index, count).shape
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = amoledOutlineBorder(item.enabled),
    ) {
        Row(
            Modifier
                .then(interaction)
                .heightIn(min = PickerRowMinHeight)
                .padding(horizontal = WhiteNoiseSpacing.FormField, vertical = WhiteNoiseSpacing.Related),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            Avatar(title = item.title, seed = item.avatarSeed, size = PickerAvatarSize, pictureUrl = item.avatarUrl)
            Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (multiple && onSelect != null) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = item.enabled,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** A pill-shaped single-line search field with a leading glyph and a clear action while text is present. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun WhiteNoiseCompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fieldColors =
        TextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            disabledContainerColor = containerColor,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = SearchFieldMinHeight),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        singleLine = true,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            TextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = { Text(text = placeholder, maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon =
                    if (value.isNotEmpty()) {
                        {
                            IconButton(onClick = { onValueChange("") }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_search))
                            }
                        }
                    } else {
                        null
                    },
                shape = MaterialTheme.shapes.extraLarge,
                colors = fieldColors,
                contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(top = 0.dp, bottom = 0.dp),
                container = {
                    TextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = fieldColors,
                        shape = MaterialTheme.shapes.extraLarge,
                        focusedIndicatorLineThickness = 0.dp,
                        unfocusedIndicatorLineThickness = 0.dp,
                    )
                },
            )
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
private val PickerRowGap = 2.dp
private val PickerRowMinHeight = 56.dp
private val PickerAvatarSize = 40.dp
private val SearchFieldMinHeight = 48.dp
