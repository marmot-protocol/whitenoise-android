package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter

/** The source is covered by the arrival backplate from this presence on: it fades out quickly. */
private const val UNDERLAY_FADE_END = 0.5f

/** The landing card's own content cross-fades in, at its final size inside the bubble, over this presence. */
private const val BUBBLE_CARD_FADE_START = 0.25f
private const val BUBBLE_CARD_FADE_END = 0.8f

/** A control bubble's pen is gone by this presence, as the pen's is entering the composer. */
private const val GLYPH_FADE_END = 0.5f

/** A card that rests without a border takes over the bubble's edge and fill over this last stretch. */
private const val OUTLINE_HANDOVER_START = 0.85f
private const val LANDING_CLIP_READY = 0.5f

/**
 * One native card a surface can land on, in screen coordinates: its outer [bounds], the
 * part of it on screen, its corner [radius] and its resting paint. [outline] is unspecified for a
 * card that rests without a border (light and dark themes).
 */
internal data class SurfaceMorphCard(
    val bounds: Rect,
    val visibleBounds: Rect,
    val radius: Float,
    val fill: Color,
    val outline: Color,
    val stroke: Float,
) {
    fun translate(by: Offset) = copy(bounds = bounds.translate(by), visibleBounds = visibleBounds.translate(by))
}

/** The measured source and native landing group, in the receiving host's coordinates. */
internal class SurfaceMorphGeometry(
    val origin: Rect,
    val card: SurfaceMorphCard,
    val control: ControlMorphSurface,
)

/**
 * Paint for the handoff: the destination [backplate], the hairline [edge] of a borderless card's
 * bubble and the pen [glyph] that fades out.
 */
internal data class SurfaceMorphColors(
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

/** How much the destination backplate covers the live route beneath it. */
internal fun surfaceMorphUnderlayCover(presence: Float): Float = ramp(presence, 0f, UNDERLAY_FADE_END)

/** The landing card's own content inside the bubble. */
internal fun surfaceMorphCardAlpha(presence: Float) = ramp(presence, BUBBLE_CARD_FADE_START, BUBBLE_CARD_FADE_END)

/** The bubble at [presence]: the origin's circle at 0 and the card's resting outline at 1, on one clock. */
internal fun surfaceMorphShape(
    geometry: SurfaceMorphGeometry,
    presence: Float,
): RoundRect {
    val p = presence.coerceIn(0f, 1f)
    val origin = geometry.origin
    val bounds = lerp(origin, geometry.card.bounds, p)
    val from = geometry.control.radius
    val radius = (from + (geometry.card.radius - from) * p).coerceIn(0f, minOf(bounds.width, bounds.height) / 2f)
    return RoundRect(bounds, CornerRadius(radius))
}

/**
 * One bubble, clipped like its card. A card its viewport partly clips keeps that clip at rest; the
 * bubble leaves it as it leaves the card, and the card's hidden strips stay hidden throughout, so no
 * frame paints its chrome over the header.
 */
internal fun DrawScope.drawClippedSurfaceMorph(
    geometry: SurfaceMorphGeometry,
    presence: Float,
    colors: SurfaceMorphColors,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val card = geometry.card.visibleBounds
    val clip = lerp(Rect(Offset.Zero, size), card, presence.coerceIn(0f, 1f))
    val full = geometry.card.bounds
    val landingClip = ramp(presence, 0f, LANDING_CLIP_READY)
    val top = card.top - (card.top - geometry.origin.top).coerceAtLeast(0f) * (1f - landingClip)
    val bottom = card.bottom + (geometry.origin.bottom - card.bottom).coerceAtLeast(0f) * (1f - landingClip)
    clipRect(clip.left, clip.top, clip.right, clip.bottom) {
        clipRect(full.left, full.top, full.right, top.coerceAtLeast(full.top), ClipOp.Difference) {
            clipRect(full.left, bottom.coerceAtMost(full.bottom), full.right, full.bottom, ClipOp.Difference) {
                drawSurfaceMorph(geometry, presence, colors, ink, content)
            }
        }
    }
}

/** The content ink a transition turned around with: the presence it turned at and the ink drawn there. */
internal data class SurfaceMorphResume(
    val presence: Float,
    val ink: Float,
)

/**
 * The content ink multiplier at [presence]: whole on an uninterrupted leg. A reversed leg starts with
 * the [resume] ink it turned around with; closing keeps it, and opening blends it to whole by rest.
 */
internal fun surfaceMorphInk(
    presence: Float,
    opening: Boolean,
    resume: SurfaceMorphResume?,
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
private fun DrawScope.drawSurfaceMorph(
    geometry: SurfaceMorphGeometry,
    presence: Float,
    colors: SurfaceMorphColors,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val shape = surfaceMorphShape(geometry, presence)
    val card = geometry.card
    val control = geometry.control
    val p = presence.coerceIn(0f, 1f)
    val handover = if (card.outline.isSpecified) 0f else ramp(presence, OUTLINE_HANDOVER_START, 1f)
    val path = Path().apply { addRoundRect(shape) }
    val fill = 1f - handover
    val fillColor = lerp(control.fill, card.fill, p)
    if (fill > 0f) drawPath(path, fillColor, alpha = fill)
    val cardInk = surfaceMorphCardAlpha(presence) * ink
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
    val color = lerp(control.border ?: resting, resting, p)
    val stroke = control.stroke + (card.stroke - control.stroke) * p
    if (handover < 1f) drawControlMorphOutline(bounds, shape.topLeftCornerRadius.x, color, stroke, fill)
    colors.glyph?.let { glyph ->
        val alpha = control.decorationAlpha * (1f - ramp(presence, 0f, GLYPH_FADE_END))
        drawControlMorphPen(bounds, glyph, colors.glyphTint, alpha)
    }
}

private const val PAGE_FADE_START = 0.35f

/** Paints the native page once around the landing card and again only inside its moving outline. */
internal fun DrawScope.drawSurfaceArrival(
    landing: SurfaceMorphGeometry?,
    presence: Float,
    colors: SurfaceMorphColors,
    ink: Float,
    pageAlpha: Float =
        if (landing == null) {
            ((presence - PAGE_FADE_START) / (1f - PAGE_FADE_START)).coerceAtLeast(0f)
        } else {
            presence
        },
    content: (DrawScope.() -> Unit)?,
) {
    val effectivePageAlpha = pageAlpha * ink
    if (content != null && effectivePageAlpha > 0f) {
        drawAtAlpha(Rect(0f, 0f, size.width, size.height), effectivePageAlpha) {
            val card = landing?.card?.visibleBounds
            if (card == null) {
                content()
            } else {
                clipRect(card.left, card.top, card.right, card.bottom, ClipOp.Difference, content)
            }
        }
    }
    landing?.let { drawClippedSurfaceMorph(it, presence, colors, ink, content) }
}

/** Draws [block] composited at [alpha] within [bounds]. */
internal fun DrawScope.drawAtAlpha(
    bounds: Rect,
    alpha: Float,
    block: DrawScope.() -> Unit,
) {
    val layered = alpha < 1f
    if (layered) drawIntoCanvas { it.saveLayer(bounds, Paint().apply { this.alpha = alpha }) }
    block()
    if (layered) drawIntoCanvas { it.restore() }
}
