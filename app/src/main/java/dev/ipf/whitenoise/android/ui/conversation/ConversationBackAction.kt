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
): ConversationBackAction =
    when {
        textSelectionActive -> ConversationBackAction.CLEAR_TEXT_SELECTION
        messageSelectionActive -> ConversationBackAction.CLEAR_MESSAGE_SELECTION
        searchOpen -> ConversationBackAction.CLOSE_SEARCH
        composerFocused || imeIsOpen -> ConversationBackAction.DISMISS_COMPOSER
        else -> ConversationBackAction.NAVIGATE_UP
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

internal fun shouldClearComposerFocusAfterImeDismissal(
    wasImeTargetOpen: Boolean,
    imeTargetIsOpen: Boolean,
    composerFocused: Boolean,
    lifecycleResumed: Boolean,
): Boolean = wasImeTargetOpen && !imeTargetIsOpen && composerFocused && lifecycleResumed
