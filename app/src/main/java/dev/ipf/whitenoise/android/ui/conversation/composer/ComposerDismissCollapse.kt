package dev.ipf.whitenoise.android.ui.conversation.composer

/**
 * Whether the pill lays out its editing row. A dismiss in progress treats lingering focus as already
 * gone, so the compact row starts animating with the keyboard instead of after it; text, a forced
 * layout or a non-automatic height still keep the editing row.
 */
internal fun composerEditingRequested(
    focused: Boolean,
    hasText: Boolean,
    forceEditingLayout: Boolean,
    mode: ComposerExpansionMode,
    dismissInProgress: Boolean,
): Boolean =
    (focused && !dismissInProgress) ||
        hasText ||
        forceEditingLayout ||
        mode != ComposerExpansionMode.Automatic
