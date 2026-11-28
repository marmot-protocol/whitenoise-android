@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

private val FocusedActionCellHorizontalPadding = 12.dp
internal val FocusedActionIconSize = 24.dp
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
 *
 * Actions that are not [FocusedMessageAction.firstLevel] wait behind a More actions cell, always the
 * grid's last, until [showingAll]; [onShowingAllChange] toggles it (#4077). Columns are sized for both
 * states, so opening the full set keeps every first-level action in place: the panel grows, the hidden
 * actions fade into the slots after the first level and the disclosure glides to the end of the grid as
 * Fewer actions. The disclosure keeps one identity throughout, so a screen reader keeps its focus on it.
 *
 * With a [scrollState] the rows scroll inside the rounded surface, so a short frame still shows the
 * menu's whole outline and shadow instead of cutting the surface off at the frame's edge (#4100).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusedActionMenu(
    actions: List<FocusedMessageAction>,
    showingAll: Boolean = true,
    onShowingAllChange: (Boolean) -> Unit = {},
    scrollState: ScrollState? = null,
) {
    val hasMore = hasMoreMessageActions(actions, FocusedMessageAction::firstLevel)
    val laidOut = if (hasMore) actions.sortedBy { !it.firstLevel } else actions
    val firstLevelCount = if (hasMore) laidOut.count(FocusedMessageAction::firstLevel) else laidOut.size
    val disclosure = if (hasMore) focusedActionDisclosure(showingAll) { onShowingAllChange(!showingAll) } else null
    val cellWidths = rememberOneLineActionCellWidths(laidOut + focusedActionDisclosureLabels(disclosure))
    val actionCellHeight = rememberActionCellHeight(laidOut)
    val reveal = rememberFocusedActionReveal()
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShapes(),
        border = amoledOutlineBorder(),
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        BoxWithConstraints(if (scrollState != null) Modifier.fadingVerticalScroll(scrollState) else Modifier) {
            val actionWidths = cellWidths.take(laidOut.size)
            val disclosureWidth = cellWidths.drop(laidOut.size).maxOrNull()
            val grid = focusedActionGridColumns(actionWidths, disclosureWidth, firstLevelCount, maxWidth)
            val columnWidths = grid.widths
            val columns = columnWidths.size
            val disclosurePairs = grid.disclosurePairs
            reveal.Grid(columnWidths, lastMayPair = disclosure == null || disclosurePairs) {
                laidOut.forEachIndexed { index, action ->
                    key(action.label) {
                        val cell: @Composable () -> Unit = {
                            FocusedActionCell(
                                action = action,
                                minimumHeight = actionCellHeight,
                                modifier = Modifier.width(columnWidths[index % columns]),
                            )
                        }
                        val hiddenRow = index / columns - firstLevelCount / columns
                        if (index >= firstLevelCount) reveal.Cell(showingAll, hiddenRow, cell) else cell()
                    }
                }
                if (disclosure != null && disclosureWidth != null) {
                    val slot = if (showingAll) laidOut.size else firstLevelCount
                    key(FOCUSED_ACTION_DISCLOSURE_TEST_TAG) {
                        FocusedActionCell(
                            action = disclosure,
                            minimumHeight = actionCellHeight,
                            modifier =
                                reveal
                                    .glide(this)
                                    .width(
                                        if (slot % columns == 1 && disclosurePairs) {
                                            columnWidths[1]
                                        } else {
                                            maxOf(columnWidths[0], disclosureWidth)
                                        },
                                    ).focusedActionDisclosureState(showingAll)
                                    .testTag(FOCUSED_ACTION_DISCLOSURE_TEST_TAG),
                        )
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
    val (originModifier, activate) =
        rememberMeasuredControlClick { origin ->
            action.onClickAt?.invoke(origin) ?: action.onClick()
        }
    TextButton(
        onClick = activate,
        enabled = action.enabled,
        modifier =
            modifier
                .then(originModifier)
                .fillMaxWidth()
                .heightIn(min = minimumHeight)
                .explainOnLongPress(action.explanation),
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
