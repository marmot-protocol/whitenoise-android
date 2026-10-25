@file:Suppress("FunctionNaming") // Compose surface functions follow UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil

internal const val COMPOSER_SEND_MOTION_MILLIS = 280
private const val MAX_COMPOSER_MOTION_CHARACTERS = 4096
private const val DESTINATION_MEASURE_WAIT_MILLIS = 80L
private const val TEXT_REFLOW_BLEND_START = 0.65f
private const val TEXT_REFLOW_BLEND_INTERVAL = 0.35f
internal val LocalComposerSendMotion = staticCompositionLocalOf<ComposerSendMotion?> { null }

/** Presentation-only snapshots live for at most one send animation, never in saveable state. */
internal data class ComposerSendSource(
    val text: String,
    val ownerGeneration: Long,
    val layout: TextLayoutResult,
    val surface: Rect,
    val editor: Rect,
    val fill: Color,
    val border: Color,
    val topRadius: Float,
    val bottomRadius: Float,
    val strokeWidth: Float,
    val paragraphDirection: ResolvedTextDirection,
    val composer: Rect? = null,
    val viewport: ComposerSendViewportProjection? = null,
)

internal data class ComposerSendFlight(
    val presentationId: String,
    val source: ComposerSendSource,
    val inputGeneration: Long,
) {
    val landing = ComposerSendLanding(source.surface, source.composer, source.fill, inputGeneration, source.viewport)
}

internal class ComposerSendMotion {
    var flight: ComposerSendFlight? by mutableStateOf(null)
        private set
    val activePresentationId: String? get() = flight?.presentationId
    private var ownerGeneration = 0L
    var enabled: Boolean by mutableStateOf(false)
    var sourceLayout: TextLayoutResult? = null
    var sourceSurface: LayoutCoordinates? = null
    var sourceEditor: LayoutCoordinates? = null
    var composerCoordinates: LayoutCoordinates? = null
    var composerTarget: ComposerSendComposerTarget? = null
    var readViewport: ((String?) -> ComposerSendViewportProjection?)? = null
    var lastRenderedPendingSource: ComposerSendSource? = null
        private set
    var lastRenderedGeometry: ComposerSendFlightGeometry? = null
        private set
    var sourceFill: Color = Color.Transparent
    var sourceBorder: Color = Color.Transparent
    var sourceTopRadius: Float = 0f
    var sourceBottomRadius: Float = 0f
    var sourceStrokeWidth: Float = 0f
    private var destinationFrame: LayoutCoordinates? by mutableStateOf(null)
    private var destinationText: LayoutCoordinates? by mutableStateOf(null)
    private var destinationLayout: TextLayoutResult? by mutableStateOf(null)
    private var destinationFill = Color.Transparent
    private var destinationBorder = Color.Transparent
    private var destinationCorners = RoundRect(Rect.Zero, CornerRadius.Zero)
    private var destinationStrokeWidth = 0f

    fun capture(text: String): ComposerSendSource? =
        sourceLayout?.let { layout ->
            sourceSurface?.visibleMotionBounds()?.let { surface ->
                sourceEditor?.visibleMotionBounds()?.let { editor ->
                    layout
                        .takeIf {
                            validComposerCaptureText(enabled, text, layout) &&
                                validComposerCaptureLayout(layout, editor)
                        }?.uniformParagraphDirection()
                        ?.let { direction ->
                            ComposerSendSource(
                                text,
                                ownerGeneration,
                                layout,
                                surface,
                                editor,
                                sourceFill,
                                sourceBorder,
                                sourceTopRadius,
                                sourceBottomRadius,
                                sourceStrokeWidth,
                                direction,
                                composerCoordinates?.visibleMotionBounds(),
                                readViewport?.invoke(null),
                            )
                        }
                }
            }
        }

    fun accept(
        source: ComposerSendSource?,
        presentationId: String?,
        inputGeneration: Long,
    ) {
        // An old pre-lock callback must not cancel a newer resumed flight either.
        if (source == null || source.ownerGeneration != ownerGeneration) return
        clear()
        if (enabled && presentationId != null) {
            flight = ComposerSendFlight(presentationId, source, inputGeneration)
        }
    }

    fun clear() {
        lastRenderedGeometry = null
        lastRenderedPendingSource = null
        flight = null
        destinationFrame = null
        destinationText = null
        destinationLayout = null
    }

    fun dispose() {
        enabled = false
        ownerGeneration += 1
        clear()
        sourceLayout = null
        sourceSurface = null
        sourceEditor = null
        composerCoordinates = null
        composerTarget = null
        readViewport = null
    }

