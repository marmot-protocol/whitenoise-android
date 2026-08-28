package dev.ipf.whitenoise.android.ui.conversation.composer

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

private fun normalizedMaximumHeight(maximumHeightPx: Float): Float = maximumHeightPx.coerceAtLeast(0f)

private fun normalizedMinimumHeight(
    automaticHeightPx: Float,
    maximumHeightPx: Float,
): Float = automaticHeightPx.coerceIn(0f, normalizedMaximumHeight(maximumHeightPx))

/**
 * Resolves the visible composer height. Automatic mode follows the text field;
 * manual and full-screen modes stay inside the live safe viewport.
 */
internal fun composerHeightPx(
    state: ComposerExpansionState,
    automaticHeightPx: Float,
    maximumHeightPx: Float,
): Float {
    val maximum = normalizedMaximumHeight(maximumHeightPx)
    val minimum = normalizedMinimumHeight(automaticHeightPx, maximum)
    return when (state.mode) {
        ComposerExpansionMode.Automatic -> minimum
        ComposerExpansionMode.Manual -> state.manualHeightPx?.coerceIn(minimum, maximum) ?: minimum
        ComposerExpansionMode.FullScreen -> maximum
    }
}

/** A negative vertical drag grows the composer; a positive drag shrinks it. */
internal fun dragComposerHeight(
    state: ComposerExpansionState,
    dragDeltaYPx: Float,
    automaticHeightPx: Float,
    maximumHeightPx: Float,
): ComposerExpansionState {
    val maximum = normalizedMaximumHeight(maximumHeightPx)
    val minimum = normalizedMinimumHeight(automaticHeightPx, maximum)
    val nextHeight =
        (composerHeightPx(state, minimum, maximum) - dragDeltaYPx)
            .coerceIn(minimum, maximum)
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
    maximumHeightPx: Float,
    deadbandPx: Float,
): ComposerExpansionState {
    val maximum = normalizedMaximumHeight(maximumHeightPx)
    val minimum = normalizedMinimumHeight(automaticHeightPx, maximum)
    val height = composerHeightPx(state, minimum, maximum)
    return when {
        abs(height - minimum) <= deadbandPx -> ComposerExpansionState()
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

/** Back from either user-expanded mode returns to the natural auto-grown height. */
internal fun collapseComposer(state: ComposerExpansionState): ComposerExpansionState =
    if (state.mode == ComposerExpansionMode.Automatic) state else ComposerExpansionState()
