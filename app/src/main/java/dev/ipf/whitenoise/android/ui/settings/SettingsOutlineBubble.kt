package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.isSpecified
import dev.ipf.whitenoise.android.ui.common.drawControlMorphOutline

/** Chats is covered by the Settings backplate from this presence on: it fades out quickly. */
private const val SETTINGS_BUBBLE_UNDERLAY_FADE_END = 0.5f

/** The landing card's own content cross-fades in, at its final size inside the bubble, over this presence. */
private const val BUBBLE_CARD_FADE_START = 0.25f
private const val BUBBLE_CARD_FADE_END = 0.8f

/** The rest of Settings (its header and the other cards) fades in in place from this presence. */
private const val SETTINGS_BUBBLE_PAGE_FADE_START = 0.35f

/** A card that rests without a border takes over the bubble's edge and fill over this last stretch. */
private const val SETTINGS_BUBBLE_HANDOVER_START = 0.85f

/**
 * One Settings card a top-level bubble can land on, in screen coordinates: its outer [bounds], the
 * part of it on screen, its corner [radius] and its resting paint. [outline] is unspecified for a
 * card that rests without a border (light and dark themes).
 */
internal data class SettingsBubbleCard(
    val bounds: Rect,
    val visibleBounds: Rect,
    val radius: Float,
    val fill: Color,
    val outline: Color,
    val stroke: Float,
) {
    fun translate(by: Offset) = copy(bounds = bounds.translate(by), visibleBounds = visibleBounds.translate(by))
}

internal fun SettingsRouteEndpoint.bubbleCard(): SettingsBubbleCard =
    SettingsBubbleCard(bounds, visibleBounds, corners.topLeftCornerRadius.x, fill, outline, strokeWidth)

/** The cards this page, or the child route mounted inside it, last measured. */
internal fun SettingsPageMorph.bubbleCards(): List<SettingsBubbleCard> =
    childCards?.invoke()?.takeIf { it.isNotEmpty() } ?: destinations.values.map { it.bubbleCard() }

/** The card on screen in [host] nearest [origin]: the one the bubble grows into and shrinks out of. */
internal fun nearestSettingsBubbleCard(
    cards: Collection<SettingsBubbleCard>,
    origin: Rect,
    host: Rect,
): SettingsBubbleCard? {
    val point = origin.center
    return cards.filter { it.visibleBounds.visibleIn(host) }.minByOrNull { card ->
        val b = card.visibleBounds
        val dx = point.x - point.x.coerceIn(b.left, b.right)
        val dy = point.y - point.y.coerceIn(b.top, b.bottom)
        dx * dx + dy * dy
    }
}

/** One top-level leg in host coordinates: the origin control and the card the bubble lands on. */
internal class SettingsBubbleGeometry(
    val origin: Rect,
    val card: SettingsBubbleCard,
)

/** Paint for the top-level pair: the Settings [backplate] and the hairline [edge] of a borderless card's bubble. */
internal data class SettingsBubbleColors(
    val backplate: Color,
    val edge: Color,
)

private fun ramp(
    presence: Float,
    start: Float,
    end: Float,
): Float = ((presence - start) / (end - start)).coerceIn(0f, 1f)

/** How much the Settings backplate covers the live Chats route beneath it. */
internal fun settingsBubbleUnderlayCover(presence: Float): Float = ramp(presence, 0f, SETTINGS_BUBBLE_UNDERLAY_FADE_END)

/** The landing card's own content inside the bubble. */
internal fun settingsBubbleCardAlpha(presence: Float) = ramp(presence, BUBBLE_CARD_FADE_START, BUBBLE_CARD_FADE_END)

/** Everything else in Settings, at its final position. */
internal fun settingsBubblePageAlpha(presence: Float): Float = ramp(presence, SETTINGS_BUBBLE_PAGE_FADE_START, 1f)

/** The bubble at [presence]: the origin's circle at 0 and the card's resting outline at 1, on one clock. */
internal fun settingsBubbleShape(
    geometry: SettingsBubbleGeometry,
    presence: Float,
): RoundRect {
    val p = presence.coerceIn(0f, 1f)
    val origin = geometry.origin
    val bounds = lerp(origin, geometry.card.bounds, p)
    val from = minOf(origin.width, origin.height) / 2f
    val radius = (from + (geometry.card.radius - from) * p).coerceIn(0f, minOf(bounds.width, bounds.height) / 2f)
    return RoundRect(bounds, CornerRadius(radius))
}

