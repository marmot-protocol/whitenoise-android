@file:Suppress("FunctionNaming") // Compose surface functions follow UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

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
)

internal data class ComposerSendFlight(
    val presentationId: String,
    val source: ComposerSendSource,
    val inputGeneration: Long,
)

internal class ComposerSendMotion {
    var flight: ComposerSendFlight? by mutableStateOf(null)
        private set
    val activePresentationId: String? get() = flight?.presentationId
    private var ownerGeneration = 0L
    var enabled: Boolean by mutableStateOf(false)
    var sourceLayout: TextLayoutResult? = null
    var sourceSurface: LayoutCoordinates? = null
    var sourceEditor: LayoutCoordinates? = null
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

    fun capture(text: String): ComposerSendSource? {
        val layout = sourceLayout ?: return null
        val surface = sourceSurface?.visibleMotionBounds() ?: return null
        val editor = sourceEditor?.visibleMotionBounds() ?: return null
        // Trimmed sends, transformed mentions, clipped drafts and enormous layouts take the normal path.
        if (!enabled || text.isBlank()) return null
        if (text != text.trim() || text.length > MAX_COMPOSER_MOTION_CHARACTERS) return null
        if (layout.layoutInput.text.text != text || layout.hasVisualOverflow) return null
        if (!layout.layoutInput.style.color.isSpecified) return null
        val paragraphDirection = layout.uniformParagraphDirection() ?: return null
        if (layout.size.height > editor.height + 1f || layout.size.width > editor.width + 1f) return null
        return ComposerSendSource(
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
            paragraphDirection,
        )
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

    private fun canRender(): Boolean {
        if (!enabled || flight == null) return false
        val frameVisible = destinationFrame?.visibleMotionBounds() != null
        val textVisible = destinationText?.visibleMotionBounds() != null
        if (!frameVisible || !textVisible) return false
        return destinationLayout?.layoutInput?.text?.text == flight?.source?.text &&
            destinationLayout
                ?.layoutInput
                ?.style
                ?.color
                ?.isSpecified == true &&
            destinationLayout?.uniformParagraphDirection() == flight?.source?.paragraphDirection
    }

    @Suppress("LongMethod") // One draw pass keeps the surface and text on the same motion clock.
    @Composable
    fun Overlay() {
        val active = flight
        val progress = remember(active) { Animatable(0f) }
        var origin by remember { mutableStateOf<LayoutCoordinates?>(null) }
        LaunchedEffect(active) {
            if (active == null) return@LaunchedEffect
            try {
                val measured =
                    withTimeoutOrNull(DESTINATION_MEASURE_WAIT_MILLIS) {
                        while (!canRender()) withFrameNanos { }
                        true
                    } == true
                if (!measured) return@LaunchedEffect
                progress.animateTo(1f, tween(COMPOSER_SEND_MOTION_MILLIS, easing = FastOutSlowInEasing)) {
                    if (!canRender()) throw CancellationException("Send presentation invalidated")
                }
            } finally {
                if (flight === active) clear()
            }
        }
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it }) {
            if (active == null || flight !== active) return@Canvas
            if (!enabled || origin?.isAttached != true) return@Canvas
            val target = destinationFrame?.takeIf { it.isAttached }?.boundsInWindow() ?: active.source.surface
            val textTarget = destinationText?.takeIf { it.isAttached }?.boundsInWindow() ?: active.source.editor
            val layout = destinationLayout ?: active.source.layout
            val windowOrigin = origin?.boundsInWindow()?.topLeft ?: return@Canvas
            val p = progress.value
            val source = active.source
            val bounds =
                Rect(
                    mix(source.surface.left, target.left, p),
                    mix(source.surface.top, target.top, p),
                    mix(source.surface.right, target.right, p),
                    mix(source.surface.bottom, target.bottom, p),
                )

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
                val fill =
                    androidx.compose.ui.graphics
                        .lerp(source.fill, destinationFill, p)
                drawPath(path, fill)
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
                                    bounds.deflate(inset),
                                    insetCorner(topLeft),
                                    insetCorner(topRight),
                                    insetCorner(bottomRight),
                                    insetCorner(bottomLeft),
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
                clipPath(path) {
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
            }
        }
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
    for (line in 1 until lineCount) {
        if (getParagraphDirection(getLineStart(line)) != direction) return null
    }
    return direction
}
