@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Explicit styling for one visible row; obtain it from [SettingsGroupScope.row]. */
internal class SettingsRowContext internal constructor(
    val shapes: ListItemShapes,
    val containerColor: Color,
    val borderColor: Color,
)

/** A rendering entry has no application state; its caller owns actions and displayed values. */
internal class SettingsGroupEntry(
    val key: String,
    val content: @Composable (SettingsRowContext) -> Unit,
)

/** Collect visible rows before assigning positions; omit hidden rows with ordinary conditional calls. */
internal class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<SettingsGroupEntry>()

    /** A stable, unique [key] preserves row composition when neighboring rows appear or disappear. */
    fun row(
        key: String,
        content: @Composable (SettingsRowContext) -> Unit,
    ) {
        require(rows.none { it.key == key }) { "Settings group row key '$key' is already used" }
        rows.add(SettingsGroupEntry(key, content))
    }
}

/**
 * Material list group with positions computed from the complete visible row set.
 *
 * The group owns the compact screen margin; headers and helpers sit at the 32 dp content line.
 * No theme, preference, or route changes occur here. Non-AMOLED rows keep Material's segmented
 * gap; AMOLED rows touch and share one-pixel seams.
 */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    content: SettingsGroupScope.() -> Unit,
) {
    val group = SettingsGroupScope().apply(content)
    val amoled = isAmoledSurfaceTheme()
    val borderColor = if (amoled) MaterialTheme.colorScheme.outline else Color.Unspecified
    val gap = if (amoled) 0.dp else ListItemDefaults.SegmentedGap
    Column(
        modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        group.rows.forEachIndexed { index, entry ->
            key(entry.key) {
                val shapes = settingsRowShapes(index, group.rows.size, amoled)
                entry.content(SettingsRowContext(shapes, containerColor, borderColor))
            }
        }
    }
}

/**
 * Native positional shapes for one row, frozen across interaction states so seams cannot open.
 *
 * Material 1.5.0-alpha20 leaves a one-row group at the small resting corner, while alpha25 gives
 * it the large container corners on every side. That singleton case is compensated here with the
 * same large shape token until the dependency pin advances.
 */
@Composable
private fun settingsRowShapes(
    index: Int,
    count: Int,
    amoled: Boolean,
): ListItemShapes {
    val defaults = ListItemDefaults.segmentedShapes(index, count)
    val large = MaterialTheme.shapes.large
    // Material returns the same default shapes for a middle row and a singleton, so key on position too.
    return remember(index, count, defaults, large, amoled) {
        val corners =
            requireNotNull(defaults.shape as? CornerBasedShape) { "Material list corners must be corner based" }
        val first = index == 0
        val last = index == count - 1
        val positioned =
            if (count == 1) {
                corners.copy(
                    topStart = large.topStart,
                    topEnd = large.topEnd,
                    bottomEnd = large.bottomEnd,
                    bottomStart = large.bottomStart,
                )
            } else {
                corners
            }
        val shape: Shape =
            if (amoled) {
                ConnectedRowShape(
                    corners =
                        positioned.copy(
                            topStart = if (first) positioned.topStart else CornerSize(0.dp),
                            topEnd = if (first) positioned.topEnd else CornerSize(0.dp),
                            bottomEnd = if (last) positioned.bottomEnd else CornerSize(0.dp),
                            bottomStart = if (last) positioned.bottomStart else CornerSize(0.dp),
                        ),
                    first = first,
                    last = last,
                )
            } else {
                positioned
            }
        defaults.copy(
            shape = shape,
            selectedShape = shape,
            pressedShape = shape,
            focusedShape = shape,
            hoveredShape = shape,
            draggedShape = shape,
        )
    }
}

