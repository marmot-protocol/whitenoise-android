package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp

/**
 * The live endpoints of the top-level Chats/Settings pair, in screen coordinates: the chat-list
 * [avatar] that flies into the Settings profile card's [avatarTarget], and the New message [pen] that
 * morphs into the bottom-most card. Geometry and a display-list layer only; nothing is captured.
 * While a leg draws them, [hideAvatar] and [hidePen] keep the live controls from showing twice; both
 * are state, so a control drawn before its leg began redraws hidden.
 */
internal class SettingsTopLevelHandoff {
    var avatar: SettingsAvatarSource? = null
    var avatarTarget: Rect? = null
    var pen: SettingsPenSource? = null
    var hideAvatar: () -> Boolean by mutableStateOf({ false })
    var hidePen: () -> Boolean by mutableStateOf({ false })
}

/** The chat-list avatar's live drawing: its [layer], the layer's [layerBounds] and the visible circle's [bounds]. */
internal class SettingsAvatarSource(
    val layer: GraphicsLayer,
) {
    var layerBounds: Rect = Rect.Zero
    var bounds: Rect = Rect.Zero
}

/** The New message pen: its [bounds], corner [radius], [fill] and [border]. */
internal data class SettingsPenSource(
    val bounds: Rect,
    val radius: Float,
    val fill: Color,
    val border: Color,
)

/** The flying avatar in host coordinates: its source layer and the circle it leaves and lands in. */
internal class SettingsAvatarFlight(
    val layer: GraphicsLayer,
    val layerBounds: Rect,
    val from: Rect,
    val to: Rect,
)

/** What one top-level frame draws besides the fading page: up to two bubbles and the flying avatar. */
internal class SettingsBubbleScene(
    val bubbles: List<SettingsBubbleGeometry> = emptyList(),
    val flight: SettingsAvatarFlight? = null,
)

/**
 * The top-level scene in [host] coordinates. An [origin] that is the chat-list avatar flies it into
 * the profile card's [avatarTarget] while that card's outline grows around it; any other origin grows
 * into its nearest card. The pen, when on screen, morphs into the bottom-most visible card unless the
 * origin already lands there. Without a pen that card just fades in place.
 */
internal fun settingsBubbleScene(
    host: Rect,
    origin: Rect?,
    cards: List<SettingsBubbleCard>,
    handoff: SettingsTopLevelHandoff?,
    avatarTarget: Rect?,
): SettingsBubbleScene {
    val visible = cards.filter { it.visibleBounds.visibleIn(host) }
    val avatar = handoff?.avatar?.takeIf { origin != null && it.bounds.contains(origin.center) }
    val target = avatarTarget?.takeIf { avatar != null && it.visibleIn(host) }
    val profile = target?.let { t -> visible.firstOrNull { it.visibleBounds.contains(t.center) } }
    val originCard = profile ?: origin?.let { nearestSettingsBubbleCard(visible, it, host) }
    val bottom = visible.maxByOrNull { it.visibleBounds.bottom }?.takeIf { it != originCard }
    val pen = handoff?.pen?.takeIf { bottom != null && it.bounds.visibleIn(host) }
    val shift = -host.topLeft
    val bubbles =
        listOfNotNull(
            originCard?.let { SettingsBubbleGeometry(checkNotNull(origin).translate(shift), it.translate(shift)) },
            pen?.let {
                val control = SettingsBubbleControl(it.radius, it.fill, it.border)
                SettingsBubbleGeometry(it.bounds.translate(shift), checkNotNull(bottom).translate(shift), control)
            },
        )
    val flight =
        if (avatar != null && target != null && profile != null) {
            val layerBounds = avatar.layerBounds.translate(shift)
            SettingsAvatarFlight(avatar.layer, layerBounds, avatar.bounds.translate(shift), target.translate(shift))
        } else {
            null
        }
    return SettingsBubbleScene(bubbles, flight)
}

/** The flying avatar's circle at [presence], moving and scaling on the bubble's clock. */
internal fun settingsAvatarFlightBounds(
    flight: SettingsAvatarFlight,
    presence: Float,
): Rect = lerp(flight.from, flight.to, presence.coerceIn(0f, 1f))

/** Draws the avatar's live layer moved and scaled from its circle to [settingsAvatarFlightBounds], clipped to it. */
internal fun DrawScope.drawSettingsAvatarFlight(
    flight: SettingsAvatarFlight,
    presence: Float,
) {
    val circle = settingsAvatarFlightBounds(flight, presence)
    if (flight.from.width <= 0f || circle.isEmpty) return
    val scale = circle.width / flight.from.width
    val inset = flight.from.topLeft - flight.layerBounds.topLeft
    val clip = Path().apply { addRoundRect(RoundRect(circle, CornerRadius(circle.width / 2f))) }
    clipPath(clip) {
        translate(circle.left - inset.x * scale, circle.top - inset.y * scale) {
            scale(scale, Offset.Zero) { drawLayer(flight.layer) }
        }
    }
}

/**
 * Offers this chat-list avatar's live drawing to the top-level Settings leg that flies it. [inset]
 * trims the touch target to the visible circle. While the leg draws it, the avatar is not drawn here.
 */
@Composable
internal fun Modifier.settingsAvatarFlightSource(inset: Dp): Modifier {
    val handoff = LocalSettingsCardMorph.current?.takeIf { it.page != null }?.topLevel ?: return this
    val view = LocalView.current
    val insetPx = with(LocalDensity.current) { inset.toPx() }
    val layer = rememberGraphicsLayer()
    val source = remember(handoff, layer) { SettingsAvatarSource(layer) }
    DisposableEffect(handoff, source) {
        handoff.avatar = source
        onDispose { if (handoff.avatar === source) handoff.avatar = null }
    }
    return onGloballyPositioned {
        source.layerBounds = it.settingsMorphBounds(view)
        source.bounds = source.layerBounds.deflate(insetPx)
    }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        if (!handoff.hideAvatar()) drawLayer(layer)
    }
}

/** Marks the Settings profile avatar, where the flying chat-list avatar lands. */
@Composable
internal fun Modifier.settingsAvatarFlightTarget(): Modifier {
    val handoff =
        LocalSettingsCardMorph.current
            ?.root
            ?.takeIf { it.page != null }
            ?.topLevel ?: return this
    val view = LocalView.current
    val token = remember(handoff) { arrayOf<Rect?>(null) }
    DisposableEffect(handoff, token) {
        onDispose { if (handoff.avatarTarget === token[0]) handoff.avatarTarget = null }
    }
    return onGloballyPositioned {
        val bounds = it.settingsMorphBounds(view)
        token[0] = bounds
        handoff.avatarTarget = bounds
    }
}

/**
 * Offers the New message pen to the top-level Settings leg that morphs it into the bottom card; while
 * the leg draws it, the pen is not drawn here. Without [enabled] (another control) nothing is offered.
 */
@Composable
internal fun Modifier.settingsPenSource(
    enabled: Boolean,
    radius: Dp,
    fill: Color,
    border: Color,
): Modifier {
    val handoff = LocalSettingsCardMorph.current?.takeIf { enabled && it.page != null }?.topLevel ?: return this
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val token = remember(handoff) { arrayOf<SettingsPenSource?>(null) }
    DisposableEffect(handoff, token) {
        onDispose { if (handoff.pen === token[0]) handoff.pen = null }
    }
    return onGloballyPositioned {
        val pen = SettingsPenSource(it.settingsMorphBounds(view), radiusPx, fill, border)
        token[0] = pen
        handoff.pen = pen
    }.drawWithContent { if (!handoff.hidePen()) drawContent() }
}