    @Suppress("LongParameterList") // Measured destination paint and geometry travel together.
    fun destinationFrame(
        id: String,
        coordinates: LayoutCoordinates,
        fill: Color,
        border: Color,
        corners: RoundRect,
        strokeWidth: Float,
    ) {
        if (activePresentationId != id) return
        if (coordinates.visibleMotionBounds() == null) {
            clear()
            return
        }
        destinationFrame = coordinates
        destinationFill = fill
        destinationBorder = border
        destinationCorners = corners
        destinationStrokeWidth = strokeWidth
    }

    fun destinationText(
        id: String,
        coordinates: LayoutCoordinates,
    ) {
        if (activePresentationId != id) return
        if (coordinates.visibleMotionBounds() == null) clear() else destinationText = coordinates
    }

    fun destinationLayout(
        id: String,
        layout: TextLayoutResult,
    ) {
        if (activePresentationId != id) return
        if (layout.hasVisualOverflow) clear() else destinationLayout = layout
    }

    /** The source copy holds the accepted frame until the target measures, for at most 80 ms. */
    fun hides(id: String): Boolean = enabled && activePresentationId == id

    private val landingMeasurement: ComposerSendLandingMeasurement?
        get() =
            destinationFrame?.visibleMotionBounds()?.let { frame ->
                destinationText?.visibleMotionBounds()?.let { text ->
                    composerCoordinates?.visibleMotionBounds()?.let { composer ->
                        composerTarget?.let { target ->
                            readViewport?.invoke(activePresentationId)?.let { viewport ->
                                ComposerSendLandingMeasurement(frame, text, composer, target, destinationFill, viewport)
                            }
                        }
                    }
                }
            }

    private val hasPaintedLanding: Boolean
        get() =
            lastRenderedGeometry?.let { painted ->
                flight?.landing?.resolve(landingMeasurement, 1f)?.let { current ->
                    painted.settled && current.settled && motionRectsMatch(painted.bounds, current.canonicalFrame)
                }
            } == true

    private fun canRender(): Boolean {
        if (!enabled || flight == null) return false
        val frameVisible = destinationFrame?.visibleMotionBounds() != null
        val textVisible = destinationText?.visibleMotionBounds() != null
        val visible = frameVisible && textVisible
        val sameText = destinationLayout?.layoutInput?.text?.text == flight?.source?.text
        val sameStyle =
            destinationLayout
                ?.layoutInput
                ?.style
                ?.color
                ?.isSpecified == true &&
                destinationLayout?.uniformParagraphDirection() == flight?.source?.paragraphDirection
        val admittedLanding = flight?.landing?.resolve(landingMeasurement, 0f) != null
        val validDestination = visible && sameText && sameStyle
        return validDestination && admittedLanding
    }

    @Composable
    fun Overlay() {
        val active = flight
        val progress = remember(active) { Animatable(0f) }
        var origin by remember { mutableStateOf<LayoutCoordinates?>(null) }
        LaunchedEffect(active) {
            if (active == null) return@LaunchedEffect
            try {
                ComposerSendFlightTiming.run(progress, ::canRender) { hasPaintedLanding }
            } finally {
                if (flight === active) clear()
            }
        }
        val overlayModifier =
            if (active != null && enabled) Modifier.testTag("conversation.send.motion.active") else Modifier
        Canvas(overlayModifier.fillMaxSize().onGloballyPositioned { origin = it }) {
            lastRenderedGeometry = null
            lastRenderedPendingSource = null
            drawFlight(active, origin, progress.value)
        }
    }

    private fun DrawScope.drawFlight(
        active: ComposerSendFlight?,
        origin: LayoutCoordinates?,
        progress: Float,
    ) {
        val canvasOrigin = origin?.takeIf { enabled && it.isAttached }
        if (active == null || flight !== active || canvasOrigin == null) return
        val geometry = active.landing.resolve(landingMeasurement, progress)
        if (geometry == null) {
            if (progress == 0f) {
                lastRenderedPendingSource = active.source
                drawPendingSource(active.source, canvasOrigin.boundsInWindow().topLeft)
            }
            return
        }
        val textTarget = geometry.textTarget
        val layout = destinationLayout ?: active.source.layout
        val windowOrigin = canvasOrigin.boundsInWindow().topLeft
        val p = geometry.progress
        val source = active.source
        val bounds = geometry.bounds

        fun corner(
            from: Float,
            target: CornerRadius,
        ): CornerRadius = CornerRadius(mix(from, target.x, p), mix(from, target.y, p))
        val topLeft = corner(source.topRadius, destinationCorners.topLeftCornerRadius)
        val topRight = corner(source.topRadius, destinationCorners.topRightCornerRadius)
        val bottomRight = corner(source.bottomRadius, destinationCorners.bottomRightCornerRadius)
        val bottomLeft = corner(source.bottomRadius, destinationCorners.bottomLeftCornerRadius)
        val path =
            Path().apply {
                addRoundRect(RoundRect(bounds, topLeft, topRight, bottomRight, bottomLeft))
            }
        translate(-windowOrigin.x, -windowOrigin.y) {
            val fill = geometry.fill
            lastRenderedGeometry = geometry
            drawPath(path, fill)
            drawFlightBorder(
                RoundRect(bounds, topLeft, topRight, bottomRight, bottomLeft),
                source,
                p,
                destinationBorder,
                destinationStrokeWidth,
            )
            clipPath(path) { drawFlightText(source, layout, textTarget, p, fill) }
        }
    }
}

