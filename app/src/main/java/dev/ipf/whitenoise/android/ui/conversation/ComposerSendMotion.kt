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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
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

/** Optional geometry-only draw observer; no message text or identity leaves the motion owner. */
internal val LocalComposerSendGeometryObserver =
    staticCompositionLocalOf<((ComposerSendFlightGeometry) -> Unit)?> { null }

/** Optional source-owner diagnostics; reports only bounded presentation flags, never message content. */
internal val LocalComposerSendOwnerObserver = staticCompositionLocalOf<((ComposerSendMotion) -> Unit)?> { null }
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
    val wholePill: Rect = surface,
)

internal data class ComposerSendFlight(
    val presentationId: String,
    val source: ComposerSendSource,
    val inputGeneration: Long,
) {
    val landing =
        ComposerSendLanding(
            source.surface,
            source.composer,
            source.fill,
            inputGeneration,
            source.viewport,
            source.wholePill.bottom,
        )
    var admitted = false
    var retired = false
}

/** One pure presentation choice shared by native alpha and the traveling copy. */
internal data class ComposerSendDrawDecision(
    val geometry: ComposerSendFlightGeometry? = null,
    val pendingSource: ComposerSendSource? = null,
) {
    val coversNative: Boolean get() = geometry != null || pendingSource != null
}

internal class ComposerSendMotion {
    /** Test-installed, geometry-only observer; absent in normal UI. */
    internal var onLandingDiagnostic: ((String, ComposerSendLandingDiagnostic) -> Unit)? = null

    var flight: ComposerSendFlight? by mutableStateOf(null)
        private set
    val activePresentationId: String? get() = flight?.presentationId
    private var ownerGeneration = 0L
    private var readProgress: () -> Float = { 0f }

    // An actual Canvas fallback decision retires only this flight, even if eligibility recovers before timing.
    private var fallbackDecisionFlight: ComposerSendFlight? = null
    private var motionEnabled by mutableStateOf(false)

    /** Retire old callbacks and ink while preserving measurements owned by mounted content. */
    var enabled: Boolean
        get() = motionEnabled
        set(eligible) {
            if (motionEnabled && !eligible) {
                ownerGeneration += 1
                clear("ineligible")
            }
            motionEnabled = eligible
        }
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
    private var destinationFrame: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
    private var destinationText: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
    private var destinationLayout: TextLayoutResult? by mutableStateOf(null)
    private var destinationFill = Color.Transparent
    private var destinationBorder = Color.Transparent
    private var destinationCorners = RoundRect(Rect.Zero, CornerRadius.Zero)
    private var destinationStrokeWidth = 0f

    private var captureAttempts = 0
    private var lastCaptureSucceeded = false
    private var acceptAttempts = 0
    private var admittedFlights = 0
    private var lastClearReason = "none"
    private var lastClearedActiveFlight = false

    /** Distinguishes absent capture/admission/landing/draw boundaries without pretending idle means painted. */
    internal val diagnosticState: String
        get() =
            "captureAttempts=$captureAttempts captureSucceeded=$lastCaptureSucceeded " +
                "acceptAttempts=$acceptAttempts admittedFlights=$admittedFlights " +
                "clearReason=$lastClearReason clearedActive=$lastClearedActiveFlight " +
                "enabled=$enabled sourceLayout=${sourceLayout != null} " +
                "sourceSurface=${sourceSurface?.isAttached == true} sourceEditor=${sourceEditor?.isAttached == true} " +
                "flight=${flight != null} destinationFrame=${destinationFrame?.isAttached == true} " +
                "destinationText=${destinationText?.isAttached == true} " +
                "destinationLayout=${destinationLayout != null} " +
                "landingMeasured=${landingMeasurement != null} painted=${lastRenderedGeometry != null}"

