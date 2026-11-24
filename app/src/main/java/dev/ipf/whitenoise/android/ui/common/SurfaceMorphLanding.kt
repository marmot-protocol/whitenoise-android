package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Rect

/** Only a change to a measured endpoint or viewport picks a new landing. */
internal class SurfaceMorphLanding {
    private var sceneHost = Rect.Zero
    private var sceneCards: List<SurfaceMorphCard>? = null
    private var scenePen: SurfaceMorphSource? = null
    private var scene: SurfaceMorphGeometry? = null

    fun measure(
        host: Rect,
        cards: List<SurfaceMorphCard>,
        source: SurfaceMorphSource?,
    ): SurfaceMorphGeometry? {
        if (host != sceneHost || cards !== sceneCards || source !== scenePen) {
            sceneHost = host
            sceneCards = cards
            scenePen = source
            val landing =
                cards
                    .asSequence()
                    .filter { it.visibleBounds.visibleIn(host) }
                    .maxByOrNull { it.visibleBounds.bottom }
            scene = source?.bubbleTo(landing, host)
        }
        return scene
    }
}

/** Source and landing bounds are measured in screen coordinates, then rebased to their receiving host. */
internal data class SurfaceMorphSource(
    val bounds: Rect,
    val surface: ControlMorphSurface,
) {
    fun bubbleTo(
        landing: SurfaceMorphCard?,
        host: Rect,
    ): SurfaceMorphGeometry? {
        if (landing == null || !bounds.visibleIn(host) || !landing.visibleBounds.visibleIn(host)) return null
        return SurfaceMorphGeometry(bounds.translate(-host.topLeft), landing.translate(-host.topLeft), surface)
    }
}

internal fun Rect.visibleIn(host: Rect): Boolean =
    listOf(left, top, right, bottom).all { it.isFinite() } &&
        !isEmpty &&
        !intersect(host).isEmpty
