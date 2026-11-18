package dev.ipf.whitenoise.android.ui.conversation.composer

/** Deduplicates identical measure-time caret corrections; see the caller. */
internal class ComposerLayoutCaretCorrectionGate {
    private var lastSelection: ComposerSelectionLayout? = null
    private var lastViewport: Int = -1
    private var lastMaxScroll: Int = -1

    /** Allows a layout correction only when selection or viewport geometry differs from the last correction. */
    fun shouldCorrect(
        selection: ComposerSelectionLayout,
        viewport: Int,
        maxScroll: Int,
    ): Boolean {
        val changed = selection != lastSelection || viewport != lastViewport || maxScroll != lastMaxScroll
        if (changed) {
            lastSelection = selection
            lastViewport = viewport
            lastMaxScroll = maxScroll
        }
        return changed
    }
}