    fun capture(text: String): ComposerSendSource? =
        sourceLayout
            ?.let { layout ->
                sourceSurface?.visibleMotionBounds()?.let { surface ->
                    sourceEditor?.visibleMotionBounds()?.let { editor ->
                        layout
                            .takeIf {
                                validComposerCaptureText(enabled, text, layout) &&
                                    validComposerCaptureLayout(layout, editor)
                            }?.uniformParagraphDirection()
                            ?.let { direction ->
                                ComposerSendSource(
                                    text.trimEnd(' '),
                                    ownerGeneration,
                                    layout,
                                    editor,
                                    editor,
                                    sourceFill,
                                    Color.Transparent,
                                    0f,
                                    0f,
                                    0f,
                                    direction,
                                    composerCoordinates?.visibleMotionBounds(),
                                    readViewport?.invoke(null),
                                    surface,
                                )
                            }
                    }
                }
            }.also { captured ->
                captureAttempts = (captureAttempts + 1).coerceAtMost(MAX_DIAGNOSTIC_ATTEMPTS)
                lastCaptureSucceeded = captured != null
            }

    /** True only when this current acceptance installs a new flight; a captured source alone is not admission. */
    fun accept(
        source: ComposerSendSource?,
        presentationId: String?,
        inputGeneration: Long,
    ): Boolean {
        acceptAttempts = (acceptAttempts + 1).coerceAtMost(MAX_DIAGNOSTIC_ATTEMPTS)
        // An old pre-lock callback must not cancel a newer resumed flight either.
        if (source == null || source.ownerGeneration != ownerGeneration) return false
        clear("accepted-replacement")
        return if (enabled && presentationId != null) {
            flight = ComposerSendFlight(presentationId, source, inputGeneration)
            admittedFlights = (admittedFlights + 1).coerceAtMost(MAX_DIAGNOSTIC_ATTEMPTS)
            true
        } else {
            false
        }
    }

    fun clear(reason: String = "external-owner") {
        lastClearReason = reason
        lastClearedActiveFlight = flight != null
        lastRenderedGeometry = null
        lastRenderedPendingSource = null
        fallbackDecisionFlight = null
        flight = null
        destinationFrame = null
        destinationText = null
        destinationLayout = null
    }

    fun dispose() {
        enabled = false
        ownerGeneration += 1
        clear("disposed")
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
        if (coordinates.visibleMotionBounds() == null && flight?.admitted == true) {
            clear("destination-frame-unavailable")
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
        if (coordinates.visibleMotionBounds() == null && flight?.admitted == true) {
            clear("destination-text-unavailable")
            return
        }
        destinationText = coordinates
    }

    fun destinationLayout(
        id: String,
        layout: TextLayoutResult,
    ) {
        if (activePresentationId != id) return
        val source = flight?.source ?: return
        val sameInk =
            layout.layoutInput.text.text == source.text &&
                layout.layoutInput.style.color.isSpecified &&
                layout.uniformParagraphDirection() == source.paragraphDirection
        when {
            layout.hasVisualOverflow -> clear("destination-text-overflow")
            !sameInk -> clear("destination-ink-changed")
            else -> destinationLayout = layout
        }
    }

    /** Layer and Canvas read the same current measurement and timer, without committing endpoint history. */
    fun presentation(id: String): ComposerSendDrawDecision {
        val active =
            flight
                ?.takeIf { enabled && it.presentationId == id && !it.retired }
                ?.takeUnless { fallbackDecisionFlight === it }
                ?: return ComposerSendDrawDecision()
        val layout = destinationLayout
        val progress = readProgress()
        // Only destinationLayout() publishes validated ink; clear() retires it with its flight.
        val geometry =
            if (layout != null) {
                resolveComposerSendLanding(active, progress, "presentation", onLandingDiagnostic, landingMeasurement)
            } else {
                null
            }
        return if (geometry != null) {
            ComposerSendDrawDecision(geometry = geometry)
        } else if (!active.admitted && progress == 0f) {
            ComposerSendDrawDecision(pendingSource = active.source)
        } else {
            ComposerSendDrawDecision()
        }
    }

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
                val current = flight?.let { presentation(it.presentationId).geometry }
                painted.settled && current?.settled == true && motionRectsMatch(painted.bounds, current.canonicalFrame)
            } == true

    private fun canRender(): Boolean {
        val active = flight
        val geometry = active?.let { presentation(it.presentationId).geometry }
        return if (active != null && geometry != null) {
            active.landing.commit(geometry)
            active.admitted = true
            true
        } else {
            if (active?.admitted == true) active.retired = true
            false
        }
    }

