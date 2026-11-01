package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen-owned switches survive lazy-item disposal; replacing the account/runtime creates fresh state. */
internal class AppearancePreviewState {
    var rtl by mutableStateOf(false)
    var largeText by mutableStateOf(false)

    /** Restores inherited layout and density without changing any saved appearance preference. */
    fun reset() {
        rtl = false
        largeText = false
    }
}
