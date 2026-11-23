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

/** The live Home pen can supply one outline to the top-level Settings route. */
internal class SettingsTopLevelHandoff {
    var pen: SettingsBubbleSource? by mutableStateOf(null)
    var hidePen: () -> Boolean by mutableStateOf({ false })
    private val landing = SettingsBubbleLanding()

    fun bubble(
        host: Rect,
        cards: List<SettingsBubbleCard>,
    ) = landing.measure(host, cards, pen)
}

/** Only a change to a measured endpoint or viewport picks a new landing. */
internal class SettingsBubbleLanding {
    private var sceneHost = Rect.Zero
    private var sceneCards: List<SettingsBubbleCard>? = null
    private var scenePen: SettingsBubbleSource? = null
    private var scene: SettingsBubbleGeometry? = null

    fun measure(
        host: Rect,
        cards: List<SettingsBubbleCard>,
        source: SettingsBubbleSource?,
    ): SettingsBubbleGeometry? {
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
internal data class SettingsBubbleSource(
    val bounds: Rect,
    val surface: ControlMorphSurface,
) {
    fun bubbleTo(
        landing: SettingsBubbleCard?,
        host: Rect,
    ): SettingsBubbleGeometry? {
        if (landing == null || !bounds.visibleIn(host) || !landing.visibleBounds.visibleIn(host)) return null
        return SettingsBubbleGeometry(bounds.translate(-host.topLeft), landing.translate(-host.topLeft), surface)
    }
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
    val token = remember(handoff) { arrayOf<SettingsBubbleSource?>(null) }
    DisposableEffect(handoff, token) {
        onDispose { if (handoff.pen === token[0]) handoff.pen = null }
    }
    return onGloballyPositioned {
        val pen = SettingsBubbleSource(it.settingsMorphBounds(view), surface)
        if (token[0] != pen) {
            token[0] = pen
            handoff.pen = pen
        }
    }.drawWithContent { if (!handoff.hidePen()) drawContent() }
}
