package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.ipf.whitenoise.android.R

/**
 * Shared folder-chip presentation for chat filtering and folder bulk actions.
 * [showStateIndicator] is enabled when all three states must be visually distinct;
 * those controls expose [ToggleableState], while binary chat-list filters retain
 * their existing selected-state semantics and long-press gesture contract.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ChatFolderChip(
    state: ToggleableState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailingCount: Int = 0,
    showStateIndicator: Boolean = false,
    labelMaxWidth: Dp? = null,
) {
    val trailingIcon = chatFolderChipTrailingIcon(trailingCount)
    val leadingIcon = chatFolderChipLeadingIcon(state, showStateIndicator)
    val accessibleDescription = chatFolderChipAccessibleDescription(label, trailingCount)
    val interactionSource = remember { MutableInteractionSource() }
    val longClickLabel = if (onLongClick != null) stringResource(R.string.edit) else null
    val gestureModifier =
        interactionSource.chatFolderChipGestureModifier(
            state = state,
            showStateIndicator = showStateIndicator,
            onClick = onClick,
            onLongClick = onLongClick,
            longClickLabel = longClickLabel,
        )
    Box {
        FilterChip(
            selected = state != ToggleableState.Off,
            onClick = {},
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = labelMaxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier,
                )
            },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            interactionSource = interactionSource,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(
            modifier =
                modifier
                    .matchParentSize()
                    .then(gestureModifier)
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibleDescription
                        if (showStateIndicator) {
                            toggleableState = state
                        } else {
                            selected = state == ToggleableState.On
                        }
                    },
        )
    }
}

/** Supplies the visible eligible count while keeping it inside the chip's merged semantics owner. */
private fun chatFolderChipTrailingIcon(trailingCount: Int): (@Composable () -> Unit)? =
    if (trailingCount > 0) {
        {
            Text(
                text = if (trailingCount > 99) "99+" else trailingCount.toString(),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    } else {
        null
    }

/** Supplies an explicit visual glyph for every bulk-selection state. */
private fun chatFolderChipLeadingIcon(
    state: ToggleableState,
    showStateIndicator: Boolean,
): (@Composable () -> Unit)? =
    if (showStateIndicator) {
        {
            Icon(
                imageVector =
                    when (state) {
                        ToggleableState.On -> Icons.Default.CheckBox
                        ToggleableState.Indeterminate -> Icons.Default.IndeterminateCheckBox
                        ToggleableState.Off -> Icons.Default.CheckBoxOutlineBlank
                    },
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        }
    } else {
        null
    }

/** Preserves chat-list long press while using the native tri-state toggle contract for bulk actions. */
private fun MutableInteractionSource.chatFolderChipGestureModifier(
    state: ToggleableState,
    showStateIndicator: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
): Modifier =
    if (showStateIndicator && onLongClick == null) {
        Modifier.triStateToggleable(
            state = state,
            interactionSource = this,
            indication = null,
            role = Role.Checkbox,
            onClick = onClick,
        )
    } else {
        Modifier.combinedClickable(
            interactionSource = this,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = longClickLabel,
            role = Role.Checkbox,
        )
    }

/** Announces the complete folder label and eligible count even when the visible label is ellipsized. */
@Composable
private fun chatFolderChipAccessibleDescription(
    label: String,
    trailingCount: Int,
): String =
    if (trailingCount > 0) {
        val countLabel =
            pluralStringResource(
                R.plurals.chat_folder_chat_count,
                trailingCount,
                trailingCount,
            )
        "$label, $countLabel"
    } else {
        label
    }
