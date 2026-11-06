@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

private val FocusedActionCellHorizontalPadding = 12.dp
private val FocusedActionIconSize = 24.dp
private val FocusedActionIconGap = 8.dp
private val FocusedActionLabelSlack = 2.dp
private val FocusedActionCellChrome =
    FocusedActionCellHorizontalPadding * 2 + FocusedActionIconSize + FocusedActionIconGap
private val FocusedActionCellMinimumHeight = 48.dp
private val FocusedActionCellSupportingHeight = 64.dp
private val FocusedActionCellVerticalPadding = 8.dp

/**
 * Material's grouped menu: leading icon, label, an optional reason line, error colours for destructive rows.
 *
 * The box wraps its columns, and each column is as wide as its widest one-line label, so a short neighbour
 * never reserves an equal share that forces a longer label to wrap. When two such columns do not fit the
 * width the stack offers, the actions become one column up to that full width before any label wraps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusedActionMenu(actions: List<FocusedMessageAction>) {
    val cellWidths = rememberOneLineActionCellWidths(actions)
    val actionCellHeight = rememberActionCellHeight(actions)
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShapes(),
        border = amoledOutlineBorder(),
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        BoxWithConstraints {
            val columnWidths = messageActionColumnWidths(cellWidths, maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(messageActionColumnGap)) {
                actions.chunked(columnWidths.size).forEach { rowActions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(messageActionColumnGap)) {
                        rowActions.forEachIndexed { column, action ->
                            FocusedActionCell(
                                action = action,
                                minimumHeight = actionCellHeight,
                                modifier = Modifier.width(columnWidths[column]),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Each action's cell width with its whole label on one line, in the label's real typography.
 *
 * The density carries the app and system text scale, so a larger font asks for wider cells and drops the
 * grid to one column on its own, rather than at a fixed scale threshold. A cell that explains why its
 * action is unavailable is also wide enough for that reason to fit its two lines, so the explanation is
 * never cut down to the width of a short label.
 */
@Composable
private fun rememberOneLineActionCellWidths(actions: List<FocusedMessageAction>): List<Dp> {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val actionTextStyle = MaterialTheme.typography.titleMedium
    val supportingTextStyle = MaterialTheme.typography.bodySmall
    return remember(
        actions.map { it.label to it.supportingLabel },
        actionTextStyle,
        supportingTextStyle,
        density,
        textMeasurer,
    ) {
        actions.map { action ->
            val labelPx = textMeasurer.oneLineWidth(action.label, actionTextStyle)
            val reasonPx =
                action.supportingLabel?.let { textMeasurer.twoLineWidth(it, supportingTextStyle) } ?: 0
            // Rounded up with a little slack, so pixel rounding in the cell's padding and icon slot
            // can never leave the text a fraction of a pixel short of its lines.
            with(density) { maxOf(labelPx, reasonPx).toDp() + FocusedActionLabelSlack + FocusedActionCellChrome }
        }
    }
}

/** The width [text] needs on one unwrapped line. */
private fun TextMeasurer.oneLineWidth(
    text: String,
    style: TextStyle,
): Int = measure(AnnotatedString(text), style = style, maxLines = 1, softWrap = false).size.width

/**
 * The narrowest width, from half the one-line width upward, at which [text] wraps onto at most two lines.
 * Word breaks make the exact width uneven, so it widens in small steps until a real layout fits.
 */
private fun TextMeasurer.twoLineWidth(
    text: String,
    style: TextStyle,
): Int {
    val oneLine = oneLineWidth(text, style)
    var width = (oneLine + 1) / 2
    val step = (oneLine / TWO_LINE_WIDTH_STEPS).coerceAtLeast(1)
    while (width < oneLine) {
        val lines = measure(AnnotatedString(text), style = style, constraints = Constraints(maxWidth = width)).lineCount
        if (lines <= 2) return width
        width += step
    }
    return oneLine
}

private const val TWO_LINE_WIDTH_STEPS = 16

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
            PaddingValues(
                horizontal = FocusedActionCellHorizontalPadding,
                vertical = FocusedActionCellVerticalPadding,
            ),
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
            // A fixed icon slot keeps the cell chrome exactly what the width measurement assumed.
            Box(Modifier.size(FocusedActionIconSize), contentAlignment = Alignment.Center) { action.icon() }
            Spacer(Modifier.size(FocusedActionIconGap))
            // Weighted so the label is measured against the width the cell actually has. Without
            // it the row offers its natural width, the label lays out on one long line and is
            // clipped rather than wrapped. Cells are sized for one line, so a primary label only
            // wraps once a single full-width column is too narrow, and then it wraps whole.
            Column(modifier = Modifier.weight(1f)) {
                Text(action.label, style = MaterialTheme.typography.titleMedium)
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
