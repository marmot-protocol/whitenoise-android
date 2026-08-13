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

/**
 * Wait until the IME inset reaches its animation target and holds there,
 * without issuing per-frame scroll writes.
 *
 * Frame-stability alone cannot tell a finished keyboard animation from a
 * gesture-driven drag whose finger merely paused — two equal frames read as
 * "settled" mid-gesture, and the scroll write that follows yanks the list
 * while the user still owns the inset. The animation target only matches the
 * live inset once the IME genuinely stops, so waiting for that equality can
 * never authorize a write inside an active drag. No frame cap: a drag may
 * hold indefinitely, and releasing or cancelling it always converges the
 * inset onto a target, so the wait ends with the gesture. Callers scope this
 * to the IME-open edge, which cancels the wait if the keyboard closes.
 */
internal suspend fun awaitImeInsetAtTarget(
    stableFramesRequired: Int = 2,
    readInset: () -> Int,
    readTargetInset: () -> Int,
    awaitFrame: suspend () -> Unit,
) {
    var stableFrames = 0
    while (true) {
        awaitFrame()
        stableFrames =
            if (readInset() == readTargetInset()) stableFrames + 1 else 0
        if (stableFrames >= stableFramesRequired.coerceAtLeast(1)) return
    }
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
