package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal const val SETTINGS_OUTGOING_PHASE_END = 0.4f

/** Geometry only: no pictures, navigation, clock or application state. All coordinates are screen pixels. */
internal data class SettingsContourSeam(
    val start: Offset,
    val end: Offset,
    val color: Color,
    val width: Float = 1f,
)

internal data class SettingsNativeRow(
    val bounds: Rect,
    val corners: RoundRect,
    val first: Boolean,
    val last: Boolean,
)

internal data class SettingsContour(
    val bounds: Rect,
    val corners: RoundRect,
    val viewport: Rect,
    val color: Color,
    val width: Float,
    val seams: List<SettingsContourSeam> = emptyList(),
    val rows: List<SettingsNativeRow> = emptyList(),
    // Native route groups and frozen frames retain the same correspondence.
    val key: Int = 0,
) {
    // Paint alpha is carried by each frozen color; no independent route-alpha owner.
    val modulationAlpha: Float get() = 1f
}

/** Reject layouts outside this solver's vertical, nonoverlapping contract rather than crossing boxes. */
internal fun orderedSettingsContours(contours: List<SettingsContour>): List<SettingsContour>? {
    val ordered = contours.distinctBy { it.bounds }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    if (ordered.any { !it.hasValidGeometry() }) return null
    return ordered.takeIf { it.zipWithNext().all { (a, b) -> a.bounds.bottom <= b.bounds.top } }
}

/** Finite native geometry is the solver's admission boundary. */
private fun SettingsContour.hasValidGeometry(): Boolean {
    val b = bounds
    val r = corners
    val finiteCoordinates =
        listOf(
            b.left,
            b.top,
            b.right,
            b.bottom,
            width,
            r.topLeftCornerRadius.x,
            r.topLeftCornerRadius.y,
            r.topRightCornerRadius.x,
            r.topRightCornerRadius.y,
            r.bottomLeftCornerRadius.x,
            r.bottomLeftCornerRadius.y,
            r.bottomRightCornerRadius.x,
            r.bottomRightCornerRadius.y,
            viewport.left,
            viewport.top,
            viewport.right,
            viewport.bottom,
        ).all { it.isFinite() }
    val nonnegativeDimensions = b.width >= 0f && b.height >= 0f && width >= 0f
    val finiteSeams =
        seams.all { seam ->
            listOf(seam.start.x, seam.start.y, seam.end.x, seam.end.y, seam.width).all { it.isFinite() }
        }
    return finiteCoordinates && nonnegativeDimensions && finiteSeams
}

/** Only genuinely borderless endpoints use a point origin. */
internal fun settingsContourSeed(
    contour: SettingsContour?,
    point: Offset,
    viewport: Rect,
): SettingsContour {
    val seed = contour?.bounds?.let { Offset(it.center.x, it.bottom) } ?: point
    val bounds = Rect(seed, seed)
    return SettingsContour(
        bounds,
        RoundRect(bounds),
        contour?.viewport ?: viewport,
        contour?.color ?: Color.Transparent,
        contour?.width ?: 0f,
    )
}

