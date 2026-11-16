package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal const val SETTINGS_OUTGOING_PHASE_END = 0.4f
private const val MATCH_HEIGHT_WEIGHT = 0.25f

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
    val destination: Any? = null,
    val fill: Color = Color.Unspecified,
    val rtl: Boolean = false,
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
    val x =
        if (to.bounds.isEmpty) {
            to.bounds.center.x
        } else {
            val span = settingsContourSpan(to, y)
            if (from.rtl) span.second else span.first
        }
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
    fun radius(a: CornerRadius, b: CornerRadius): CornerRadius {
        val value = contourRadius(a, b, t)
        val limit = min(bounds.width, bounds.height) / 2f
        return CornerRadius(min(value.x, limit), min(value.y, limit))
    }
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
            radius(a.corners.topLeftCornerRadius, b.corners.topLeftCornerRadius),
            radius(a.corners.topRightCornerRadius, b.corners.topRightCornerRadius),
            radius(a.corners.bottomRightCornerRadius, b.corners.bottomRightCornerRadius),
            radius(a.corners.bottomLeftCornerRadius, b.corners.bottomLeftCornerRadius),
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
        destination = if (t < 0.5f) a.destination else b.destination,
        rtl = a.rtl,
        fill =
            when {
                !a.fill.isSpecified -> b.fill
                !b.fill.isSpecified -> a.fill
                else -> lerp(a.fill, b.fill, t)
            },
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
 * surplus groups leave through the viewport or contract into an exclusive interior gap.
 * Correspondence is measured once by the presentation owner.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LongParameterList")
internal fun interpolateSettingsContours(
    source: List<SettingsContour>,
    target: List<SettingsContour>,
    fraction: Float,
    emptyPoint: Offset,
    viewport: Rect,
    correspondenceKey: Int? = null,
    paintFraction: Float = fraction,
    correspondence: List<SettingsContourPair>? = null,
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
                ).withEmptyPresence(
                    if (source.isNotEmpty()) {
                        1f - (paintFraction / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f)
                    } else {
                        1f - ((1f - paintFraction) / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f)
                    },
                )
        }
    }
    return (correspondence ?: matchSettingsContours(source, target, correspondenceKey, null, emptyPoint, viewport))
        .map { pair -> interpolateSettingsContour(pair.source, pair.target, t, pair.key) }
}

/** A measured batch owns correspondence; animation only samples these immutable endpoints. */
internal data class SettingsContourPair(
    val source: SettingsContour,
    val target: SettingsContour,
    val key: Int,
)

