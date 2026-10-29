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
    val settled: Boolean
        get() {
            val nativeClear = canonicalFrame.bottom <= composer.top + MOTION_ROUNDING_PX
            val sameMeasuredHeight = abs(composer.height - consumedHeight) <= MOTION_ROUNDING_PX
            val completed = progress == 1f && remainingCollapse == 0f
            val nativeSettled = nativeClear && sameMeasuredHeight
            return completed && nativeSettled && motionRectsMatch(bounds, canonicalFrame)
        }
}

/** Immutable numeric observation of one existing resolve call; contains no message content. */
internal data class ComposerSendLandingDiagnostic(
    val timerProgress: Float,
    val sourceSurface: Rect,
    val sourceComposer: Rect?,
    val sourceViewport: ComposerSendViewportProjection?,
    val inputGeneration: Long,
    val measured: ComposerSendLandingMeasurement?,
    val expectedFrameBefore: Rect?,
    val expectedTextBefore: Rect?,
    val lastConsumedHeightBefore: Float?,
    val resolved: ComposerSendFlightGeometry? = null,
)

/** Compensates the existing measured composer overlap, never unrelated viewport or scroll motion. */
internal class ComposerSendLanding(
    private val sourceSurface: Rect,
    private val sourceComposer: Rect?,
    private val sourceFill: Color,
    private val inputGeneration: Long,
    private val sourceViewport: ComposerSendViewportProjection?,
    private val sourceUpwardBoundBottom: Float = sourceSurface.bottom,
) {
    private var expectedFrame: Rect? = null
    private var expectedText: Rect? = null
    private var lastConsumedHeight: Float? = null

    /**
     * Read-only probe mapping the current measurement and timer progress to a frame, or null while the landing is
     * unmeasured or ineligible. An installed [observe] receives the prior endpoint history alongside the result.
     */
    fun resolve(
        measured: ComposerSendLandingMeasurement?,
        timerProgress: Float,
        observe: ((ComposerSendLandingDiagnostic) -> Unit)? = null,
    ): ComposerSendFlightGeometry? {
        val container = sourceComposer
        // Observe immutable endpoint history; only the timing owner commits a successful probe.
        val before =
            observe?.let {
                ComposerSendLandingDiagnostic(
                    timerProgress,
                    sourceSurface,
                    container,
                    sourceViewport,
                    inputGeneration,
                    measured,
                    expectedFrame,
                    expectedText,
                    lastConsumedHeight,
                )
            }
        val result =
            if (container != null && measured != null) {
                resolveMeasured(container, measured, timerProgress)
            } else {
                null
            }
        if (before != null) observe?.invoke(before.copy(resolved = result))
        return result
    }

    /** Only the timing owner commits a successful endpoint; layer and Canvas probes are read-only. */
    fun commit(geometry: ComposerSendFlightGeometry) {
        val remaining = Offset(0f, geometry.remainingCollapse)
        expectedFrame = expectedFrame ?: geometry.canonicalFrame.translate(remaining)
        expectedText = expectedText ?: geometry.canonicalText.translate(remaining)
        lastConsumedHeight = geometry.consumedHeight
    }

    /**
     * Shifts the measured bubble by the composer collapse still to come, accepting it only when it lands at or above
     * the source pill and matches the first committed endpoint, then interpolates bounds and fill by progress.
     */
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
        val upward = targetFrame.bottom <= sourceUpwardBoundBottom + MOTION_ROUNDING_PX
        val stable = motionRectsMatch(expectedFrame, targetFrame) && motionRectsMatch(expectedText, targetText)
        return if (upward && stable) {
            val phase = timerProgress
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

    /**
     * The measurement belongs to this acceptance: same input generation, a positive target no taller than the source
     * composer, a composer still anchored in place and only shrinking toward that target, and a coherent overlap.
     */
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

    /**
     * The overlap comes from the viewport seen at capture, lies between the empty-composer height and its original
     * value, and never grows between frames.
     */
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
        abs(expected.left - actual.left) <= MOTION_ROUNDING_PX &&
        abs(expected.top - actual.top) <= MOTION_ROUNDING_PX &&
        abs(expected.right - actual.right) <= MOTION_ROUNDING_PX &&
        abs(expected.bottom - actual.bottom) <= MOTION_ROUNDING_PX

/** Resolves [active]'s landing against [measurement], tagging forwarded diagnostics with [callKind]. */
internal fun resolveComposerSendLanding(
    active: ComposerSendFlight,
    progress: Float,
    callKind: String,
    observer: ((String, ComposerSendLandingDiagnostic) -> Unit)?,
    measurement: ComposerSendLandingMeasurement?,
): ComposerSendFlightGeometry? =
    active.landing.resolve(
        measurement,
        progress,
        observer?.let { sink -> { diagnostic -> sink(callKind, diagnostic) } },
    )
