package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphLanding
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphSource
import dev.ipf.whitenoise.android.ui.common.motionBoundsOnScreen

/** The live Home pen can supply one outline to the top-level Settings route. */
internal class SettingsTopLevelHandoff {
    var pen: SurfaceMorphSource? by mutableStateOf(null)
    var hidePen: () -> Boolean by mutableStateOf({ false })
    private val landing = SurfaceMorphLanding()

    fun bubble(
        host: Rect,
        cards: List<SurfaceMorphCard>,
    ) = landing.measure(host, cards, pen)
}

/** The relay warning is not a pen and does not supply an outline. */
@Composable
internal fun Modifier.settingsPenSource(
    enabled: Boolean,
    radius: Dp,
    fill: Color,
    border: Color,
): Modifier {
    val handoff = LocalSettingsCardMorph.current?.takeIf { enabled && it.page != null }?.topLevel ?: return this
    val view = LocalView.current
    val surface = with(LocalDensity.current) { ControlMorphSurface(fill, border, radius.toPx(), 1.dp.toPx(), 1f) }
    val token = remember(handoff) { arrayOf<SurfaceMorphSource?>(null) }
    DisposableEffect(handoff, token) {
        onDispose { if (handoff.pen === token[0]) handoff.pen = null }
    }
    return onGloballyPositioned {
        val pen = SurfaceMorphSource(it.motionBoundsOnScreen(view), surface)
        if (token[0] != pen) {
            token[0] = pen
            handoff.pen = pen
        }
    }.drawWithContent { if (!handoff.hidePen()) drawContent() }
}