    @Composable
    fun Overlay() {
        val ownerObserver = LocalComposerSendOwnerObserver.current
        SideEffect { ownerObserver?.invoke(this) }
        val active = flight
        val geometryObserver = LocalComposerSendGeometryObserver.current
        val progress = remember(active) { Animatable(0f) }
        SideEffect { readProgress = { progress.value } }
        var origin by remember { mutableStateOf<LayoutCoordinates?>(null) }
        LaunchedEffect(active) {
            if (active == null) return@LaunchedEffect
            try {
                ComposerSendFlightTiming.run(progress, ::canRender) { hasPaintedLanding }
            } finally {
                if (flight === active) clear("overlay-finished")
            }
        }
        val overlayModifier =
            if (active != null && enabled) Modifier.testTag("conversation.send.motion.active") else Modifier
        Canvas(overlayModifier.fillMaxSize().onGloballyPositioned { origin = it }) {
            lastRenderedGeometry = null
            lastRenderedPendingSource = null
            drawFlight(active, origin, geometryObserver)
        }
    }

    /** Idle or replaced ink observes no eligibility or geometry; a current flight retains all paint guards. */
    internal fun DrawScope.drawFlight(
        active: ComposerSendFlight?,
        origin: LayoutCoordinates?,
        geometryObserver: ((ComposerSendFlightGeometry) -> Unit)?,
    ) {
        // Idle ink must not subscribe the full-window canvas to modal eligibility changes.
        val canvasOrigin = origin?.takeIf { active != null && flight === active && enabled && it.isAttached }
        if (active == null || canvasOrigin == null) return
        val decision = presentation(active.presentationId)
        val geometry = decision.geometry
        if (geometry == null) {
            if (decision.pendingSource == null && active.admitted) fallbackDecisionFlight = active
            decision.pendingSource?.let { source ->
                lastRenderedPendingSource = source
                drawPendingSource(source, canvasOrigin.boundsInWindow().topLeft)
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
            geometryObserver?.invoke(geometry)
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
    motionEnabled: Boolean = rememberConversationMotionEnabled(),
    content: @Composable () -> Unit,
) {
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    val reactionWindow =
        dev.ipf.whitenoise.android.ui.conversation.reactions
            .rememberReactionWindowCoordinates()
    CompositionLocalProvider(
        LocalComposerSendMotion provides motion,
        LocalConversationMotionEnabled provides motionEnabled,
        dev.ipf.whitenoise.android.ui.conversation.reactions.LocalReactionWindowCoordinates provides reactionWindow,
    ) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { reactionWindow.update() }) {
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

private const val MAX_DIAGNOSTIC_ATTEMPTS = 255
private const val TEXT_CONTRAST_OFFSET = 0.05f

/** Differing paragraph bases cannot share one text anchor without inventing another layout owner. */
private fun TextLayoutResult.uniformParagraphDirection(): ResolvedTextDirection? {
    if (lineCount == 0) return null
    val direction = getParagraphDirection(0)
    return direction.takeIf {
        (1 until lineCount).all { line -> getParagraphDirection(getLineStart(line)) == direction }
    }
}

// Leading/line-breaking whitespace, transformed text and clipped layouts keep the direct presentation.
private fun validComposerCaptureText(
    enabled: Boolean,
    text: String,
    layout: TextLayoutResult,
): Boolean {
    val canonical = text.trimEnd(' ')
    val ordinaryInk = canonical.isNotBlank() && canonical == text.trim()
    val matchingText = text.length <= MAX_COMPOSER_MOTION_CHARACTERS && layout.layoutInput.text.text == text
    val sameLastLine =
        if (matchingText && canonical.isNotEmpty()) {
            layout.getLineForOffset(canonical.lastIndex) == layout.lineCount - 1
        } else {
            false
        }
    return enabled && ordinaryInk && matchingText && sameLastLine
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
    if (blend < 1f) drawText(source.layout, color = foreground, topLeft = sourceOrigin, alpha = 1f - blend)
    if (blend > 0f) drawText(layout, color = foreground, topLeft = targetOrigin, alpha = blend)
}
