package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard

/** Caches the native landing geometry until its owning route reports a changed destination. */
internal class SettingsBubbleCardCache {
    private var bubbleRevision by mutableIntStateOf(0)
    private var cachedBubbleRevision = -1
    private var cachedBubbleCards: List<SurfaceMorphCard> = emptyList()

    fun cards(destinations: Collection<SettingsRouteEndpoint>): List<SurfaceMorphCard> {
        if (cachedBubbleRevision != bubbleRevision) {
            cachedBubbleRevision = bubbleRevision
            cachedBubbleCards = destinations.map { it.bubbleCard() }
        }
        return cachedBubbleCards
    }

    fun invalidate() {
        bubbleRevision++
    }
}