/**
 * One native toggle action covers the entire row; the child switch is decorative.
 *
 * Both [enabled] and [busy] gate edits while the caller retains the authoritative [checked] value.
 * Busy rows replace the decorative switch with the existing Material progress indicator.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsSwitch(
    context: SettingsRowContext,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val editable = enabled && !busy
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = context.shapes,
        modifier =
            modifier
                .fillMaxWidth()
                .settingsRowBorder(context, editable)
                .semantics { role = Role.Switch },
        enabled = editable,
        supportingContent = subtitle?.let { { SettingsRowSupportingText(it, editable) } },
        trailingContent = {
            SettingsRowTrailing(busy) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = editable,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        },
        colors = settingsRowColors(context),
        content = { SettingsRowTitle(title, editable) },
    )
}

/**
 * A single navigation action with wrapping value/subtitle text and a decorative mirrored chevron.
 * A subtitle equal to the value is shown once rather than repeated.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsLink(
    context: SettingsRowContext,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val editable = enabled && !busy
    val summary = listOfNotNull(value, subtitle).distinct().joinToString("\n").takeIf { it.isNotEmpty() }
    SegmentedListItem(
        onClick = onClick,
        shapes = context.shapes,
        modifier =
            modifier
                .fillMaxWidth()
                .settingsRowBorder(context, editable)
                .semantics { role = Role.Button },
        enabled = editable,
        leadingContent = leading,
        supportingContent = summary?.let { { SettingsRowSupportingText(it, editable) } },
        trailingContent = {
            SettingsRowTrailing(busy) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
            }
        },
        colors = settingsRowColors(context),
        content = { SettingsRowTitle(title, editable, destructive) },
    )
}

/**
 * Row that performs an action in place instead of opening a destination, so it carries no chevron.
 *
 * [destructive] actions use the error role for the title; callers tint any leading icon to match.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsAction(
    context: SettingsRowContext,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = context.shapes,
        modifier =
            modifier
                .fillMaxWidth()
                .settingsRowBorder(context, enabled)
                .semantics { role = Role.Button },
        enabled = enabled,
        leadingContent = leading,
        supportingContent = subtitle?.let { { SettingsRowSupportingText(it, enabled) } },
        colors = settingsRowColors(context),
        content = { SettingsRowTitle(title, enabled, destructive) },
    )
}

/** Keep the group fill identical at rest, checked, and disabled so only the control reports state. */
@Composable
private fun settingsRowColors(context: SettingsRowContext): ListItemColors =
    ListItemDefaults.segmentedColors(
        containerColor = context.containerColor,
        disabledContainerColor = context.containerColor,
        selectedContainerColor = context.containerColor,
    )

/**
 * One selectable choice in a radio-style group; the leading radio button is decorative and the row
 * carries the single `RadioButton` role and selected state.
 *
 * [highlightSelected] tints the selected row as picker groups do; theme-mode groups
 * pass `false` so all modes keep one fill and only the radio reports the choice.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsChoice(
    context: SettingsRowContext,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    highlightSelected: Boolean = true,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = context.shapes,
        modifier = modifier.fillMaxWidth().settingsRowBorder(context, enabled),
        enabled = enabled,
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        supportingContent = subtitle?.let { { SettingsRowSupportingText(it, enabled) } },
        colors = settingsChoiceColors(context, highlightSelected),
        content = { SettingsRowTitle(title, enabled) },
    )
}

/** Choice rows share the group fill except for an optional tonal highlight on the selected row. */
@Composable
private fun settingsChoiceColors(
    context: SettingsRowContext,
    highlightSelected: Boolean,
): ListItemColors {
    val selectedContainer =
        when {
            !highlightSelected -> context.containerColor
            isAmoledSurfaceTheme() -> {
                MaterialTheme.colorScheme.onSurface.copy(alpha = SettingsRowDefaults.AmoledSelectionAlpha)
            }
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    return ListItemDefaults.segmentedColors(
        containerColor = context.containerColor,
        disabledContainerColor = context.containerColor,
        selectedContainerColor = selectedContainer,
    )
}
