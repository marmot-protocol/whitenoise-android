package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.drawControlMorphOutline

/** Chats is covered by the Settings backplate from this presence on: it fades out quickly. */
private const val SETTINGS_BUBBLE_UNDERLAY_FADE_END = 0.5f

/** The landing card's own content cross-fades in, at its final size inside the bubble, over this presence. */
private const val BUBBLE_CARD_FADE_START = 0.25f
private const val BUBBLE_CARD_FADE_END = 0.8f

/** The rest of Settings (its header and the other cards) fades in in place from this presence. */
private const val SETTINGS_BUBBLE_PAGE_FADE_START = 0.35f

/** A control bubble's pen is gone by this presence, as the pen's is entering the composer. */
private const val SETTINGS_BUBBLE_GLYPH_FADE_END = 0.5f

private val SettingsBubbleGlyphSize = 24.dp

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

/** A control a bubble leaves as itself (the New message pen): its corner [radius], [fill] and [border]. */
internal data class SettingsBubbleControl(
    val radius: Float,
    val fill: Color,
    val border: Color,
)

/**
 * One bubble in host coordinates: the origin it leaves and the card it lands on. Without a [control]
 * it leaves the origin's circle as a bare outline; with one it starts as that control, pen and all.
 */
internal class SettingsBubbleGeometry(
    val origin: Rect,
    val card: SettingsBubbleCard,
    val control: SettingsBubbleControl? = null,
)

/**
 * Paint for the top-level pair: the Settings [backplate], the hairline [edge] of a borderless card's
 * bubble and the pen [glyph] a control bubble fades out.
 */
internal data class SettingsBubbleColors(
    val backplate: Color,
    val edge: Color,
    val glyph: Painter? = null,
    val glyphTint: Color = Color.Unspecified,
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
    val from = geometry.control?.radius ?: (minOf(origin.width, origin.height) / 2f)
    val radius = (from + (geometry.card.radius - from) * p).coerceIn(0f, minOf(bounds.width, bounds.height) / 2f)
    return RoundRect(bounds, CornerRadius(radius))
}

/**
 * One frame of the top-level pair over the backplate. Settings [content] outside the [scene]'s
 * landing cards fades in in place while each bubble carries its card, and the flying avatar lands in
 * the profile card's avatar, which stays out of the content until it lands. [ink] scales every
 * Settings ink alpha (a reopen after a close that drew none).
 */
internal fun DrawScope.drawSettingsBubbleFrame(
    scene: SettingsBubbleScene,
    presence: Float,
    colors: SettingsBubbleColors,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val page = settingsBubblePageAlpha(presence) * ink
    val whole = Rect(Offset.Zero, size)
    if (content != null && page > 0f) {
        val outside =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(whole)
                scene.bubbles.forEach { addRect(it.card.visibleBounds) }
            }
        clipPath(outside) { drawAtAlpha(whole, page, content) }
    }
    val landing = scene.flight?.to
    val cardContent: (DrawScope.() -> Unit)? =
        content?.let { draw ->
            if (landing == null) {
                draw
            } else {
                {
                    val hole = Path().apply { addOval(landing) }
                    clipPath(hole, ClipOp.Difference) { draw() }
                }
            }
        }
    scene.bubbles.forEach { drawClippedBubble(it, presence, colors, ink, cardContent) }
    scene.flight?.let { drawSettingsAvatarFlight(it, presence) }
}

/**
 * One bubble, clipped like its card. A card its viewport partly clips keeps that clip at rest; the
 * bubble leaves it as it leaves the card, and the card's hidden strips stay hidden throughout, so no
 * frame paints its chrome over the header.
 */
private fun DrawScope.drawClippedBubble(
    geometry: SettingsBubbleGeometry,
    presence: Float,
    colors: SettingsBubbleColors,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val card = geometry.card.visibleBounds
    val clip = lerp(Rect(Offset.Zero, size), card, presence.coerceIn(0f, 1f))
    val full = geometry.card.bounds
    clipRect(clip.left, clip.top, clip.right, clip.bottom) {
        clipRect(full.left, full.top, full.right, card.top, ClipOp.Difference) {
            clipRect(full.left, card.bottom, full.right, full.bottom, ClipOp.Difference) {
                drawSettingsBubble(geometry, presence, colors, ink, content)
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
 * bubble, never scaled. A control bubble starts as the control, its pen fading out as the content
 * fades in. A borderless card hands its edge and fill over to the native card at rest.
 */
private fun DrawScope.drawSettingsBubble(
    geometry: SettingsBubbleGeometry,
    presence: Float,
    colors: SettingsBubbleColors,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val shape = settingsBubbleShape(geometry, presence)
    val card = geometry.card
    val control = geometry.control
    val p = presence.coerceIn(0f, 1f)
    val handover = if (card.outline.isSpecified) 0f else ramp(presence, SETTINGS_BUBBLE_HANDOVER_START, 1f)
    val path = Path().apply { addRoundRect(shape) }
    val fill = (if (control != null) 1f else settingsBubbleUnderlayCover(presence)) * (1f - handover)
    val fillColor = if (control != null) lerp(control.fill, card.fill, p) else card.fill
    if (fill > 0f) drawPath(path, fillColor, alpha = fill)
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
    val resting = if (card.outline.isSpecified) card.outline else colors.edge
    val color = if (control != null) lerp(control.border, resting, p) else resting
    if (handover < 1f) drawControlMorphOutline(bounds, shape.topLeftCornerRadius.x, color, card.stroke, 1f - handover)
    val glyph = colors.glyph?.takeIf { control != null } ?: return
    val glyphAlpha = 1f - ramp(presence, 0f, SETTINGS_BUBBLE_GLYPH_FADE_END)
    if (glyphAlpha <= 0f) return
    val glyphSize = SettingsBubbleGlyphSize.toPx()
    translate(bounds.center.x - glyphSize / 2f, bounds.center.y - glyphSize / 2f) {
        with(glyph) { draw(Size(glyphSize, glyphSize), glyphAlpha, ColorFilter.tint(colors.glyphTint)) }
    }
}
