package dev.ipf.whitenoise.android.ui.conversation

/**
 * Serializes the transition from a mounted conversation to the chat list.
 *
 * When the IME still owns an inset, navigation waits for the zero-inset edge.
 * This preserves the final inset dispatch before focus is detached and prevents
 * the chat list from inheriting the conversation's keyboard-sized viewport.
 */
internal class ConversationExitCoordinator {
    var awaitingImeDismiss: Boolean = false
        private set

    private var completing = false

    fun requestExit(
        imeIsOpen: Boolean,
        hideIme: () -> Unit,
        clearComposerFocus: () -> Unit,
        navigate: () -> Unit,
    ) {
        if (completing || awaitingImeDismiss) return

        hideIme()
        if (imeIsOpen) {
            awaitingImeDismiss = true
        } else {
            complete(clearComposerFocus, navigate)
        }
    }

    fun onImeVisibilityChanged(
        imeIsOpen: Boolean,
        clearComposerFocus: () -> Unit,
        navigate: () -> Unit,
    ) {
        if (!awaitingImeDismiss || imeIsOpen) return
        awaitingImeDismiss = false
        complete(clearComposerFocus, navigate)
    }

    private fun complete(
        clearComposerFocus: () -> Unit,
        navigate: () -> Unit,
    ) {
        if (completing) return
        completing = true
        try {
            clearComposerFocus()
            navigate()
        } finally {
            completing = false
        }
    }
}