private fun contourRadius(
    a: CornerRadius,
    b: CornerRadius,
    t: Float,
): CornerRadius = CornerRadius(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** An unmatched divider retracts to the real perimeter at its own relative row height. */
private fun absentSettingsSeam(
    seam: SettingsContourSeam,
    from: SettingsContour,
    to: SettingsContour,
): SettingsContourSeam {
    val relativeY = if (from.bounds.height == 0f) 0.5f else (seam.start.y - from.bounds.top) / from.bounds.height
    val y = to.bounds.top + relativeY * to.bounds.height
    val x = if (to.bounds.isEmpty) to.bounds.center.x else settingsContourSpan(to, y).first
    return seam.copy(start = Offset(x, y), end = Offset(x, y))
}

/** Raw geometry and native paint survive freezing; sub-stroke clamping belongs to drawing only. */
private fun interpolateSettingsContour(
    a: SettingsContour,
    b: SettingsContour,
    t: Float,
    key: Int,
): SettingsContour {
    val bounds = lerp(a.bounds, b.bounds, t)
    val width =
        if (a.width == 0f) {
            b.width
        } else if (b.width == 0f) {
            a.width
        } else {
            a.width + (b.width - a.width) * t
        }
    return SettingsContour(
        bounds,
        RoundRect(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            contourRadius(a.corners.topLeftCornerRadius, b.corners.topLeftCornerRadius, t),
            contourRadius(a.corners.topRightCornerRadius, b.corners.topRightCornerRadius, t),
            contourRadius(a.corners.bottomRightCornerRadius, b.corners.bottomRightCornerRadius, t),
            contourRadius(a.corners.bottomLeftCornerRadius, b.corners.bottomLeftCornerRadius, t),
        ),
        lerp(a.viewport, b.viewport, t),
        if (a.bounds.isEmpty) {
            b.color
        } else if (b.bounds.isEmpty) {
            a.color
        } else {
            lerp(a.color, b.color, t)
        },
        width,
        List(max(a.seams.size, b.seams.size)) { index ->
            val sa = a.seams.getOrNull(index) ?: absentSettingsSeam(b.seams[index], b, a)
            val sb = b.seams.getOrNull(index) ?: absentSettingsSeam(a.seams[index], a, b)
            SettingsContourSeam(
                lerp(sa.start, sb.start, t),
                lerp(sa.end, sb.end, t),
                lerp(sa.color, sb.color, t),
                sa.width + (sb.width - sa.width) * t,
            )
        },
        key = key,
    )
}

/** Each absent peer has its own point, carried by the facing edge of its nearest matched group. */
private fun satelliteSettingsContour(
    slot: SettingsContour,
    matched: SettingsContour,
    partner: SettingsContour,
    above: Boolean,
): SettingsContour {
    val dy = if (above) partner.bounds.top - matched.bounds.top else partner.bounds.bottom - matched.bounds.bottom
    val point = slot.bounds.center + Offset(partner.bounds.center.x - matched.bounds.center.x, dy)
    return SettingsContour(
        Rect(point, point),
        RoundRect(Rect(point, point)),
        slot.viewport,
        slot.color,
        0f,
        key = slot.key,
    )
}

internal fun nearestSettingsContour(
    contours: List<SettingsContour>,
    point: Offset,
): Int =
    contours.indices.minBy { index ->
        val b = contours[index].bounds
        val dx = point.x - point.x.coerceIn(b.left, b.right)
        val dy = point.y - point.y.coerceIn(b.top, b.bottom)
        dx * dx + dy * dy
    }

// One sampler owns outlined geometry and true-empty compatibility.

/**
 * The activated group moves 1:1 for the entire clock. Neighbouring groups correspond in order;
 * surplus outer groups shrink to separate facing-edge-carried points, never into the survivor.
 * Rank correspondence also preserves ordinary N↔M routes with several native destination groups.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
internal fun interpolateSettingsContours(
    source: List<SettingsContour>,
    target: List<SettingsContour>,
    fraction: Float,
    emptyPoint: Offset,
    viewport: Rect,
    correspondenceKey: Int? = null,
    paintFraction: Float = fraction,
): List<SettingsContour> {
    val t = fraction.coerceIn(0f, 1f)
    if (t == 0f) return source
    if (t == 1f) return target
    if (source.isEmpty() || target.isEmpty()) {
        // Preserve true-empty full-span seam arithmetic and native style without inventing a card.
        return List(max(source.size, target.size)) { index ->
            val a = source.getOrNull(index) ?: settingsContourSeed(null, emptyPoint, viewport)
            val b = target.getOrNull(index) ?: settingsContourSeed(null, emptyPoint, viewport)
            val contour = interpolateSettingsContour(a, b, t, (source.getOrNull(index) ?: target[index]).key)
            val fallback = contour.copy(width = min(contour.width, min(contour.bounds.width, contour.bounds.height)))
            fallback
                .copy(
                    seams =
                        List(max(a.seams.size, b.seams.size)) { row ->
                            val sa = a.seams.getOrNull(row)
                            val sb = b.seams.getOrNull(row)
                            val offsetA =
                                (sa?.start?.y ?: (a.bounds.bottom - a.width / 2f)) - a.bounds.top - a.width / 2f
                            val offsetB =
                                (sb?.start?.y ?: (b.bounds.bottom - b.width / 2f)) - b.bounds.top - b.width / 2f
                            val y = contour.bounds.top + contour.width / 2f + offsetA * (1f - t) + offsetB * t
                            val span = settingsContourSpan(fallback, y)
                            val style = sa ?: requireNotNull(sb)
                            SettingsContourSeam(
                                Offset(span.first, y),
                                Offset(span.second, y),
                                if (sa != null && sb != null) lerp(sa.color, sb.color, t) else style.color,
                                style.width,
                            )
                        },
                ).withSatellitePresence(
                    if (source.isNotEmpty()) {
                        1f - (paintFraction / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f)
                    } else {
                        1f - ((1f - paintFraction) / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f)
                    },
                )
        }
    }
    val sharedKeys = source.map { it.key }.intersect(target.map { it.key }.toSet())
    val selectedKey =
        correspondenceKey?.takeIf { it in sharedKeys }
            ?: source
                .filter {
                    it.key in sharedKeys
                }.takeIf { it.isNotEmpty() }
                ?.let { it[nearestSettingsContour(it, emptyPoint)].key }
    val sourceIndex =
        source.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: nearestSettingsContour(source, emptyPoint)
    val targetIndex =
        target.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: nearestSettingsContour(target, emptyPoint)
    val firstRank = min(-sourceIndex, -targetIndex)
    val lastRank = max(source.lastIndex - sourceIndex, target.lastIndex - targetIndex)
    return (firstRank..lastRank).map { rank ->
        val a = source.getOrNull(sourceIndex + rank)
        val b = target.getOrNull(targetIndex + rank)
        when {
            a != null && b != null ->
                interpolateSettingsContour(
                    a,
                    b,
                    t,
                    if (source.size >= target.size) {
                        a.key
                    } else {
                        b.key
                    },
                )
            a != null -> {
                val matchedIndex =
                    (sourceIndex + rank).coerceIn(
                        sourceIndex - targetIndex,
                        sourceIndex + target.lastIndex - targetIndex,
                    )
                val matched = source[matchedIndex]
                val partner = target[targetIndex + matchedIndex - sourceIndex]
                interpolateSettingsContour(a, satelliteSettingsContour(a, matched, partner, rank < 0), t, a.key)
                    .withSatellitePresence(1f - (paintFraction / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f))
            }
            else -> {
                val slot = requireNotNull(b)
                val matchedIndex =
                    (targetIndex + rank).coerceIn(
                        targetIndex - sourceIndex,
                        targetIndex + source.lastIndex - sourceIndex,
                    )
                val matched = target[matchedIndex]
                val partner = source[sourceIndex + matchedIndex - targetIndex]
                interpolateSettingsContour(
                    satelliteSettingsContour(slot, matched, partner, rank < 0),
                    slot,
                    t,
                    slot.key,
                ).withSatellitePresence(1f - ((1f - paintFraction) / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f))
            }
        }
    }
}

/** Only surplus boxes fade, including their seams; frozen source alpha is never reset. */
private fun SettingsContour.withSatellitePresence(presence: Float): SettingsContour =
    copy(
        color = color.copy(alpha = color.alpha * presence),
        seams = seams.map { it.copy(color = it.color.copy(alpha = it.color.alpha * presence)) },
    )

/** Horizontal span of the inset rounded perimeter; zero length seams meet its real left edge. */
internal fun settingsContourSpan(
    contour: SettingsContour,
    y: Float,
): Pair<Float, Float> {
    val b = contour.bounds
    val inset = contour.width / 2f

    fun edge(left: Boolean): Float {
        val topRadius = if (left) contour.corners.topLeftCornerRadius.x else contour.corners.topRightCornerRadius.x
        val bottomRadius =
            if (left) contour.corners.bottomLeftCornerRadius.x else contour.corners.bottomRightCornerRadius.x
        val radius = (if (y < b.center.y) topRadius else bottomRadius).minus(inset).coerceAtLeast(0f)
        val cy = if (y < b.center.y) b.top + inset + radius else b.bottom - inset - radius
        val dy = (y - cy).let { if (y < b.center.y) min(it, 0f) else max(it, 0f) }.coerceIn(-radius, radius)
        val dx = radius - sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
        return if (left) b.left + inset + dx else b.right - inset - dx
    }
    return edge(true) to edge(false)
}