/**
 * One frame of the top-level pair over the backplate. With [geometry], Settings [content] outside the
 * landing card fades in in place while the bubble carries the card itself; without it the whole page
 * fades. [ink] scales every Settings ink alpha (a reopen after a close that drew none).
 */
internal fun DrawScope.drawSettingsBubbleFrame(
    geometry: SettingsBubbleGeometry?,
    presence: Float,
    edge: Color,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val page = settingsBubblePageAlpha(presence) * ink
    val whole = Rect(Offset.Zero, size)
    if (geometry == null) {
        if (content != null && page > 0f) drawAtAlpha(whole, page, content)
        return
    }
    val card = geometry.card.visibleBounds
    if (content != null && page > 0f) {
        clipRect(card.left, card.top, card.right, card.bottom, ClipOp.Difference) { drawAtAlpha(whole, page, content) }
    }
    // A card its viewport partly clips keeps that clip at rest; the bubble leaves it as it leaves the card.
    // The card's hidden strips stay hidden throughout, so no frame paints its chrome over the header.
    val clip = lerp(whole, card, presence.coerceIn(0f, 1f))
    val full = geometry.card.bounds
    clipRect(clip.left, clip.top, clip.right, clip.bottom) {
        clipRect(full.left, full.top, full.right, card.top, ClipOp.Difference) {
            clipRect(full.left, card.bottom, full.right, full.bottom, ClipOp.Difference) {
                drawSettingsBubble(geometry, presence, edge, ink, content)
            }
        }
    }
}

/** The Settings ink a top-level leg turned around with: the presence it turned at and the ink drawn there. */
internal data class SettingsBubbleResume(
    val presence: Float,
    val ink: Float,
)

/**
 * The Settings ink multiplier at [presence]: whole on an uninterrupted leg. A reversed leg starts with
 * the [resume] ink it turned around with; closing keeps it, and opening blends it to whole by rest.
 */
internal fun settingsBubbleInk(
    presence: Float,
    opening: Boolean,
    resume: SettingsBubbleResume?,
): Float {
    val blend = if (resume != null && resume.presence < 1f) ramp(presence, resume.presence, 1f) else 1f
    return when {
        resume == null -> 1f
        !opening -> resume.ink
        else -> resume.ink + (1f - resume.ink) * blend
    }
}

/**
 * The bubble itself, like the New message pen's: a hairline edge inset to the card's resting border
 * over the card's own fill, with the card's content cross-fading in at its final size clipped to the
 * bubble, never scaled. A borderless card hands its edge and fill over to the native card at rest.
 */
private fun DrawScope.drawSettingsBubble(
    geometry: SettingsBubbleGeometry,
    presence: Float,
    edge: Color,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val shape = settingsBubbleShape(geometry, presence)
    val card = geometry.card
    val handover = if (card.outline.isSpecified) 0f else ramp(presence, SETTINGS_BUBBLE_HANDOVER_START, 1f)
    val path = Path().apply { addRoundRect(shape) }
    val fill = settingsBubbleUnderlayCover(presence) * (1f - handover)
    if (fill > 0f) drawPath(path, card.fill, alpha = fill)
    val cardInk = settingsBubbleCardAlpha(presence) * ink
    if (content != null && cardInk > 0f) {
        // The bubble's edge stands in for the card's own border, which stays out (antialiasing included) until rest.
        val inset = if (card.outline.isSpecified) card.stroke * 2f else 0f
        val corner = CornerRadius((card.radius - inset).coerceAtLeast(0f))
        val interior = Path().apply { addRoundRect(RoundRect(card.bounds.deflate(inset), corner)) }
        val visible = card.visibleBounds
        clipRect(visible.left, visible.top, visible.right, visible.bottom) {
            clipPath(path) { clipPath(interior) { drawAtAlpha(card.bounds, cardInk, content) } }
        }
    }
    val bounds = Rect(shape.left, shape.top, shape.right, shape.bottom)
    val color = if (card.outline.isSpecified) card.outline else edge
    if (handover < 1f) drawControlMorphOutline(bounds, shape.topLeftCornerRadius.x, color, card.stroke, 1f - handover)
}
