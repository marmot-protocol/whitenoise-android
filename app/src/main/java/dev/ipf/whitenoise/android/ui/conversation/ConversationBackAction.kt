package dev.ipf.whitenoise.android.ui.conversation

internal enum class ConversationBackAction {
    CLEAR_TEXT_SELECTION,
    CLEAR_MESSAGE_SELECTION,
    CLOSE_SEARCH,
    DISMISS_COMPOSER,
    NAVIGATE_UP,
}

internal fun conversationBackAction(
    textSelectionActive: Boolean,
    messageSelectionActive: Boolean,
    searchOpen: Boolean,
    composerFocused: Boolean,
    imeIsOpen: Boolean,
    composerDismissInProgress: Boolean,
): ConversationBackAction =
    when {
        textSelectionActive -> ConversationBackAction.CLEAR_TEXT_SELECTION
        messageSelectionActive -> ConversationBackAction.CLEAR_MESSAGE_SELECTION
        searchOpen -> ConversationBackAction.CLOSE_SEARCH
        composerDismissInProgress -> ConversationBackAction.NAVIGATE_UP
        composerFocused || imeIsOpen -> ConversationBackAction.DISMISS_COMPOSER
        else -> ConversationBackAction.NAVIGATE_UP
    }

/** Wait for inset geometry to settle without issuing per-frame scroll writes. */
internal suspend fun awaitStableImeInset(
    maxFrames: Int,
    stableFramesRequired: Int = 2,
    readInset: () -> Int,
    awaitFrame: suspend () -> Unit,
): Boolean {
    var previousInset = readInset()
    var stableFrames = 0
    repeat(maxFrames.coerceAtLeast(1)) {
        awaitFrame()
        val inset = readInset()
        stableFrames = if (inset == previousInset) stableFrames + 1 else 0
        if (stableFrames >= stableFramesRequired.coerceAtLeast(1)) return true
        previousInset = inset
    }
    return false
}

internal enum class ComposerPreImeBackAction {
    IGNORE,
    CONSUME,
    DISMISS,
}

internal fun composerPreImeBackAction(
    enabled: Boolean,
    isBackKey: Boolean,
    isKeyDown: Boolean,
): ComposerPreImeBackAction =
    when {
        !enabled || !isBackKey -> ComposerPreImeBackAction.IGNORE
        isKeyDown -> ComposerPreImeBackAction.DISMISS
        else -> ComposerPreImeBackAction.CONSUME
    }
