package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * The one compact selectable filter or option. A pill with a tonal fill, no border outside AMOLED, and the
 * secondary container plus a check when selected, so selection reads from shape and colour at any font scale.
 *
 * A [leadingIcon] replaces the check; [showCheck] = false drops it for chips that are removable ([trailingIcon]).
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
    val lead = leadingIcon ?: if (selected && showCheck) ({ WhiteNoiseFilterChipCheck() }) else null
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
            border = amoledSurfaceBorderStroke(),
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

@Composable
private fun whiteNoiseFilterChipColors(): ChipColors =
    FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTrailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

@Composable
@Suppress("FunctionNaming")
private fun WhiteNoiseFilterChipCheck() {
    Icon(
        painterResource(R.drawable.ic_check),
        contentDescription = null,
        modifier = Modifier.size(FilterChipDefaults.IconSize),
    )
}
