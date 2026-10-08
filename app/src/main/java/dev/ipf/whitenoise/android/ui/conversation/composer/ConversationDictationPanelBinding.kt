package dev.ipf.whitenoise.android.ui.conversation.composer

/** Disposed composer callbacks cannot act on a recovery panel from another account or conversation. */
internal class ConversationDictationPanelBinding {
    var active = true
        private set

    fun dispose() {
        active = false
    }
}
