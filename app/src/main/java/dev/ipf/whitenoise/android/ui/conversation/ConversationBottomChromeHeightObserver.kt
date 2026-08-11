package dev.ipf.whitenoise.android.ui.conversation

internal class ConversationBottomChromeHeightObserver {
    var hasMeasurement = false
        private set
    var currentHeightPx: Int = 0
        private set

    fun onMeasured(heightPx: Int): Boolean {
        val changed = hasMeasurement && currentHeightPx != heightPx
        currentHeightPx = heightPx
        hasMeasurement = true
        return changed
    }
}
