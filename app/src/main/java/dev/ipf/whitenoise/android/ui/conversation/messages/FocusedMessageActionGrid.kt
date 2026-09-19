@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

private val FocusedMenuMinimumWidth = 248.dp
private val FocusedMenuMaximumWidth = 384.dp
private val FocusedActionCellMinimumWidth = 136.dp
private val FocusedActionCellChrome = 52.dp
private val FocusedActionCellMinimumHeight = 48.dp
private val FocusedActionCellSupportingHeight = 64.dp
private val FocusedActionCellVerticalPadding = 8.dp

/** Material's grouped menu: leading icon, label with an optional second line, error colours for destructive rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusedActionMenu(actions: List<FocusedMessageAction>) {
    val minimumActionCellWidth = rememberMinimumActionCellWidth(actions)
    val actionCellHeight = rememberActionCellHeight(actions)
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShapes(),
        border = amoledOutlineBorder(),
        modifier = Modifier.widthIn(min = FocusedMenuMinimumWidth, max = FocusedMenuMaximumWidth),
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        BoxWithConstraints {
            val columns = messageActionColumnCount(maxWidth, minimumActionCellWidth)
            if (columns < 2) {
                // One column is not a narrow grid, it is the list this menu already was: a cell
                // sized for two columns cannot hold a label at 200% font without clipping it.
                FocusedActionColumn(actions)
                return@BoxWithConstraints
            }
            Column(verticalArrangement = Arrangement.spacedBy(messageActionColumnGap)) {
                actions.chunked(columns).forEach { rowActions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(messageActionColumnGap),
                    ) {
                        rowActions.forEach { action ->
                            FocusedActionCell(
                                action = action,
                                minimumHeight = actionCellHeight,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // A trailing odd action keeps its column rather than stretching across the row.
                        repeat(columns - rowActions.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * How wide a cell has to be before two of them are worth offering.
 *
 * A label may wrap, so a cell only has to hold the longest single word: "Save attachments" fits
 * two columns over two lines, where demanding the whole phrase on one line would collapse every
 * column to fit that one action. At large font the longest word alone outgrows half the menu,
 * which is what still drops the grid to one readable column.
 */
@Composable
private fun rememberMinimumActionCellWidth(actions: List<FocusedMessageAction>): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val actionTextStyle = MaterialTheme.typography.titleMedium
    return remember(actions, actionTextStyle, density, textMeasurer) {
        with(density) {
            val widestWordPx =
                actions
                    .flatMap { it.label.split(' ') }
                    .maxOfOrNull { word ->
                        textMeasurer
                            .measure(AnnotatedString(word), style = actionTextStyle, maxLines = 1)
                            .size.width
                    } ?: 0
            maxOf(FocusedActionCellMinimumWidth, widestWordPx.toDp() + FocusedActionCellChrome)
        }
    }
}

/**
 * How tall a cell has to be for the type it holds.
 *
 * A fixed 48dp clips a single line at 200% font, which is exactly where a reader needs the label
 * most, and an action carrying a reason it is unavailable needs room for that line too.
 */
@Composable
private fun rememberActionCellHeight(actions: List<FocusedMessageAction>): Dp {
    val density = LocalDensity.current
    val actionTextStyle = MaterialTheme.typography.titleMedium
    val supportingTextStyle = MaterialTheme.typography.bodySmall
    return with(density) {
        val carriesSupportingLabel = actions.any { it.supportingLabel != null }
        maxOf(
            if (carriesSupportingLabel) FocusedActionCellSupportingHeight else FocusedActionCellMinimumHeight,
            actionTextStyle.lineHeight.toDp() +
                if (carriesSupportingLabel) {
                    supportingTextStyle.lineHeight.toDp() * 2 + FocusedActionCellVerticalPadding * 2
                } else {
                    FocusedActionCellVerticalPadding * 2
                },
        )
    }
}

/** The single readable column the grid falls back to when two would not fit. */
@Composable
private fun FocusedActionColumn(actions: List<FocusedMessageAction>) {
    Column {
        actions.forEachIndexed { index, action ->
            val contentColor =
                if (action.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            DropdownMenuItem(
                text = {
                    Column {
                        Text(action.label, style = MaterialTheme.typography.bodyLarge)
                        if (action.supportingLabel != null) {
                            Text(action.supportingLabel, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                onClick = action.onClick,
                shape = MenuDefaults.itemShape(index, actions.size).shape,
                leadingIcon = action.icon,
                enabled = action.enabled,
                colors =
                    MenuDefaults.itemColors(
                        textColor = contentColor,
                        leadingIconColor = contentColor,
                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}

/** One labelled action in the grid: icon, label, and the reason it is unavailable when there is one. */
@Composable
private fun FocusedActionCell(
    action: FocusedMessageAction,
    minimumHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = minimumHeight),
        contentPadding =
            PaddingValues(horizontal = 12.dp, vertical = FocusedActionCellVerticalPadding),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = contentColor,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            action.icon()
            Spacer(Modifier.size(8.dp))
            // Weighted so the label is measured against the width the cell actually has. Without
            // it the row offers its natural width, the label lays out on one long line and is
            // clipped rather than wrapped.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    action.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (action.supportingLabel != null) {
                    Text(
                        action.supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
