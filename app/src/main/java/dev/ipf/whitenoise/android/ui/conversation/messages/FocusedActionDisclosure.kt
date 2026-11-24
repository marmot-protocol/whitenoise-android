@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.SurfaceMotion
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

internal const val FOCUSED_ACTION_DISCLOSURE_TEST_TAG = "focused-action-disclosure"

// The chevron points down to open the full set and up to return from it.
private const val DISCLOSURE_OPEN_ROTATION = 180f

// A revealed cell starts a little small, so it settles into its slot rather than blinking on.
private const val REVEALED_CELL_INITIAL_SCALE = 0.92f

/**
 * How the actions behind More actions arrive and leave (#4077), on the app's open and close curves.
 *
 * Rows unfold from their top edge, so the panel and the row beneath it grow and shrink with them instead
 * of jumping to the new height; a cell sharing a row with the first level fades and settles into its slot.
 * With system animations off every change is immediate.
 */
@Immutable
internal class FocusedActionReveal(
    private val animated: Boolean,
) {
    private fun <T> open(): FiniteAnimationSpec<T> = if (animated) SurfaceMotion.Open.spec() else snap()

    private fun <T> close(): FiniteAnimationSpec<T> = if (animated) SurfaceMotion.Close.spec() else snap()

    private val rowEnter: EnterTransition
        get() = expandVertically(open<IntSize>(), expandFrom = Alignment.Top) + fadeIn(open())
    private val rowExit: ExitTransition
        get() = shrinkVertically(close<IntSize>(), shrinkTowards = Alignment.Top) + fadeOut(close())
    private val cellEnter: EnterTransition
        get() = fadeIn(open()) + scaleIn(open(), initialScale = REVEALED_CELL_INITIAL_SCALE)
    private val cellExit: ExitTransition
        get() = fadeOut(close()) + scaleOut(close(), targetScale = REVEALED_CELL_INITIAL_SCALE)

    /** A whole row of hidden actions, unfolding beneath the rows above it. */
    @Composable
    fun Row(
        visible: Boolean,
        content: @Composable () -> Unit,
    ) {
        AnimatedVisibility(visible = visible, enter = rowEnter, exit = rowExit) { content() }
    }

    /** A hidden action beside a first-level one, settling into the slot it keeps in both states. */
    @Composable
    fun Cell(
        visible: Boolean,
        content: @Composable () -> Unit,
    ) {
        AnimatedVisibility(visible = visible, enter = cellEnter, exit = cellExit) { content() }
    }

    /** The disclosure chevron's turn, in step with the rows it reveals. */
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
