package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledRowSeamColor
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorderPath
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlin.math.ceil
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
)

/** One route clock owns continuous opaque chrome and the separately permitted content picture. */
@Suppress("TooManyFunctions")
internal class SettingsPageMorph {
    var pendingOrigin: Rect? = null
    var pendingEndpoint: SettingsRouteEndpoint? = null
    private val destinations = mutableMapOf<Any, SettingsRouteEndpoint>()
    private val rows = mutableMapOf<Any, MutableMap<Any, SettingsRouteEndpoint>>()
    private val viewports = mutableMapOf<Any, Rect>()

    private data class ReturnTarget(
        val point: Offset,
        val groupKey: Int?,
    )

    private val returnTargets = mutableMapOf<Any?, ReturnTarget>()
    private val routeGroupKeys = mutableMapOf<Any?, MutableMap<Any, Int>>()
    private var nextGroupKey = 1
    private var correspondenceKey: Int? = null
    private var sourceContours: List<SettingsContour> = emptyList()
    private var targetContours: List<SettingsContour>? = null
    private var rebaseTime = 0f
    private var settledContours: List<SettingsContour> = emptyList()
    private var leavingContours: List<SettingsContour> = emptyList()
    private var transitionHost = Rect.Zero
    private var target: Any? = null
    private var outgoingTarget: Any? = null
    private var origin: Rect? = null
    private var exitStart = 1f
    private var entryStart = 0f
    private var entryOnly = false
    internal var captureInputs: (() -> Any?)? = null
    internal var childContours: (() -> List<SettingsContour>?)? = null
    private var settledInputs: Any? = null
    private var leavingInputs: Any? = null
    private var settled: Picture? = null
    private var leaving: Picture? = null
    private var maskFrame: List<SettingsContour>? = null
    private var maskHost: Rect = Rect.Zero
    private var mask: Path = Path()
    private var leavingMask: Path? = null
    var opening = true
        private set
    val active: Boolean get() = origin != null
    val keptPictures: Int get() = if (leaving != null) 1 else 0
    internal val hasSettledPicture: Boolean get() = settled != null

    fun reportDestination(
        token: Any,
        endpoint: SettingsRouteEndpoint,
    ) {
        destinations[token] = endpoint
    }

    fun removeDestination(token: Any) {
        destinations.remove(token)
        rows.remove(token)
    }

    fun reportRow(
        group: Any,
        token: Any,
        endpoint: SettingsRouteEndpoint,
    ) {
        rows.getOrPut(group) { mutableMapOf() }[token] = endpoint
    }

    fun removeRow(
        group: Any,
        token: Any,
    ) {
        rows[group]?.remove(token)
    }

    fun reportViewport(
        token: Any,
        bounds: Rect,
    ) {
        viewports[token] = bounds
    }

    fun removeViewport(token: Any) {
        viewports.remove(token)
    }

    // Null native layouts cancel; absent and empty destination frames have separate admission gates.
    @Suppress("ReturnCount")
    fun admitDestination(): Boolean {
        if (!active) return false
        val native = nativeContours(transitionHost)
        if (native == null) {
            cancel()
            return false
        }
        targetContours = native
        return true
    }

    internal fun outgoingPresence(time: Float): Float =
        if (!active || entryOnly) {
            0f
        } else {
            exitStart * (1f - (time / SETTINGS_OUTGOING_PHASE_END).coerceIn(0f, 1f))
        }

    internal fun incomingPresence(time: Float): Float {
        if (!active) return 1f
        val fraction = if (entryOnly) time else (time - SETTINGS_OUTGOING_PHASE_END) / 0.6f
        return entryStart + (1f - entryStart) * fraction.coerceIn(0f, 1f)
    }