internal object ComposerSendFlightTiming {
    /** Invalid or impractically slow custom scales use the normal presentation without retaining draft ink. */
    fun timeoutMillis(scale: Float): Long? {
        val animationMillis = COMPOSER_SEND_MOTION_MILLIS.toDouble() * scale
        if (!scale.isFinite() || scale < 0f || animationMillis > MAX_SCALED_SEND_MOTION_MILLIS) return null
        return ceil(animationMillis).toLong() + DESTINATION_MEASURE_WAIT_MILLIS
    }

    private const val MAX_SCALED_SEND_MOTION_MILLIS = 10_000L

    /** The animation allowance scales with Compose; the missing-target wait remains at most 80 ms. */
    suspend fun run(
        progress: Animatable<Float, AnimationVector1D>,
        canRender: () -> Boolean,
        hasPaintedLanding: () -> Boolean,
    ) {
        val motionScale = coroutineContext[MotionDurationScale]
        val initialScale = motionScale?.scaleFactor ?: 1f
        val deadline = timeoutMillis(initialScale) ?: return
        val current = { canRender() && (motionScale?.scaleFactor ?: 1f) == initialScale }
        // Admission and final native settlement still share one unscaled 80 ms overhead.
        withTimeoutOrNull(deadline) flightWork@{
            val measured =
                withTimeoutOrNull(DESTINATION_MEASURE_WAIT_MILLIS) {
                    while (!current()) withFrameNanos { }
                    true
                } == true
            if (!measured) return@flightWork
            progress.animateTo(1f, tween(COMPOSER_SEND_MOTION_MILLIS, easing = FastOutSlowInEasing)) {
                if (!current()) throw CancellationException("Send presentation invalidated")
                if (hasPaintedLanding()) throw CancellationException("Accepted copy handed off")
            }
            awaitComposerSendLanding(current, hasPaintedLanding)
        }
    }
}

/** One bounded native-frame settlement wait; no new animation or resettable deadline. */
private suspend fun awaitComposerSendLanding(
    canRender: () -> Boolean,
    hasPaintedLanding: () -> Boolean,
) {
    while (!hasPaintedLanding()) {
        if (!canRender()) throw CancellationException("Send settlement invalidated")
        withFrameNanos { }
    }
}

private fun mix(
    from: Float,
    to: Float,
    progress: Float,
): Float = from + (to - from) * progress

/** Clipped or detached endpoints settle normally rather than revealing hidden draft/message lines. */
private fun LayoutCoordinates.visibleMotionBounds(): Rect? {
    if (!isAttached) return null
    val full = boundsInWindow(clipBounds = false)
    val visible = boundsInWindow()
    val horizontal = abs(full.left - visible.left) <= 1f && abs(full.right - visible.right) <= 1f
    val vertical = abs(full.top - visible.top) <= 1f && abs(full.bottom - visible.bottom) <= 1f
    return full.takeIf { !it.isEmpty && horizontal && vertical }
}

/** One route owner, one overlay, and no saved message text or input interception. */
@Composable
internal fun ComposerSendMotionHost(
    motion: ComposerSendMotion,
    content: @Composable () -> Unit,
) {
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    CompositionLocalProvider(LocalComposerSendMotion provides motion) {
        Box(Modifier.fillMaxSize()) {
            content()
            motion.Overlay()
        }
    }
}

/** Keep ink readable while the surface changes tone; interpolating opposite inks crosses zero contrast. */
internal fun composerMotionForeground(
    source: Color,
    destination: Color,
    fill: Color,
): Color {
    val backgroundLuminance = fill.luminance()

    fun contrast(foreground: Color): Float {
        val inkLuminance = foreground.compositeOver(fill).luminance()
        return (maxOf(inkLuminance, backgroundLuminance) + TEXT_CONTRAST_OFFSET) /
            (minOf(inkLuminance, backgroundLuminance) + TEXT_CONTRAST_OFFSET)
    }
    return if (contrast(source) >= contrast(destination)) source else destination
}