/** Maximum-cardinality minimum-distance monotone assignment on either side of the semantic anchor. */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList", "NestedBlockDepth")
internal fun matchSettingsContours(
    source: List<SettingsContour>,
    target: List<SettingsContour>,
    anchorKey: Int?,
    routeKey: Any?,
    point: Offset,
    viewport: Rect,
    targetAnchorKey: Int? = anchorKey,
): List<SettingsContourPair> {
    fun visible(contours: List<SettingsContour>) =
        contours.filter { !it.bounds.intersect(it.viewport).intersect(viewport).isEmpty }
    val a = visible(source)
    val b = visible(target)
    if (a.isEmpty() || b.isEmpty()) {
        // Offscreen outlined endpoints are not genuinely empty: visible peers still leave rigidly.
        val visible = a.ifEmpty { b }
        val dy = visible.firstOrNull()?.let { viewport.bottom - it.bounds.top + it.width } ?: 0f
        return visible.map { slot ->
            val offset = Offset(0f, dy)
            val bounds = slot.bounds.translate(offset)
            val peer = slot.copy(
                bounds = bounds,
                corners = RoundRect(bounds, slot.corners.topLeftCornerRadius.x, slot.corners.topLeftCornerRadius.y),
                seams = slot.seams.map { it.copy(start = it.start + offset, end = it.end + offset) },
                rows = emptyList(),
            )
            if (a.isEmpty()) SettingsContourPair(peer, slot, slot.key) else SettingsContourPair(slot, peer, slot.key)
        }
    }
    val shared = a.filter { from -> b.any { it.key == from.key } }
    val selectedKey =
        anchorKey ?: shared.takeIf { it.isNotEmpty() }?.let { it[nearestSettingsContour(it, point)].key }
    val ai = a.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: nearestSettingsContour(a, point)
    val bi =
        b.indexOfFirst { routeKey != null && it.destination == routeKey }.takeIf { it >= 0 }
            ?: b.indexOfFirst { it.key == (targetAnchorKey ?: selectedKey) }.takeIf { it >= 0 }
            ?: nearestSettingsContour(b, point)
    fun cost(x: SettingsContour, y: SettingsContour): Float {
        val xc = x.bounds.intersect(x.viewport).intersect(viewport)
        val yc = y.bounds.intersect(y.viewport).intersect(viewport)
        return kotlin.math.abs(xc.center.y - yc.center.y) + kotlin.math.abs(xc.height - yc.height) * MATCH_HEIGHT_WEIGHT
    }
    fun assign(xs: List<Int>, ys: List<Int>): List<Pair<Int, Int>> {
        if (xs.isEmpty() || ys.isEmpty()) return emptyList()
        // The smaller side is fully matched; skip only on the larger side.
        val swapped = xs.size > ys.size
        val small = if (swapped) ys else xs
        val large = if (swapped) xs else ys
        val scores = Array(small.size + 1) { FloatArray(large.size + 1) { Float.POSITIVE_INFINITY } }
        val take = Array(small.size + 1) { BooleanArray(large.size + 1) }
        scores[0].fill(0f)
        for (i in 1..small.size) {
            for (j in i..large.size) {
                val x = if (swapped) large[j - 1] else small[i - 1]
                val y = if (swapped) small[i - 1] else large[j - 1]
                val paired = scores[i - 1][j - 1] + cost(a[x], b[y])
                take[i][j] = paired <= scores[i][j - 1]
                scores[i][j] = min(paired, scores[i][j - 1])
            }
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var i = small.size
        var j = large.size
        while (i > 0) {
            if (take[i][j]) {
                result += if (swapped) large[j - 1] to small[i - 1] else small[i - 1] to large[j - 1]
                i--
            }
            j--
        }
        return result.reversed()
    }
    val indices =
        assign((0 until ai).toList(), (0 until bi).toList()) +
            listOf(ai to bi) + assign((ai + 1..a.lastIndex).toList(), (bi + 1..b.lastIndex).toList())
    val result =
        indices.map { (x, y) ->
            val key =
                when {
                    a.size > b.size -> a[x].key
                    b.size > a.size -> b[y].key
                    else -> min(a[x].key, b[y].key)
                }
            SettingsContourPair(a[x], b[y], key)
        }.toMutableList()
    fun addSurplus(own: List<SettingsContour>, other: List<SettingsContour>, reverse: Boolean) {
        val pairs = if (reverse) indices.map { it.second to it.first } else indices
        own.indices.filter { index -> pairs.none { it.first == index } }.forEach { index ->
            val slot = own[index]
            val before = pairs.lastOrNull { it.first < index }
            val after = pairs.firstOrNull { it.first > index }
            val bounds =
                when {
                    before == null -> {
                        val next = requireNotNull(after)
                        val edge = min(viewport.top, other[next.second].bounds.top)
                        slot.bounds.translate(Offset(0f, edge - own[next.first - 1].bounds.bottom - slot.width))
                    }
                    after == null -> {
                        val edge = max(viewport.bottom, other[before.second].bounds.bottom)
                        slot.bounds.translate(Offset(0f, edge - own[before.first + 1].bounds.top + slot.width))
                    }
                    else -> {
                        // Each interior surplus owns a separate position in the destination's empty gap.
                        val low = other[before.second].bounds.bottom
                        val high = other[after.second].bounds.top
                        val rank = (index - before.first).toFloat() / (after.first - before.first)
                        val y = low + (high - low) * rank
                        Rect(slot.bounds.left, y, slot.bounds.right, y)
                    }
                }
            val seams = slot.seams.map { seam ->
                fun move(point: Offset): Offset {
                    val relative =
                        if (slot.bounds.height == 0f) 0.5f else (point.y - slot.bounds.top) / slot.bounds.height
                    return Offset(point.x, bounds.top + relative * bounds.height)
                }
                seam.copy(start = move(seam.start), end = move(seam.end))
            }
            val peer =
                slot.copy(
                    bounds = bounds,
                    corners = RoundRect(bounds, slot.corners.topLeftCornerRadius.x, slot.corners.topLeftCornerRadius.y),
                    seams = seams,
                    rows = emptyList(),
                )
            result +=
                if (reverse) SettingsContourPair(peer, slot, slot.key) else SettingsContourPair(slot, peer, slot.key)
        }
    }
    addSurplus(a, b, false)
    addSurplus(b, a, true)
    return result.sortedWith(compareBy({ it.source.bounds.top }, { it.target.bounds.top }))
}

/** True-empty compatibility alone retains its native seed reveal. */
private fun SettingsContour.withEmptyPresence(presence: Float): SettingsContour =
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