    /** Freeze exactly what was drawn before replacement; native target movement rebases this same clock. */
    internal fun renderedContours(time: Float): List<SettingsContour> {
        val end = targetContours ?: return sourceContours
        val easing = if (opening) AppMotionOpenEasing else AppMotionCloseEasing
        val eased = easing.transform(time.coerceIn(0f, 1f))
        val easedRebase = easing.transform(rebaseTime)
        val fraction = if (easedRebase >= 1f) 1f else ((eased - easedRebase) / (1f - easedRebase)).coerceIn(0f, 1f)
        val paintFraction = if (rebaseTime >= 1f) 1f else ((time - rebaseTime) / (1f - rebaseTime)).coerceIn(0f, 1f)
        return interpolateSettingsContours(
            sourceContours,
            end,
            fraction,
            origin?.center ?: transitionHost.center,
            transitionHost,
            correspondenceKey,
            paintFraction,
        )
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
    fun begin(
        next: Any?,
        forward: Boolean,
        animate: Boolean,
        host: Rect,
        currentTime: Float = 1f,
    ): Boolean {
        val changedHost = transitionHost != Rect.Zero && transitionHost != host
        if (changedHost || !animate) returnTargets.clear()
        if (changedHost || settledInputs != captureInputs?.invoke()) clearPictures()
        val oldExit = outgoingPresence(currentTime)
        val oldEntry = incomingPresence(currentTime)
        val interrupted = active && currentTime < 1f && host == transitionHost
        val current = if (interrupted) renderedContours(currentTime) else nativeContours(host)
        val returnsToVisibleParent = interrupted && oldExit > 0f && next == outgoingTarget
        val retainsOutgoing = interrupted && oldExit > 0f && !returnsToVisibleParent
        val selected =
            if (pendingEndpoint != null) {
                pendingEndpoint?.takeIf { it.destination == next }?.bounds
            } else {
                pendingOrigin
            }
        val returnTarget = returnTargets[target]
        val returnPoint = returnTarget?.point
        // Resolve the activating row against native groups before freezing or clearing their reports.
        val activatedGroup =
            selected?.let { bounds ->
                nativeContours(
                    host,
                )?.takeIf { it.isNotEmpty() }?.let { it[nearestSettingsContour(it, bounds.center)].key }
            }
        correspondenceKey =
            if (forward) activatedGroup ?: correspondenceKey else returnTarget?.groupKey ?: correspondenceKey
        val eligible =
            animate &&
                !changedHost &&
                !host.isEmpty &&
                current != null &&
                (selected?.validIn(host) == true || (!forward && returnPoint != null) || interrupted)
        if (forward) {
            if (eligible &&
                selected?.validIn(host) == true
            ) {
                returnTargets[next] = ReturnTarget(selected.center, correspondenceKey)
            } else {
                returnTargets.remove(next)
            }
        } else {
            returnTargets.remove(target)
        }
        if (!retainsOutgoing) {
            leavingContours = settledContours
            // Bind the cached mask to this frozen picture; raster construction belongs to draw.
            leavingMask = null
            leaving = settled.takeIf { settledInputs == captureInputs?.invoke() }
            leavingInputs = settledInputs
            outgoingTarget = target
        }
        sourceContours = current.orEmpty()
        targetContours = null
        rebaseTime = 0f
        settled = null
        exitStart =
            if (retainsOutgoing) {
                oldExit
            } else if (interrupted) {
                oldEntry
            } else {
                1f
            }
        entryStart = if (returnsToVisibleParent) oldExit else 0f
        entryOnly = returnsToVisibleParent || (leaving == null && sourceContours.isEmpty())
        if (returnsToVisibleParent) leaving = null
        val anchor = selected ?: returnPoint?.let { Rect(it, it) } ?: host
        origin = anchor.takeIf { eligible }
        transitionHost = host
        target = next
        opening = forward
        destinations.clear()
        rows.clear()
        pendingOrigin = null
        pendingEndpoint = null
        if (!active) finish()
        return active
    }

    /** A parent records the same contours the child currently paints, including an interrupted frame. */
    internal fun presentedContours(
        host: Rect,
        time: Float,
    ): List<SettingsContour>? =
        if (active && host == transitionHost && time < 1f) {
            renderedContours(time)
        } else {
            nativeContours(host)
        }

    /** Full native bounds are retained; the separate containing viewport clips offscreen edges. */
    private fun nativeContours(host: Rect) = childContours?.let { it() } ?: ownNativeContours(host)

    // Native measurement and stable route-group correspondence are one batch.

    @Suppress("LongMethod", "ReturnCount")
    // Invalid host and invalid measured groups reject before building the native contour batch.

    private fun ownNativeContours(host: Rect): List<SettingsContour>? {
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
                    )
                },
            ) ?: return null
        if (contours.isEmpty()) return contours
        val saved = routeGroupKeys.getOrPut(target) { mutableMapOf() }
        nextGroupKey = maxOf(nextGroupKey, (sourceContours.maxOfOrNull { it.key } ?: 0) + 1)
        val selectedIndex = nearestSettingsContour(contours, origin?.center ?: contours.first().bounds.center)
        val sourceIndex = sourceContours.indexOfFirst { it.key == correspondenceKey }
        val keys =
            contours.indices.map { index ->
                val identity = groups[index].value.key
                val occurrence = groups.take(index).count { it.value.key == identity }
                saved.getOrPut(identity to occurrence) {
                    sourceContours.getOrNull(sourceIndex + index - selectedIndex)?.key?.takeIf {
                        sourceIndex >= 0 &&
                            it !in saved.values
                    }
                        ?: nextGroupKey++
                }
            }
        return contours.mapIndexed { index, contour -> contour.copy(key = keys[index]) }
    }

    fun finish() {
        origin = null
        leaving = null
        sourceContours = emptyList()
        targetContours = null
        leavingContours = emptyList()
        leavingMask = null
    }

    fun cancel() {
        finish()
        clearPictures()
        returnTargets.clear()
        routeGroupKeys.clear()
        correspondenceKey = null
    }

    fun clearPictures() {
        settled = null
        leaving = null
        settledContours = emptyList()
        leavingContours = emptyList()
        leavingMask = null
    }

    fun bounds(
        host: Rect,
        time: Float,
    ): Rect? {
        if (!time.isFinite() || host != transitionHost) {
            finish()
            clearPictures()
            returnTargets.clear()
        }
        return host.takeIf { active }
    }

    @Suppress("LongMethod", "LongParameterList")
    fun ContentDrawScope.draw(
        host: Rect,
        time: Float,
        recording: Boolean,
        backplate: Color,
        captureContent: Boolean = true,
    ) {
        val inputs = captureInputs?.invoke()
        if (!recording) clearPictures()
        if (leavingInputs != inputs) leaving = null
        if (!captureContent || settledInputs != inputs) settled = null
        val native = nativeContours(host)
        if (native == null) {
            finish()
            clearPictures()
        }
        if (bounds(host, time) == null || time >= 1f) {
            leaving = null
            if (recording && captureContent) {
                val picture = settled ?: Picture().also { settled = it }
                drawAndRecord(picture)
                settledInputs = inputs
                settledContours = native.orEmpty()
            } else {
                drawContent()
            }
            return
        }
        updateNativeFrame(native, host, time)
        drawRect(backplate)
        val body = activeNativeBody(native.orEmpty(), host)
        clipPath(body, ClipOp.Difference) {
            clipPath(mask, ClipOp.Difference) { this@draw.drawContent() }
        }
        drawIncomingBody(body, time)
        renderedContours(time).forEach { drawContinuousContour(it, host) }
    }

    /** Commit one measured native batch to this owner before painting its body and chrome. */
    private fun updateNativeFrame(
        native: List<SettingsContour>?,
        host: Rect,
        time: Float,
    ) {
        if (targetContours != null && targetContours != native) {
            // All layout callbacks precede drawing; one coherent measured batch updates the target.
            sourceContours = renderedContours(time)
            rebaseTime = time.coerceIn(0f, 1f)
            targetContours = native
        }
        // Body ink is revealed in place after the contour has nearly settled. No old route
        // picture travels with the outline or covers the destination's fixed header.
        // Headers and other ink outside the measured groups remain live throughout.
        if (maskFrame != native || maskHost != host) {
            maskHost = host
            maskFrame = native
            mask = nativeChromeMask(native.orEmpty(), host)
        }
    }

    private fun activeNativeBody(
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

    private fun ContentDrawScope.drawIncomingBody(
        body: Path,
        time: Float,
    ) {
        val incoming = ((incomingPresence(time) - 0.75f) / 0.25f).coerceIn(0f, 1f)
        if (incoming > 0f) {
            clipPath(body) {
                clipPath(mask, ClipOp.Difference) {
                    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = incoming }) }
                    this@drawIncomingBody.drawContent()
                    drawIntoCanvas { it.restore() }
                }
            }
        }
    }
}

