@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.SurfaceMotion
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

internal const val FOCUSED_ACTION_DISCLOSURE_TEST_TAG = "focused-action-disclosure"

// The chevron points down to open the full set and up to return from it.
private const val DISCLOSURE_OPEN_ROTATION = 180f

// Each further row of revealed actions starts this much later.
private const val REVEALED_ROW_STAGGER_MILLIS = 40

// A revealed cell starts a little small, so it settles into its slot rather than blinking on.
private const val REVEALED_CELL_INITIAL_SCALE = 0.92f

/**
 * How the actions behind More actions arrive and leave (#4077), on the app's open and close curves.
 *
 * The grid's bounds and the disclosure's place animate between the two states, so the panel grows and
 * shrinks instead of jumping to its new height and the disclosure glides to its new slot. A hidden action
 * fades and settles into its slot as the panel opens, and fades out before it closes. With system
 * animations off every change is immediate.
 */
@Immutable
internal class FocusedActionReveal(
    private val animated: Boolean,
) {
    private fun <T> open(): FiniteAnimationSpec<T> = if (animated) SurfaceMotion.Open.spec() else snap()

    private fun <T> close(): FiniteAnimationSpec<T> = if (animated) SurfaceMotion.Close.spec() else snap()

    private val boundsTransform = BoundsTransform { _, _ -> open() }

    /** Rows further down start later, so the disclosure gliding past them reaches its slot first. */
    private fun cellEnter(row: Int): EnterTransition {
        val delayed: FiniteAnimationSpec<Float> =
            if (animated) {
                tween(
                    durationMillis = APP_MOTION_OPEN_MILLIS,
                    delayMillis = row * REVEALED_ROW_STAGGER_MILLIS,
                    easing = AppMotionOpenEasing,
                )
            } else {
                snap()
            }
        return fadeIn(delayed) + scaleIn(delayed, initialScale = REVEALED_CELL_INITIAL_SCALE)
    }

    private val cellExit: ExitTransition
        get() = fadeOut(close()) + scaleOut(close(), targetScale = REVEALED_CELL_INITIAL_SCALE)

    /**
     * The action grid: each cell in the column its slot falls in, rows as tall as their tallest cell, and
     * the grid's size animating as cells arrive, leave or move. Unless [lastMayPair], the last cell starts a
     * row of its own; a last cell at the start of a row may take its own width, up to the grid's.
     */
    @Composable
    fun Grid(
        columnWidths: List<Dp>,
        lastMayPair: Boolean,
        content: @Composable LookaheadScope.() -> Unit,
    ) {
        LookaheadScope {
            val scope = this
            Layout(
                content = { scope.content() },
                modifier = Modifier.animateContentSize(open(), alignment = Alignment.TopStart),
            ) { measurables, _ ->
                val columns = columnWidths.size
                val gap = messageActionColumnGap.roundToPx()
                val widths = columnWidths.map { it.roundToPx() }
                val gridWidth = widths.sum() + gap * (columns - 1)
                val slots =
                    measurables.indices.map { index ->
                        val startsRow = index == measurables.lastIndex && !lastMayPair && index % columns != 0
                        if (startsRow) index + columns - index % columns else index
                    }
                val placeables =
                    measurables.mapIndexed { index, measurable ->
                        val column = widths[slots[index] % columns]
                        val alone = index == measurables.lastIndex && slots[index] % columns == 0
                        measurable.measure(Constraints(minWidth = column, maxWidth = if (alone) gridWidth else column))
                    }
                val rows = (slots.maxOrNull() ?: -1) / columns + 1
                val rowHeights = IntArray(rows)
                placeables.forEachIndexed { index, cell ->
                    val row = slots[index] / columns
                    rowHeights[row] = maxOf(rowHeights[row], cell.height)
                }
                val rowTops = rowHeights.runningFold(0) { top, height -> top + height + gap }
                layout(gridWidth, (rowTops.lastOrNull() ?: gap) - gap) {
                    placeables.forEachIndexed { index, cell ->
                        val column = slots[index] % columns
                        cell.placeRelative(widths.take(column).sum() + gap * column, rowTops[slots[index] / columns])
                    }
                }
            }
        }
    }

    /** Moves a cell to its new slot along the panel's curve instead of jumping there. */
    @OptIn(ExperimentalSharedTransitionApi::class)
    fun glide(scope: LookaheadScope): Modifier = Modifier.animateBounds(scope, boundsTransform = boundsTransform)

    /** A hidden action, settling into its slot after the first level; [row] counts from the first hidden row. */
    @Composable
    fun Cell(
        visible: Boolean,
        row: Int,
        content: @Composable () -> Unit,
    ) {
        AnimatedVisibility(visible = visible, enter = cellEnter(row), exit = cellExit) { content() }
    }

    /** The disclosure chevron's turn, in step with the panel. */
    @Composable
    fun chevronRotation(showingAll: Boolean): Float {
        val rotation by animateFloatAsState(
            targetValue = if (showingAll) DISCLOSURE_OPEN_ROTATION else 0f,
            animationSpec = if (showingAll) open() else close(),
            label = "focused-action-disclosure-chevron",
        )
        return rotation
    }
}

