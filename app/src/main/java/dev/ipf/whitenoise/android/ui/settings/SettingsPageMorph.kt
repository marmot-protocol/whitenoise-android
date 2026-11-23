package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard
import dev.ipf.whitenoise.android.ui.common.visibleIn

/** One route clock owns continuous opaque chrome and the separately permitted content picture. */
@Suppress("TooManyFunctions")
internal class SettingsPageMorph {
    var pendingOrigin: Rect? = null
    var pendingEndpoint: SettingsRouteEndpoint? = null
    internal val destinations = mutableMapOf<Any, SettingsRouteEndpoint>()
    internal val rows = mutableMapOf<Any, MutableMap<Any, SettingsRouteEndpoint>>()
    internal val viewports = mutableMapOf<Any, Rect>()

    private val returnTargets = mutableMapOf<Any?, ReturnTarget>()
    internal val routeGroupKeys = mutableMapOf<Any?, MutableMap<Any, Int>>()
    internal var nextGroupKey = 1

    // Anchor-only routes retain their native identity independently from destination group identity.
    private val anchorGroupKeys = mutableMapOf<Any?, Int>()
    private var correspondenceKey: Int? = null
    private var targetAnchorKey: Int? = null
    internal var sourceContours: List<SettingsContour> = emptyList()
    private var targetContours: List<SettingsContour>? = null
    private var correspondence: List<SettingsContourPair>? = null
    private var externalSource: SettingsContour? = null
    private var externalPairs: List<SettingsContourPair>? = null
    private var bodyMask = Path()
    private var bodyCoverage = Path()
    private var unoutlinedBodyMask = Path()
    private var bodyReports: NativeFrame? = null
    private var rebaseTime = 0f
    private var transitionHost = Rect.Zero
    internal var target: Any? = null
    private var outgoingTarget: Any? = null
    private var origin: Rect? = null

    /** The live outline of the control an anchor-only route, such as a chat, opens from. */
    internal var anchorContour: (() -> SettingsContour?)? = null
    private var closingOrigin: Rect? = null
    internal var closingAnchorContour: (() -> SettingsContour?)? = null
    private var exitStart = 1f
    private var entryStart = 0f
    private var entryOnly = false
    internal var captureInputs: (() -> Any?)? = null
    internal var childContours: (() -> List<SettingsContour>?)? = null
    internal var childBodyTop: (() -> Float)? = null

    /** The cards the mounted child route measured, which a top-level bubble may land on. */
    internal var childCards: (() -> List<SurfaceMorphCard>)? by mutableStateOf(null)
    private val bubbleCache = SettingsBubbleCardCache()

    internal fun bubbleCards(): List<SurfaceMorphCard> = childCards?.invoke()?.takeIf { it.isNotEmpty() } ?: bubbleCache.cards(destinations.values)

    internal val bodyTop: Float
        get() =
            childBodyTop?.invoke()
                ?: (viewports.values.minOfOrNull { it.top } ?: destinations.values.minOfOrNull { it.bounds.top } ?: 0f)