private const val TEXT_CONTRAST_OFFSET = 0.05f

/** Differing paragraph bases cannot share one text anchor without inventing another layout owner. */
private fun TextLayoutResult.uniformParagraphDirection(): ResolvedTextDirection? {
    if (lineCount == 0) return null
    val direction = getParagraphDirection(0)
    return direction.takeIf {
        (1 until lineCount).all { line -> getParagraphDirection(getLineStart(line)) == direction }
    }
}

// Trimmed sends, transformed mentions, clipped drafts and enormous layouts take the normal path.
private fun validComposerCaptureText(
    enabled: Boolean,
    text: String,
    layout: TextLayoutResult,
): Boolean {
    val plainText = text.isNotBlank() && text == text.trim()
    val matchingText = text.length <= MAX_COMPOSER_MOTION_CHARACTERS && layout.layoutInput.text.text == text
    return enabled && plainText && matchingText
}

private fun validComposerCaptureLayout(
    layout: TextLayoutResult,
    editor: Rect,
): Boolean {
    val fitsEditor = layout.size.height <= editor.height + 1f && layout.size.width <= editor.width + 1f
    return !layout.hasVisualOverflow && layout.layoutInput.style.color.isSpecified && fitsEditor
}

/** Keep the captured accepted ink visible during the bounded, zero-progress measurement wait. */
private fun DrawScope.drawPendingSource(
    source: ComposerSendSource,
    windowOrigin: Offset,
) {
    val top = CornerRadius(source.topRadius)
    val bottom = CornerRadius(source.bottomRadius)
    val outline = RoundRect(source.surface, top, top, bottom, bottom)
    val path = Path().apply { addRoundRect(outline) }
    translate(-windowOrigin.x, -windowOrigin.y) {
        drawPath(path, source.fill)
        drawFlightBorder(outline, source, 0f, source.border, source.strokeWidth)
        clipPath(path) { drawFlightText(source, source.layout, source.editor, 0f, source.fill) }
    }
}

private fun DrawScope.drawFlightBorder(
    outline: RoundRect,
    source: ComposerSendSource,
    p: Float,
    destinationBorder: Color,
    destinationStrokeWidth: Float,
) {
    val strokeWidth = mix(source.strokeWidth, destinationStrokeWidth, p)
    if (strokeWidth > 0f) {
        val inset = strokeWidth / 2f

        fun insetCorner(corner: CornerRadius) =
            CornerRadius(
                (corner.x - inset).coerceAtLeast(0f),
                (corner.y - inset).coerceAtLeast(0f),
            )
        val borderPath =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(outline.left, outline.top, outline.right, outline.bottom).deflate(inset),
                        insetCorner(outline.topLeftCornerRadius),
                        insetCorner(outline.topRightCornerRadius),
                        insetCorner(outline.bottomRightCornerRadius),
                        insetCorner(outline.bottomLeftCornerRadius),
                    ),
                )
            }
        drawPath(
            borderPath,
            androidx.compose.ui.graphics
                .lerp(source.border, destinationBorder, p),
            style = Stroke(strokeWidth),
        )
    }
}

private fun DrawScope.drawFlightText(
    source: ComposerSendSource,
    layout: TextLayoutResult,
    textTarget: Rect,
    p: Float,
    fill: Color,
) {
    val rightAnchored = source.paragraphDirection == ResolvedTextDirection.Rtl
    val anchorX =
        if (rightAnchored) {
            mix(source.editor.right, textTarget.right, p)
        } else {
            mix(source.editor.left, textTarget.left, p)
        }
    val textY = mix(source.editor.top, textTarget.top, p)
    val sourceOrigin = Offset(anchorX - if (rightAnchored) source.layout.size.width else 0, textY)
    val targetOrigin = Offset(anchorX - if (rightAnchored) layout.size.width else 0, textY)
    // Preserve font size. Reflowed multiline layouts crossfade without scaling letters.
    val blend = ((p - TEXT_REFLOW_BLEND_START) / TEXT_REFLOW_BLEND_INTERVAL).coerceIn(0f, 1f)
    val sourceColor = source.layout.layoutInput.style.color
    val targetColor =
        layout.layoutInput.style.color
            .takeOrElse { sourceColor }
    val foreground = composerMotionForeground(sourceColor, targetColor, fill)
    drawText(source.layout, color = foreground, topLeft = sourceOrigin, alpha = 1f - blend)
    drawText(layout, color = foreground, topLeft = targetOrigin, alpha = blend)
}
