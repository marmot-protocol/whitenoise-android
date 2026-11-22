package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing

internal const val CONTENT_HANDOFF_FRACTION = 0.5f

/** Linear clock time over which the outgoing body fades out before the handoff and the incoming one in after it. */
private const val CONTENT_HANDOFF_FADE = 0.1f

/** Clock time at which [easing] reaches the geometry handoff. */
private fun handoffTime(easing: Easing): Float {
    var low = 0f
    var high = 1f
    repeat(HANDOFF_SEARCH_STEPS) {
        val mid = (low + high) / 2f
        if (easing.transform(mid) < CONTENT_HANDOFF_FRACTION) low = mid else high = mid
    }
    return high
}

private const val HANDOFF_SEARCH_STEPS = 24
private val OpenHandoffTime = handoffTime(AppMotionOpenEasing)
private val CloseHandoffTime = handoffTime(AppMotionCloseEasing)

/**
 * Paint alpha for the two bodies around the handoff: the outgoing picture fades out just before it
 * and the incoming native body fades in just after it, so they never overlap at native positions.
 */
internal fun settingsPageHandoffAlpha(
    opening: Boolean,
    time: Float,
    incoming: Boolean,
): Float {
    val handoff = if (opening) OpenHandoffTime else CloseHandoffTime
    val offset = if (incoming) time - handoff else handoff - time
    return (offset / CONTENT_HANDOFF_FADE).coerceIn(0f, 1f)
}

/**
 * Draws the live route once through [live] outside the native chrome [mask]. Below full [bodyAlpha]
 * the fixed header inside [chrome] stays opaque while the arriving body fades in after the handoff.
 */
internal fun ContentDrawScope.drawNativeLiveBody(
    live: Path,
    mask: Path,
    bodyAlpha: Float,
    chrome: () -> Path,
) {
    if (bodyAlpha >= 1f) {
        clipPath(live) {
            clipPath(mask, ClipOp.Difference) { this@drawNativeLiveBody.drawContent() }
        }
        return
    }
    val header = chrome()
    clipPath(header) {
        clipPath(mask, ClipOp.Difference) { this@drawNativeLiveBody.drawContent() }
    }
    val body = Path().apply { op(live, header, PathOperation.Difference) }
    clipPath(body) {
        clipPath(mask, ClipOp.Difference) {
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = bodyAlpha }) }
            this@drawNativeLiveBody.drawContent()
            drawIntoCanvas { it.restore() }
        }
    }
}

/** Include free-form controls and margins inside the actual measured scroll body, never headers. */
internal fun SettingsPageMorph.measuredBodyCoverage(
    native: List<SettingsContour>,
    host: Rect,
): Path =
    activeNativeBody(native, host).apply {
        viewports.values.forEach { viewport ->
            val visible = viewport.intersect(host)
            if (!visible.isEmpty) addRect(visible.translate(-host.topLeft))
        }
    }

internal fun SettingsPageMorph.activeNativeBody(
    native: List<SettingsContour>,
    host: Rect,
): Path =
    nativeBodyMask(native, host).apply {
        // Unoutlined native bodies participate in the reveal without inventing a border.
        destinations.values.filter { !it.outline.isSpecified }.forEach { endpoint ->
            val visible = endpoint.bounds.intersect(viewports[endpoint.viewportToken] ?: host).intersect(host)
            if (!visible.isEmpty) addRect(visible.translate(-host.topLeft))
        }
    }
