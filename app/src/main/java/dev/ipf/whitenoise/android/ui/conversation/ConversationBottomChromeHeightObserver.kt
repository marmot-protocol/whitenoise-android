package dev.ipf.whitenoise.android.ui.conversation

internal class ConversationBottomChromeHeightObserver {
    private var measuredHeightPx: Int? = null

    fun onMeasured(heightPx: Int): Boolean {
        val previousHeightPx = measuredHeightPx
        measuredHeightPx = heightPx
        return previousHeightPx != null && previousHeightPx != heightPx
    }
}
