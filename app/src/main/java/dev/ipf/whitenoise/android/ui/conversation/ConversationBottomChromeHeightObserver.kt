package dev.ipf.whitenoise.android.ui.conversation

internal class ConversationBottomChromeHeightObserver {
    private var measured = false
    var currentHeightPx: Int = 0
        private set

    fun onMeasured(heightPx: Int): Boolean {
        val changed = measured && currentHeightPx != heightPx
        currentHeightPx = heightPx
        measured = true
        return changed
    }
}
