package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard

internal fun SettingsRouteEndpoint.bubbleCard(): SurfaceMorphCard =
    SurfaceMorphCard(bounds, visibleBounds, corners.topLeftCornerRadius.x, fill, outline, strokeWidth)