    private var nativeFrame: NativeFrame? = null
    private var settled: PresentedFrame? = null
    private var leaving: PresentedFrame? = null
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
        if (destinations[token] == endpoint) return
        destinations[token] = endpoint
        bubbleCache.invalidate()
        nativeFrame = null
    }

    fun removeDestination(token: Any) {
        val destination = destinations.remove(token)
        if (destination != null) bubbleCache.invalidate()
        val groupRows = rows.remove(token)
        if (destination != null || groupRows != null) nativeFrame = null
    }

    fun reportRow(
        group: Any,
        token: Any,
        endpoint: SettingsRouteEndpoint,
    ) {
        val groupRows = rows.getOrPut(group) { mutableMapOf() }
        if (groupRows[token] == endpoint) return
        groupRows[token] = endpoint
        nativeFrame = null
    }

    fun removeRow(
        group: Any,
        token: Any,
    ) {
        if (rows[group]?.remove(token) != null) nativeFrame = null
    }

    fun reportViewport(
        token: Any,
        bounds: Rect,
    ) {
        if (viewports[token] == bounds) return
        viewports[token] = bounds
        nativeFrame = null
    }

    fun removeViewport(token: Any) {
        if (viewports.remove(token) != null) nativeFrame = null
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
        targetContours = closingTarget(native)
        updateCorrespondence()
        return true
    }

    /** Closing to a route without groups folds into the outline it opened from, when that is on screen. */
    private fun closingTarget(native: List<SettingsContour>?): List<SettingsContour>? =
        if (opening || native?.isEmpty() != true) {
            native
        } else {
            val key = targetAnchorKey ?: correspondenceKey ?: 0
            val anchor = (closingAnchorContour ?: anchorContour)?.invoke()?.takeIf { it.bounds.validIn(transitionHost) }
            val measured = closingOrigin?.takeIf { it.validIn(transitionHost) }
            val contour =
                anchor?.copy(key = key, viewport = transitionHost)
                    ?: measured?.let { settingsOriginContour(it, transitionHost, key) }
            contour?.let(::listOf) ?: native
        }

    /** The activating control's bounds for [next], as [begin] will resolve it; null without a measured click. */
    internal fun pendingOriginFor(next: Any?): Rect? =
        if (pendingEndpoint != null) pendingEndpoint?.takeIf { it.destination == next }?.bounds else pendingOrigin

    internal fun outgoingPresence(time: Float): Float {
        val hasOutgoingFrame = active && !entryOnly && exitStart > 0f
        return if (hasOutgoingFrame && geometryFraction(time) < CONTENT_HANDOFF_FRACTION) 1f else 0f
    }

    internal fun incomingPresence(time: Float): Float {
        if (!active || entryOnly || entryStart > 0f) return 1f
        return if (geometryFraction(time) >= CONTENT_HANDOFF_FRACTION) 1f else 0f
    }

    private fun geometryFraction(time: Float): Float {
        val easing = if (opening) AppMotionOpenEasing else AppMotionCloseEasing
        return easing.transform(time.coerceIn(0f, 1f))
    }

    private fun updateCorrespondence() {
        correspondence =
            targetContours?.let {
                matchSettingsContours(
                    sourceContours,
                    it,
                    correspondenceKey,
                    if (opening) target else null,
                    origin?.center ?: transitionHost.center,
                    transitionHost,
                    targetAnchorKey,
                )
            }
        if (opening) {
            val saved = returnTargets[target]
            val child = correspondence?.firstOrNull { it.source.key == correspondenceKey }?.target?.key
            if (saved != null && child != null) returnTargets[target] = saved.copy(childGroupKey = child)
        }
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
            correspondence,
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
        if (changedHost || (settled ?: leaving)?.inputs != captureInputs?.invoke()) clearPictures()
        val oldExit = outgoingPresence(currentTime)
        val oldEntry = incomingPresence(currentTime)
        val interrupted = active && currentTime < 1f && host == transitionHost
        // A cached picture and its geometry always come from the same presented draw.
        // Changed capture inputs discard the whole frame and fall back to native geometry without replay.
        val frame = settled?.takeIf { it.host == host }
        val current = if (interrupted) renderedContours(currentTime) else frame?.contours ?: nativeContours(host)
        val returnsToVisibleParent = interrupted && oldExit > 0f && next == outgoingTarget
        val retainsOutgoing = interrupted && oldExit > 0f && !returnsToVisibleParent
        // Active incoming pixels are never recorded. Without a coherent outgoing body, reveal
        // the live destination immediately instead of restarting an empty-body phase. The outline
        // still freezes its presented geometry and completes the replacement route's own clock.
        val revealInterruptedBody =
            interrupted &&
                oldEntry > 0f &&
                frame == null &&
                !returnsToVisibleParent &&
                !(retainsOutgoing && leaving != null)
        val selected =
            if (pendingEndpoint != null) {
                pendingEndpoint?.takeIf { it.destination == next }?.bounds
            } else {
                pendingOrigin
            }
        val visibleSelection = pendingEndpoint?.takeIf { it.destination == next }?.visibleBounds ?: selected
        val returnTarget = returnTargets[target]
        val returnPoint = returnTarget?.point
        closingOrigin = returnTarget?.unoutlinedOrigin.takeUnless { forward }
        // Resolve the activating row against native groups before freezing or clearing their reports.
        val activatedGroup =
            selected?.let { bounds ->
                (frame?.contours ?: nativeContours(host))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { it[nearestSettingsContour(it, bounds.center)].key }
            }
        correspondenceKey =
            if (forward) {
                activatedGroup?.let { key ->
                    if (interrupted) correspondence?.firstOrNull { it.target.key == key }?.key ?: key else key
                } ?: correspondenceKey
            } else {
                returnTarget?.childGroupKey?.takeIf { key -> current.orEmpty().any { it.key == key } }
                    ?: returnTarget?.groupKey ?: correspondenceKey
            }
        targetAnchorKey = if (forward) null else returnTarget?.groupKey
        val eligible =
            animate &&
                !changedHost &&
                !host.isEmpty &&
                current != null &&
                (visibleSelection?.visibleIn(host) == true || (!forward && returnPoint != null) || interrupted)
        // An anchor-only route contributes its one real outline; it pairs with one destination group.
        val anchorSource =
            anchorContour
                ?.takeIf { forward && eligible && current?.isEmpty() == true }
                ?.invoke()
                ?.takeIf { it.bounds.validIn(host) }
                ?.copy(key = anchorGroupKeys.getOrPut(next) { nextGroupKey++ }, viewport = host)
        // Without an outline to leave from, the measured origin itself is the one source contour,
        // so a single group grows out of it instead of every group growing from a point.
        val outlineLessOrigin = anchorSource == null && forward && eligible && current?.isEmpty() == true
        val originSource =
            selected
                ?.takeIf { outlineLessOrigin && visibleSelection?.visibleIn(host) == true }
                ?.let { settingsOriginContour(it, host, anchorGroupKeys.getOrPut(next) { nextGroupKey++ }) }
        val emptySource = anchorSource ?: originSource
        if (emptySource != null) correspondenceKey = emptySource.key
        if (forward) {
            if (eligible &&
                selected != null &&
                visibleSelection?.visibleIn(host) == true
            ) {
                returnTargets[next] =
                    ReturnTarget(selected.center, correspondenceKey, unoutlinedOrigin = originSource?.bounds)
            } else {
                returnTargets.remove(next)
            }
        } else {
            returnTargets.remove(target)
        }
        if (!retainsOutgoing) {
            // Bind the cached mask to this frozen picture; raster construction belongs to draw.
            leavingMask = null
            leaving = frame
            outgoingTarget = target
        }
        sourceContours = emptySource?.let(::listOf) ?: current.orEmpty()
        targetContours = null
        correspondence = null
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
        entryStart =
            if (revealInterruptedBody) {
                1f
            } else if (returnsToVisibleParent) {
                oldExit
            } else {
                0f
            }
        entryOnly = returnsToVisibleParent || (leaving == null && current.isNullOrEmpty())
        if (returnsToVisibleParent) leaving = null
        val anchor = selected ?: returnPoint?.let { Rect(it, it) } ?: host
        origin = anchor.takeIf { eligible }
        transitionHost = host
        target = next
        opening = forward
        destinations.clear()
        bubbleCache.invalidate()
        rows.clear()
        nativeFrame = null
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

    private fun ownNativeContours(host: Rect): List<SettingsContour>? {
        nativeFrame?.takeIf { it.host == host }?.let { return it.contours }
        return buildNativeContours(host).also { nativeFrame = NativeFrame(host, it) }
    }

    fun finish() {
        nativeFrame = null
        origin = null
        leaving = null
        sourceContours = emptyList()
        targetContours = null
        correspondence = null
        leavingMask = null
    }

    fun cancel() {
        finish()
        clearPictures()
        returnTargets.clear()
        routeGroupKeys.clear()
        anchorGroupKeys.clear()
        correspondenceKey = null
    }

    fun clearPictures() {
        settled = null
        leaving = null
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

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    fun ContentDrawScope.draw(
        host: Rect,
        time: Float,
        recording: Boolean,
        backplate: Color,
        captureContent: Boolean = true,
    ) {
        val inputs = captureInputs?.invoke()
        if (!recording) clearPictures()
        if (!captureContent || settled?.inputs != inputs) settled = null
        val native = nativeContours(host)
        if (native == null) {
            finish()
            clearPictures()
        }
        if (bounds(host, time) == null || time >= 1f) {
            leaving = null
            drawSettledFrame(native, host, recording && captureContent, inputs)
            return
        }
        updateNativeFrame(native, host, time)
        drawRect(backplate)
        val contours = renderedContours(time)
        val interiors = if (contours.isEmpty()) leaving?.body ?: bodyMask else movingBodyMask(contours, host)
        val borderlessEntry = native?.isEmpty() == true && leaving == null
        val showNativeBody = incomingPresence(time) > 0f || borderlessEntry
        val showUnoutlinedBody = unoutlinedBodyIsVisible(time, borderlessEntry)
        contours.forEach { drawContourFill(it, host) }
        // One live display-list invocation: the fixed header and, after handoff, clipped body.
        val live =
            Rect(Offset.Zero, size).nativeLiveBodyMask(
                bodyCoverage,
                bodyMask,
                interiors,
                unoutlinedBodyMask.takeIf { showUnoutlinedBody },
                showNativeBody,
            )
        val enteringFromOrigin = entryOnly || entryStart > 0f
        val bodyAlpha =
            if (!showNativeBody || borderlessEntry || enteringFromOrigin) {
                1f
            } else {
                settingsPageHandoffAlpha(opening, time, true)
            }
        drawNativeLiveBody(live, mask, bodyAlpha) {
            Rect(Offset.Zero, size).nativeLiveBodyMask(bodyCoverage, bodyMask, interiors, null, false)
        }
        clipPath(interiors) { this@draw.drawOutgoingInk(host, time) }
        contours.forEach { drawContinuousContour(it, host) }
    }

    private fun unoutlinedBodyIsVisible(
        time: Float,
        borderlessEntry: Boolean,
    ): Boolean = entryStart > 0f || geometryFraction(time) >= CONTENT_HANDOFF_FRACTION || borderlessEntry

    private fun ContentDrawScope.drawSettledFrame(
        native: List<SettingsContour>?,
        host: Rect,
        capture: Boolean,
        inputs: Any?,
    ) {
        if (capture) {
            val picture = settled?.picture ?: Picture()
            drawAndRecord(picture)
            settled =
                PresentedFrame(
                    picture,
                    native.orEmpty(),
                    activeNativeBody(native.orEmpty(), host),
                    bodyTop,
                    host,
                    inputs,
                )
        } else {
            drawContent()
        }
    }

    /** Cross-window presentation uses the same native contour batch and exclusive stroke mask. */
    fun ContentDrawScope.drawExternal(
        source: SettingsContour,
        host: Rect,
        presence: Float,
        backplate: Color,
    ) {
        val native = nativeContours(host)
        if (native.isNullOrEmpty() || presence >= 1f) {
            drawContent()
            return
        }
        drawRect(backplate)
        if (maskFrame != native || maskHost != host || externalSource != source) {
            maskFrame = native
            maskHost = host
            mask = nativeChromeMask(native, host)
            bodyMask = nativeBodyMask(native, host)
            bodyCoverage = measuredBodyCoverage(native, host)
            unoutlinedBodyMask = Path().apply { op(bodyCoverage, bodyMask, PathOperation.Difference) }
            externalSource = source
            externalPairs =
                matchSettingsContours(
                    listOf(source),
                    native,
                    source.key,
                    source.destination,
                    source.bounds.center,
                    host,
                )
        }
        val contours =
            interpolateSettingsContours(
                listOf(source),
                native,
                presence,
                source.bounds.center,
                host,
                source.key,
                correspondence = externalPairs,
            )
        contours.forEach { drawContourFill(it, host) }
        val interiors = movingBodyMask(contours, host)
        val live =
            Rect(Offset.Zero, size).nativeLiveBodyMask(
                bodyCoverage,
                bodyMask,
                interiors,
                unoutlinedBodyMask,
                presence >= CONTENT_HANDOFF_FRACTION,
            )
        clipPath(live) {
            clipPath(mask, ClipOp.Difference) { this@drawExternal.drawContent() }
        }
        contours.forEach { drawContinuousContour(it, host) }
    }

    /** Frozen ink stays at native coordinates and leaves only through the moving interior clip. */
    private fun ContentDrawScope.drawOutgoingInk(
        host: Rect,
        time: Float,
    ) {
        val frame = leaving ?: return
        if (outgoingPresence(time) <= 0f) return
        val alpha = settingsPageHandoffAlpha(opening, time, false)
        val chrome = leavingMask ?: nativeChromeMask(frame.contours, host).also { leavingMask = it }
        drawFrozenSettingsBody(frame, chrome, alpha, (maxOf(frame.bodyTop, bodyTop) - host.top).coerceAtLeast(0f))
    }

    /** Commit one measured native batch to this owner before painting its body and chrome. */
    private fun updateNativeFrame(
        native: List<SettingsContour>?,
        host: Rect,
        time: Float,
    ) {
        val end = closingTarget(native)
        if (targetContours != null && targetContours != end) {
            // All layout callbacks precede drawing; one coherent measured batch updates the target.
            sourceContours = renderedContours(time)
            nativeFrame = null
            rebaseTime = time.coerceIn(0f, 1f)
            targetContours = end
            updateCorrespondence()
        }
        // Native masks change only with measured batches; body ink stays at native coordinates.
        // Headers and ink outside measured body viewports remain live throughout.
        val chrome = if (!opening && native?.isEmpty() == true) end else native
        if (maskFrame != chrome || maskHost != host || bodyReports !== nativeFrame) {
            bodyReports = nativeFrame
            maskHost = host
            maskFrame = chrome
            mask = nativeChromeMask(chrome.orEmpty(), host)
            bodyMask = nativeBodyMask(native.orEmpty(), host)
            bodyCoverage = measuredBodyCoverage(native.orEmpty(), host)
            unoutlinedBodyMask =
                Path().apply {
                    op(bodyCoverage, nativeBodyMask(native.orEmpty(), host), PathOperation.Difference)
                }
            externalSource = null
        }
    }
}