/** The reveal for this composition, honouring the system's animation setting. */
@Composable
internal fun rememberFocusedActionReveal(): FocusedActionReveal {
    val animated = rememberSystemAnimationsEnabled()
    return remember(animated) { FocusedActionReveal(animated) }
}

/** The row that shows every action, or returns to the first level once they are shown. */
@Composable
internal fun focusedActionDisclosure(
    showingAll: Boolean,
    onToggle: () -> Unit,
): FocusedMessageAction {
    val label = stringResource(if (showingAll) R.string.fewer_message_actions else R.string.more_message_actions)
    val explanation =
        stringResource(
            if (showingAll) R.string.explanation_message_action_fewer else R.string.explanation_message_action_more,
        )
    val reveal = rememberFocusedActionReveal()
    return FocusedMessageAction(
        label = label,
        supportingLabel = null,
        enabled = true,
        destructive = false,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.size(FocusedActionIconSize).rotate(reveal.chevronRotation(showingAll)),
            )
        },
        onClick = onToggle,
        explanation = Explanation(label, explanation),
        dismissBeforeActivation = false,
    )
}

/**
 * The disclosure under both of its labels, so its width is measured for the longer one and the row
 * keeps one width while its label changes. Empty without a disclosure.
 */
@Composable
internal fun focusedActionDisclosureLabels(disclosure: FocusedMessageAction?): List<FocusedMessageAction> =
    if (disclosure == null) {
        emptyList()
    } else {
        listOf(
            disclosure.copy(label = stringResource(R.string.more_message_actions)),
            disclosure.copy(label = stringResource(R.string.fewer_message_actions)),
        )
    }

/** Back from the full action set: returns to the first level and reports Back handled, or reports it unhandled. */
internal fun MutableState<Boolean>.collapseFullActionSet(): Boolean =
    if (value) {
        value = false
        true
    } else {
        false
    }

/** The action grid's column widths, and whether the disclosure may sit beside an action in the second. */
internal data class FocusedActionGridColumns(
    val widths: List<Dp>,
    val disclosurePairs: Boolean,
)

/**
 * Columns for [actionWidths], the full set's one-line cell widths in menu order, and a disclosure of
 * [disclosureWidth] after the first [firstLevelCount] or after them all (#4077).
 *
 * The first level is a prefix of the full set, so columns sized for the full set hold both states. When
 * either state puts the disclosure beside an action, the second column widens for it if the width allows;
 * otherwise the disclosure starts the next row instead, where it may run past its column.
 */
internal fun focusedActionGridColumns(
    actionWidths: List<Dp>,
    disclosureWidth: Dp?,
    firstLevelCount: Int,
    availableWidth: Dp,
): FocusedActionGridColumns {
    val shared = messageActionColumnWidths(actionWidths, availableWidth)
    val pairedSlot = firstLevelCount % 2 == 1 || actionWidths.size % 2 == 1
    val widths =
        when {
            disclosureWidth == null -> shared
            shared.size == 1 -> listOf(minOf(maxOf(shared.single(), disclosureWidth), availableWidth))
            pairedSlot && shared[0] + messageActionColumnGap + disclosureWidth <= availableWidth ->
                listOf(shared[0], maxOf(shared[1], disclosureWidth))
            else -> shared
        }
    val pairs = disclosureWidth != null && widths.size == 2 && disclosureWidth <= widths[1]
    return FocusedActionGridColumns(widths, disclosurePairs = pairs)
}