/** Native group interiors only: no header replay and no travelling cell-sized crop. */
private fun nativeBodyMask(
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
private fun nativeChromeMask(
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

private fun DrawScope.drawContinuousContour(
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

private fun Rect.validIn(host: Rect): Boolean =
    listOf(left, top, right, bottom).all { it.isFinite() } &&
        !isEmpty &&
        !host.isEmpty &&
        left >= host.left &&
        top >= host.top &&
        right <= host.right &&
        bottom <= host.bottom

/** Retain just a control rectangle, never pixels or a long-lived navigation registry. */
@Composable
internal fun rememberMeasuredControlClick(onClick: (Rect?) -> Unit): Pair<Modifier, () -> Unit> {
    val view = LocalView.current
    val bounds = remember { arrayOf(Rect.Zero) }
    return Modifier.onGloballyPositioned { bounds[0] = it.settingsMorphBounds(view) } to {
        onClick(bounds[0].takeUnless { it.isEmpty })
    }
}

/** Compatibility facade for existing rectangle-only launchers. */
@Composable
internal fun rememberSettingsRouteClick(onClick: () -> Unit) = rememberSettingsRouteClick(null, null, onClick)

/** Capture the actual activating row, including its scrolled position, before navigation changes it. */
@Composable
// Reject missing route owners before installing the attached-origin callback.
@Suppress("ReturnCount")
internal fun rememberSettingsRouteClick(
    context: SettingsRowContext?,
    routeKey: Any?,
    onClick: () -> Unit,
): Pair<Modifier, () -> Unit> {
    if (context != null && routeKey == null) return Modifier to onClick
    val morph = LocalSettingsCardMorph.current ?: return Modifier to onClick
    val page = morph.page ?: return Modifier to onClick
    val view = LocalView.current
    val bounds = remember(page) { arrayOf(Rect.Zero) }
    val token = remember(page, routeKey) { Any() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val endpoint = remember(page, token) { arrayOfNulls<SettingsRouteEndpoint>(1) }
    val attached = remember(page, token) { booleanArrayOf(true) }
    DisposableEffect(page, token) {
        attached[0] = true
        onDispose { attached[0] = false }
    }
    return Modifier
        .onGloballyPositioned {
            val rect = it.settingsMorphBounds(view)
            bounds[0] = rect
            if (context != null && routeKey != null && !rect.isEmpty) {
                val shape = context.shapes.shape
                val corners =
                    when (val outline = shape.createOutline(rect.size, direction, density)) {
                        is Outline.Rounded -> outline.roundRect
                        else -> RoundRect(Rect(Offset.Zero, rect.size))
                    }
                val measured =
                    SettingsRouteEndpoint(
                        routeKey,
                        routeKey,
                        rect,
                        corners,
                        context.containerColor,
                        context.borderColor,
                        (shape as? ConnectedRowShape)?.first ?: true,
                        (shape as? ConnectedRowShape)?.last ?: true,
                        if (context.borderColor.isSpecified) {
                            amoledRowSeamColor(
                                context.borderColor,
                            )
                        } else {
                            context.borderColor
                        },
                    )
                endpoint[0] = measured
            }
        } to {
        if (attached[0]) {
            page.pendingOrigin = bounds[0].takeUnless { it.isEmpty }
            page.pendingEndpoint = endpoint[0]
            onClick()
        }
    }
}

/** Controls inside popup windows and their destination host share screen coordinates. */
internal fun LayoutCoordinates.settingsMorphBounds(view: View): Rect {
    val screen = IntArray(2)
    val window = IntArray(2)
    view.getLocationOnScreen(screen)
    view.getLocationInWindow(window)
    return boundsInWindow().translate(Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()))
}

/** A caller declares the actual native group; absent outlines never acquire synthetic chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
internal fun rememberSettingsNativeSurface(
    destination: Any?,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    surface: Any = destination ?: Unit,
    shape: Shape = WhiteNoiseListItemDefaults.segmentedShapes(0, 1).shape,
    horizontalInset: Dp = 0.dp,
    registrationToken: Any? = null,
    strokeColor: Color = if (isAmoledSurfaceTheme()) MaterialTheme.colorScheme.outline else Color.Unspecified,
): Pair<Modifier, () -> Boolean> {
    val morph = LocalSettingsCardMorph.current
    val page = morph?.page
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val outline = strokeColor
    val token = registrationToken ?: remember(page, destination, surface) { Any() }
    val viewport = LocalSettingsNativeViewport.current
    val identity = LocalSettingsSearchItemKey.current to surface
    val width = with(density) { ceil(1.dp.toPx()) }
    DisposableEffect(page, token) { onDispose { page?.removeDestination(token) } }
    if (page == null) return Modifier to { false }
    val modifier =
        Modifier.onGloballyPositioned { coordinates ->
            // boundsInWindow clips tall content; layout size and local origin retain the true perimeter.
            val clipped = coordinates.settingsMorphBounds(view)
            val local = coordinates.localToWindow(Offset.Zero)
            val windowBounds = coordinates.boundsInWindow()
            val offset = clipped.topLeft - windowBounds.topLeft
            val inset = with(density) { horizontalInset.toPx() }
            val measured =
                Rect(
                    local + offset,
                    androidx.compose.ui.geometry
                        .Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                )
            val rect = Rect(measured.left + inset, measured.top, measured.right - inset, measured.bottom)
            val corners =
                when (val native = shape.createOutline(rect.size, direction, density)) {
                    is Outline.Rounded -> native.roundRect
                    else -> RoundRect(Rect(Offset.Zero, rect.size))
                }
            page.reportDestination(
                token,
                SettingsRouteEndpoint(
                    identity,
                    destination ?: token,
                    rect,
                    corners,
                    fill,
                    outline,
                    true,
                    true,
                    visibleBounds = rect.intersect(clipped),
                    strokeWidth = width,
                    viewportToken = viewport,
                ),
            )
        }
    return modifier to { false }
}

/** Row reports belong to the existing group instance and carry only native stroke geometry. */
@Composable
internal fun rememberSettingsNativeRow(
    group: Any,
    key: String,
    context: SettingsRowContext,
): Modifier {
    val page = LocalSettingsCardMorph.current?.page ?: return Modifier
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val token = remember(page, group, key) { Any() }
    DisposableEffect(page, group, token) { onDispose { page.removeRow(group, token) } }
    return Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.fullSettingsMorphBounds(view)
        val shape = context.shapes.shape
        val corners =
            when (val outline = shape.createOutline(bounds.size, direction, density)) {
                is Outline.Rounded -> outline.roundRect
                else -> RoundRect(Rect(Offset.Zero, bounds.size))
            }
        page.reportRow(
            group,
            token,
            SettingsRouteEndpoint(
                token,
                token,
                bounds,
                corners,
                context.containerColor,
                context.borderColor,
                (shape as? ConnectedRowShape)?.first ?: true,
                (shape as? ConnectedRowShape)?.last ?: true,
                if (context.borderColor.isSpecified) amoledRowSeamColor(context.borderColor) else context.borderColor,
                strokeWidth = with(density) { ceil(1.dp.toPx()) },
            ),
        )
    }
}

/** Layout origin and size retain a partially clipped row's actual bottom and seam. */
private fun LayoutCoordinates.fullSettingsMorphBounds(view: View): Rect {
    val clipped = settingsMorphBounds(view)
    val offset = clipped.topLeft - boundsInWindow().topLeft
    return Rect(
        localToWindow(Offset.Zero) + offset,
        androidx.compose.ui.geometry
            .Size(size.width.toFloat(), size.height.toFloat()),
    )
}
