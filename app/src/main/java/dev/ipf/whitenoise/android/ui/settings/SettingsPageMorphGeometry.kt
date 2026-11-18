package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.isSpecified
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorderPath
import kotlin.math.min

/** Frozen activation metadata; this is presentation data, never a navigation owner. */
internal data class SettingsRouteEndpoint(
    val key: Any,
    val destination: Any,
    val bounds: Rect,
    val corners: RoundRect,
    val fill: Color,
    val outline: Color,
    val first: Boolean,
    val last: Boolean,
    val seam: Color = outline,
    val visibleBounds: Rect = bounds,
    val strokeWidth: Float = 1f,
    val viewportToken: Any? = null,
    val rtl: Boolean = false,
)

internal data class ReturnTarget(
    val point: Offset,
    val groupKey: Int?,
    val childGroupKey: Int? = null,
)

internal data class PresentedFrame(
    val picture: Picture,
    val contours: List<SettingsContour>,
    val body: Path,
    val bodyTop: Float,
    val host: Rect,
    val inputs: Any?,
)

// Derived only from reports and route context; child animation is sampled separately.
internal data class NativeFrame(
    val host: Rect,
    val contours: List<SettingsContour>?,
)

// Keys identify native groups only; transition correspondence belongs to the measured pair batch.
@Suppress("LongMethod", "ReturnCount")
internal fun SettingsPageMorph.buildNativeContours(host: Rect): List<SettingsContour>? {
    val groups =
        destinations.entries
            .filter { it.value.outline.isSpecified }
            .distinctBy { it.value.bounds }
            .sortedWith(compareBy({ it.value.bounds.top }, { it.value.bounds.left }))
    val contours =
        orderedSettingsContours(
            groups.map { (token, endpoint) ->
                val nativeRows = rows[token]?.values.orEmpty().sortedBy { it.bounds.top }
                SettingsContour(
                    endpoint.bounds,
                    endpoint.corners,
                    viewports[endpoint.viewportToken] ?: host,
                    endpoint.outline,
                    endpoint.strokeWidth,
                    nativeRows.filterNot { it.last }.map { row ->
                        val inset = row.strokeWidth / 2f
                        SettingsContourSeam(
                            Offset(row.bounds.left + inset, row.bounds.bottom - 0.5f),
                            Offset(row.bounds.right - inset, row.bounds.bottom - 0.5f),
                            row.seam,
                        )
                    },
                    nativeRows.map { SettingsNativeRow(it.bounds, it.corners, it.first, it.last) },
                    destination = endpoint.destination,
                    fill = endpoint.fill,
                    rtl = endpoint.rtl,
                )
            },
        ) ?: return null
    if (contours.isEmpty()) return contours
    val saved = routeGroupKeys.getOrPut(target) { mutableMapOf() }
    nextGroupKey = maxOf(nextGroupKey, (sourceContours.maxOfOrNull { it.key } ?: 0) + 1)
    val keys =
        contours.indices.map { index ->
            val identity = groups[index].value.key
            val occurrence = groups.take(index).count { it.value.key == identity }
            saved.getOrPut(identity to occurrence) { nextGroupKey++ }
        }
    return contours.mapIndexed { index, contour -> contour.copy(key = keys[index]) }
}

/** Native group interiors only: no header replay and no travelling cell-sized crop. */
internal fun nativeBodyMask(
    contours: List<SettingsContour>,
    host: Rect,
): Path =
    Path().apply {
        contours.forEach { contour ->
            val visible = contour.bounds.intersect(contour.viewport).intersect(host)
            if (!visible.isEmpty) addRect(visible.translate(-host.topLeft))
        }
    }

/** Cached per native frame, never sampled from pixels or rebuilt for animation progress. */
internal fun nativeChromeMask(
    contours: List<SettingsContour>,
    host: Rect,
): Path {
    val result = android.graphics.Path()
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style =
                android.graphics.Paint.Style.STROKE
        }

    fun add(
        chrome: android.graphics.Path,
        path: Path,
        width: Float,
    ) {
        paint.strokeWidth = width + 2f // one physical AA pixel on each side of the actual stroke
        val filled = android.graphics.Path()
        paint.getFillPath(path.asAndroidPath(), filled)
        chrome.addPath(filled)
    }
    contours.forEach { contour ->
        val viewport = contour.viewport.intersect(host).translate(-host.topLeft)
        if (viewport.isEmpty) return@forEach
        val chrome = android.graphics.Path()
        val pieces = contour.rows.ifEmpty { listOf(SettingsNativeRow(contour.bounds, contour.corners, true, true)) }
        pieces.forEach { row ->
            add(
                chrome,
                connectedRowBorderPath(
                    row.bounds.translate(-host.topLeft),
                    row.corners,
                    row.first,
                    row.last,
                    contour.width,
                ),
                contour.width,
            )
        }
        contour.seams.forEach { seam ->
            add(
                chrome,
                Path().apply {
                    moveTo(seam.start.x - host.left, seam.start.y - host.top)
                    lineTo(seam.end.x - host.left, seam.end.y - host.top)
                },
                seam.width,
            )
        }
        // Native strokes outside the list never existed in the page picture: leave that ink alone.
        val clip =
            android.graphics.Path().apply {
                addRect(
                    viewport.left,
                    viewport.top,
                    viewport.right,
                    viewport.bottom,
                    android.graphics.Path.Direction.CW,
                )
            }
        chrome.op(clip, android.graphics.Path.Op.INTERSECT)
        result.op(chrome, android.graphics.Path.Op.UNION)
    }
    return result.asComposePath()
}

internal fun DrawScope.drawContinuousContour(
    contour: SettingsContour,
    host: Rect,
) {
    if (contour.bounds.isEmpty || contour.width <= 0f) return
    val bounds = contour.bounds.translate(-host.topLeft)
    val width = min(contour.width, min(bounds.width, bounds.height))

    clipPath(Path().apply { addRect(contour.viewport.intersect(host).translate(-host.topLeft)) }) {
        val chrome = connectedRowBorderPath(bounds, contour.corners, true, true, width)
        contour.seams.forEach { seam ->
            if (seam.start != seam.end) {
                if (seam.color == contour.color && min(seam.width, width) == width) {
                    chrome.moveTo(seam.start.x - host.left, seam.start.y - host.top)
                    chrome.lineTo(seam.end.x - host.left, seam.end.y - host.top)
                } else {
                    drawLine(
                        seam.color,
                        seam.start - host.topLeft,
                        seam.end - host.topLeft,
                        strokeWidth = min(seam.width, width),
                    )
                }
            }
        }
        // One coverage owner for coincident native-weight structural edges and the exterior.
        drawPath(chrome, contour.color, style = Stroke(width))
    }
}

internal fun Rect.validIn(host: Rect): Boolean =
    listOf(left, top, right, bottom).all { it.isFinite() } &&
        !isEmpty &&
        !host.isEmpty &&
        left >= host.left &&
        top >= host.top &&
        right <= host.right &&
        bottom <= host.bottom

internal fun Rect.visibleIn(host: Rect): Boolean =
    listOf(left, top, right, bottom).all { it.isFinite() } &&
        !isEmpty &&
        !intersect(host).isEmpty
