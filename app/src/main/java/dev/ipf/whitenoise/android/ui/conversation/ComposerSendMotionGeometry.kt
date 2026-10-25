package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** An actual empty-editor measurement, bound to the generation cleared by acceptance. */
internal data class ComposerSendComposerTarget(
    val inputGeneration: Long,
    val height: Float,
)

/** The overlap and physical viewport consumed by the same completed native layout as the landing row. */
internal data class ComposerSendViewportProjection(
    val bounds: Rect,
    val consumedHeight: Float,
)

internal data class ComposerSendLandingMeasurement(
    val frame: Rect,
    val text: Rect,
    val composer: Rect,
    val target: ComposerSendComposerTarget,
    val fill: Color,
    val viewport: ComposerSendViewportProjection,
)

/** The immutable frame actually consumed by the draw pass; retained only until that flight retires. */
internal data class ComposerSendFlightGeometry(
    val bounds: Rect,
    val textTarget: Rect,
    val canonicalFrame: Rect,
    val canonicalText: Rect,
    val composer: Rect,
    val settledComposerHeight: Float,
    val remainingCollapse: Float,
    val progress: Float,
    val fill: Color,
    val consumedHeight: Float,
) {
    val settled: Boolean get() = progress == 1f && remainingCollapse == 0f && motionRectsMatch(bounds, canonicalFrame)
}

/** Compensates the existing measured composer overlap, never unrelated viewport or scroll motion. */
internal class ComposerSendLanding(
    private val sourceSurface: Rect,
    private val sourceComposer: Rect?,
    private val sourceFill: Color,
    private val inputGeneration: Long,
    private val sourceViewport: ComposerSendViewportProjection?,
) {
    private var expectedFrame: Rect? = null
    private var expectedText: Rect? = null
    private var lastConsumedHeight: Float? = null

    fun resolve(
        measured: ComposerSendLandingMeasurement?,
        timerProgress: Float,
    ): ComposerSendFlightGeometry? {
        val container = sourceComposer
        return if (container != null && measured != null) resolveMeasured(container, measured, timerProgress) else null
    }

    private fun resolveMeasured(
        container: Rect,
        measured: ComposerSendLandingMeasurement,
        timerProgress: Float,
    ): ComposerSendFlightGeometry? {
        if (!eligible(container, measured)) return null
        val rawRemaining = measured.viewport.consumedHeight - measured.target.height
        // Only native-pixel rounding is normalized. Out-of-range or growing overlap is rejected before this point.
        val remaining = if (abs(rawRemaining) <= MOTION_ROUNDING_PX) 0f else rawRemaining
        val targetFrame = measured.frame.translate(Offset(0f, remaining))
        val targetText = measured.text.translate(Offset(0f, remaining))
        val upward = targetFrame.bottom <= sourceSurface.bottom + MOTION_ROUNDING_PX
        val stable = motionRectsMatch(expectedFrame, targetFrame) && motionRectsMatch(expectedText, targetText)
        return if (upward && stable) {
            expectedFrame = expectedFrame ?: targetFrame
            expectedText = expectedText ?: targetText
            lastConsumedHeight = measured.viewport.consumedHeight
            val contraction = checkNotNull(sourceViewport).consumedHeight - measured.target.height
            val phase = if (contraction > MOTION_ROUNDING_PX) (contraction - remaining) / contraction else timerProgress
            ComposerSendFlightGeometry(
                androidx.compose.ui.geometry
                    .lerp(sourceSurface, targetFrame, phase),
                targetText,
                measured.frame,
                measured.text,
                measured.composer,
                measured.target.height,
                remaining,
                phase,
                androidx.compose.ui.graphics
                    .lerp(sourceFill, measured.fill, phase),
                measured.viewport.consumedHeight,
            )
        } else {
            null
        }
    }

    private fun eligible(
        container: Rect,
        measured: ComposerSendLandingMeasurement,
    ): Boolean {
        val height = measured.target.height
        val validHeight = height.isFinite() && height > 0f && height <= container.height + MOTION_ROUNDING_PX
        val generationAndHeight = measured.target.inputGeneration == inputGeneration && validHeight
        val stationary =
            abs(container.bottom - measured.composer.bottom) <= MOTION_ROUNDING_PX &&
                abs(container.width - measured.composer.width) <= MOTION_ROUNDING_PX &&
                abs(container.left - measured.composer.left) <= MOTION_ROUNDING_PX
        val shrinking =
            measured.composer.height <= container.height + MOTION_ROUNDING_PX &&
                measured.composer.height >= height - MOTION_ROUNDING_PX
        val samePhysicalOwner = stationary && shrinking
        return generationAndHeight && samePhysicalOwner && coherentOverlap(container, measured)
    }

    private fun coherentOverlap(
        container: Rect,
        measured: ComposerSendLandingMeasurement,
    ): Boolean {
        val original = sourceViewport ?: return false
        val consumed = measured.viewport.consumedHeight
        val initialMatches = abs(original.consumedHeight - container.height) <= MOTION_ROUNDING_PX
        val sameViewport = motionRectsMatch(original.bounds, measured.viewport.bounds)
        val inRange =
            consumed.isFinite() &&
                consumed >= measured.target.height - MOTION_ROUNDING_PX &&
                consumed <= original.consumedHeight
        val monotonic = lastConsumedHeight?.let { consumed <= it } ?: true
        val sameOwner = initialMatches && sameViewport
        return sameOwner && inRange && monotonic
    }
}

private const val MOTION_ROUNDING_PX = 1f

/** Compare against the original predicted landing, so rounding cannot accumulate into endpoint drift. */
internal fun motionRectsMatch(
    expected: Rect?,
    actual: Rect,
): Boolean =
    expected == null ||
        listOf(
            expected.left - actual.left,
            expected.top - actual.top,
            expected.right - actual.right,
            expected.bottom - actual.bottom,
        ).all { abs(it) <= MOTION_ROUNDING_PX }
