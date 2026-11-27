package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

private const val AMOLED_SELECTED_CHIP_OUTLINE_ALPHA = 0.5f

/**
 * A compact selectable filter or option. On AMOLED, a gray outline marks selection and leading icons stay visible.
 * Light and dark themes use the secondary container and a check for selection.
 *
 * [showCheck] = false keeps the leading icon in every theme, for removable chips with a [trailingIcon].
 * [onLongClick] moves the gesture and semantics to one overlay that owns the whole chip, because the Material
 * chip has no long press; [modifier] then lands on that overlay, not on the chip's layout. [role] overrides the
 * chip's checkbox role, for example with a radio button in a single-choice group.
 * [contentDescription] names the chip for TalkBack when the visible label is truncated or carries a count.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList", "FunctionNaming")
internal fun WhiteNoiseFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    showCheck: Boolean = true,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    role: Role? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val amoled = isAmoledSurfaceTheme()
    val lead = if (selected && showCheck && !amoled) ({ WhiteNoiseFilterChipCheck() }) else leadingIcon
    val border =
        if (amoled && selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = AMOLED_SELECTED_CHIP_OUTLINE_ALPHA))
        } else {
            null
        }
    val overlay = onLongClick != null
    val describe =
        if (contentDescription == null && role == null) {
            Modifier
        } else {
            Modifier.semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                if (role != null) this.role = role
            }
        }
    val chip: @Composable (Modifier) -> Unit = { chipModifier ->
        FilterChip(
            selected = selected,
            onClick = if (overlay) ({}) else onClick,
            label = label,
            modifier = chipModifier,
            enabled = enabled,
            leadingIcon = lead,
            trailingIcon = trailingIcon,
            shape = CircleShape,
            colors = whiteNoiseFilterChipColors(),
            border = border,
            interactionSource = interactions,
        )
    }
    if (!overlay) {
        chip(modifier.then(describe))
        return
    }
    Box {
        chip(Modifier.clearAndSetSemantics {})
        Box(
            modifier =
                modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = interactions,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        onLongClickLabel = longClickLabel,
                        role = role ?: Role.Checkbox,
                    ).semantics(mergeDescendants = true) {
                        if (contentDescription != null) this.contentDescription = contentDescription
                        this.selected = selected
                        if (role != null) this.role = role
                    },
        )
    }
}

/** Tonal container colours shared by every filter chip; selected chips use the secondary container. */
@Composable
private fun whiteNoiseFilterChipColors(): SelectableChipColors =
    FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTrailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

/** The check that marks a selected chip, sized to the Material chip icon slot. */
@Composable
@Suppress("FunctionNaming")
private fun WhiteNoiseFilterChipCheck() {
    Icon(
        painterResource(R.drawable.ic_check),
        contentDescription = null,
        modifier = Modifier.size(FilterChipDefaults.IconSize),
    )
}
