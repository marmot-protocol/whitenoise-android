package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.RetainedComposerExpansion
import dev.ipf.whitenoise.android.state.RetainedComposerExpansionMode
import kotlin.math.abs

internal const val COMPOSER_EXPANSION_ANIMATION_MILLIS = 220

internal enum class ComposerExpansionMode {
    Automatic,
    Manual,
    FullScreen,
}

internal data class ComposerExpansionState(
    val mode: ComposerExpansionMode = ComposerExpansionMode.Automatic,
    val manualHeightPx: Float? = null,
)

/** Stable account/conversation key shared by every composer surface for one draft owner. */
internal fun composerDraftOwnerKey(
    accountRef: String?,
    groupIdHex: String,
): Pair<String?, String> = accountRef to groupIdHex

/** Converts live pixels to the density-independent value safe for retained UI state. */
internal fun ComposerExpansionState.toRetainedPreference(density: Density): RetainedComposerExpansion? =
    when (mode) {
        ComposerExpansionMode.Automatic -> null
        ComposerExpansionMode.FullScreen ->
            RetainedComposerExpansion(
                mode = RetainedComposerExpansionMode.FullScreen,
                manualHeightDp = null,
            )
        ComposerExpansionMode.Manual -> {
            val heightDp =
                manualHeightPx
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?.let { with(density) { it.toDp().value } }
            heightDp?.let {
                RetainedComposerExpansion(
                    mode = RetainedComposerExpansionMode.Manual,
                    manualHeightDp = it,
                )
            }
        }
    }

/** Rehydrates retained dp geometry into the current density; viewport clamping remains live. */
internal fun RetainedComposerExpansion.toComposerExpansionState(density: Density): ComposerExpansionState =
    when (mode) {
        RetainedComposerExpansionMode.FullScreen ->
            ComposerExpansionState(mode = ComposerExpansionMode.FullScreen)
        RetainedComposerExpansionMode.Manual ->
            ComposerExpansionState(
                mode = ComposerExpansionMode.Manual,
                manualHeightPx = manualHeightDp?.let { with(density) { it.dp.toPx() } },
            )
    }

internal fun composerHeightAnimationDurationMillis(
    mode: ComposerExpansionMode,
    dragActive: Boolean,
    discreteTransitionActive: Boolean,
): Int =
    if (dragActive || (mode == ComposerExpansionMode.Automatic && !discreteTransitionActive)) {
        0
    } else {
        COMPOSER_EXPANSION_ANIMATION_MILLIS
    }

private fun normalizedMaximumHeight(maximumHeightPx: Float): Float = maximumHeightPx.coerceAtLeast(0f)

private fun normalizedMinimumHeight(
    minimumHeightPx: Float,
    maximumHeightPx: Float,
): Float = minimumHeightPx.coerceIn(0f, normalizedMaximumHeight(maximumHeightPx))

/**
 * Resolves the visible composer height. Automatic mode follows the text field;
 * manual and full-screen modes stay inside the live safe viewport.
 */
internal fun composerHeightPx(
    state: ComposerExpansionState,
    automaticHeightPx: Float,
    minimumManualHeightPx: Float,
    maximumHeightPx: Float,
): Float {
    val maximum = normalizedMaximumHeight(maximumHeightPx)
    val automatic = normalizedMinimumHeight(automaticHeightPx, maximum)
    val manualMinimum = normalizedMinimumHeight(minimumManualHeightPx, maximum)
    return when (state.mode) {
        ComposerExpansionMode.Automatic -> automatic
        ComposerExpansionMode.Manual -> state.manualHeightPx?.coerceIn(manualMinimum, maximum) ?: automatic
        ComposerExpansionMode.FullScreen -> maximum
    }
}

/** A negative vertical drag grows the composer; a positive drag shrinks it. */
internal fun dragComposerHeight(
    state: ComposerExpansionState,
    dragDeltaYPx: Float,
    automaticHeightPx: Float,
    minimumManualHeightPx: Float,
    maximumHeightPx: Float,
): ComposerExpansionState {
    val maximum = normalizedMaximumHeight(maximumHeightPx)
    val minimum = normalizedMinimumHeight(minimumManualHeightPx, maximum)
    val nextHeight =
        (
            composerHeightPx(
                state = state,
                automaticHeightPx = automaticHeightPx,
                minimumManualHeightPx = minimum,
                maximumHeightPx = maximum,
            ) - dragDeltaYPx
        ).coerceIn(minimum, maximum)
    return ComposerExpansionState(
        mode = ComposerExpansionMode.Manual,
        manualHeightPx = nextHeight,
    )
}

/**
 * Preserve the exact release height except near either endpoint, where a small
 * deadband makes the automatic and full-screen destinations easy to land on.
 */
internal fun settleComposerHeight(
    state: ComposerExpansionState,
    automaticHeightPx: Float,
    minimumManualHeightPx: Float,
    maximumHeightPx: Float,
    deadbandPx: Float,
): ComposerExpansionState {
    val maximum = normalizedMaximumHeight(maximumHeightPx)
    val automatic = normalizedMinimumHeight(automaticHeightPx, maximum)
    val minimum = normalizedMinimumHeight(minimumManualHeightPx, maximum)
    val height =
        composerHeightPx(
            state = state,
            automaticHeightPx = automatic,
            minimumManualHeightPx = minimum,
            maximumHeightPx = maximum,
        )
    return when {
        abs(height - automatic) <= deadbandPx -> ComposerExpansionState()
        abs(maximum - height) <= deadbandPx ->
            ComposerExpansionState(mode = ComposerExpansionMode.FullScreen)
        else -> ComposerExpansionState(ComposerExpansionMode.Manual, height)
    }
}

/** The accessible tap path always toggles between the current height and full screen. */
internal fun toggleComposerFullScreen(state: ComposerExpansionState): ComposerExpansionState =
    if (state.mode == ComposerExpansionMode.FullScreen) {
        ComposerExpansionState()
    } else {
        ComposerExpansionState(mode = ComposerExpansionMode.FullScreen)
    }
