// One row owns reaction intent, endpoint measurement and draw handoff.
@file:Suppress("FunctionNaming", "TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerCellTextMetrics
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.rememberEmojiArtPainter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val LOCAL_REACTION_MOTION_MILLIS = 220
private const val REACTION_TRAVEL_FRACTION = 0.8f
internal val LocalRowReactionMotion = compositionLocalOf<LocalReactionMotion?> { null }

internal data class LocalReactionEffect(
    val sequence: Long,
    val emoji: String,
    val origin: Rect?,
)

internal data class ReactionMotionTarget(
    val bounds: Rect,
    val layout: TextLayoutResult? = null,
    val chrome: ReactionMotionChrome? = null,
) {
    val glyph: Boolean get() = layout != null
}

/** One active native pill recording; the existing effect owns its lifetime. */
internal class ReactionMotionChrome(
    val sequence: Long,
    val layer: GraphicsLayer,
) {
    var bounds: Rect? = null
    var recorded: Boolean = false
}

private const val REACTION_CHROME_EMERGENCE_START = 0.25f
private const val REACTION_CHROME_EMERGENCE_DURATION = 0.25f

/** The native outline forms around the moving glyph during the middle of the existing phase. */
internal fun reactionChromeAlpha(progress: Float): Float {
    val emergence = (progress - REACTION_CHROME_EMERGENCE_START) / REACTION_CHROME_EMERGENCE_DURATION
    return FastOutSlowInEasing.transform(emergence.coerceIn(0f, 1f))
}

/** Identical native pixels switch copies atomically; source-over crossfading would dim the glyph. */
internal fun reactionGlyphHandoff(progress: Float): Float = if (progress >= 1f) 1f else 0f

/** Overflow has a numeric label, so the arriving emoji fades instead of replacing a matching glyph. */
internal fun reactionOverflowAlpha(progress: Float): Float {
    val handoff = (1f - progress) / (1f - REACTION_TRAVEL_FRACTION)
    return handoff.coerceIn(0f, 1f)
}

/** Travel decelerates into the exact destination, then stays there through the final handoff. */
internal fun reactionFlightProgress(progress: Float): Float {
    val travel = (progress / REACTION_TRAVEL_FRACTION).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(travel)
}

/** Matches Image's Fit behavior without stretching decoded rectangular artwork. */
internal fun reactionArtworkFit(
    intrinsic: Size,
    container: Size,
): Size =
    if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
        val scale = ContentScale.Fit.computeScaleFactor(intrinsic, container)
        Size(intrinsic.width * scale.scaleX, intrinsic.height * scale.scaleY)
    } else {
        container
    }

/** Image centers the fitted float drawing size using rounded integer alignment. */
internal fun reactionArtworkFrame(
    container: Rect,
    intrinsic: Size,
    direction: LayoutDirection,
): Rect {
    val fit = reactionArtworkFit(intrinsic, container.size)
    val offset =
        Alignment.Center.align(
            IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            IntSize(container.width.roundToInt(), container.height.roundToInt()),
            direction,
        )
    return Rect(container.topLeft + Offset(offset.x.toFloat(), offset.y.toFloat()), fit)
}

/** Inline children use floored constraints and rounded placement within the actual text layout. */
private fun ReactionMotionTarget.artworkContainer(): Rect? {
    val placeholder = layout?.placeholderRects?.singleOrNull() ?: return null
    return Rect(
        bounds.topLeft + Offset(placeholder.left.roundToInt().toFloat(), placeholder.top.roundToInt().toFloat()),
        Size(floor(placeholder.width), floor(placeholder.height)),
    )
}

/** One mounted row's decorative local intent. Counts and mutation state remain in the controller. */
@Stable
internal class LocalReactionMotion {
    var effect: LocalReactionEffect? by mutableStateOf(null)
        private set
    var progress by mutableStateOf(Animatable(1f))
        private set
    val targets = mutableStateMapOf<String, ReactionMotionTarget>()
    var viewport: Rect? by mutableStateOf(null)
    var readViewport: (() -> Rect?)? = null
    var origin: Offset by mutableStateOf(Offset.Zero)
    var enabled: Boolean by mutableStateOf(true)
    private var mounted = true
    private var sequence = 0L
    private var choice: Pair<String, Rect>? = null

    /** Remembers the tapped picker cell's screen bounds for [emoji], or clears the choice if retired or unmeasured. */
    fun choose(
        emoji: String,
        bounds: Rect,
    ) {
        choice = if (mounted && enabled && !bounds.isEmpty) emoji to bounds else null
    }

    /** Consumes the remembered origin, returning it only if it was chosen for [emoji]. */
    fun takeOrigin(emoji: String): Rect? = choice?.takeIf { it.first == emoji }?.second.also { choice = null }

    /** Both endpoints must lie wholly inside the current reading viewport, an unknown viewport allows flight. */
    fun canFly(
        source: Rect,
        target: Rect,
    ): Boolean =
        (readViewport?.invoke() ?: viewport)?.let { bounds ->
            listOf(source, target).all {
                val horizontal = it.left >= bounds.left && it.right <= bounds.right
                val vertical = it.top >= bounds.top && it.bottom <= bounds.bottom
                horizontal && vertical
            }
        } ?: true

    /**
     * Starts an effect only for an accepted local add. The fresh animator starts at the source immediately,
     * so a target composed before the animation coroutine starts cannot flash its settled glyph.
     */
    fun accept(
        emoji: String,
        added: Boolean,
        source: Rect? = null,
    ) {
        val validAdd = added && emoji.length <= MAX_REACTION_GLYPH_LENGTH
        effect =
            if (mounted && enabled && validAdd) {
                progress = Animatable(0f)
                LocalReactionEffect(++sequence, emoji, source?.takeIf { !it.isEmpty })
            } else {
                null
            }
    }

    /** Attaches only the current effect's actually drawn native chrome to its measured glyph. */
    fun attachChrome(
        emoji: String,
        expected: LocalReactionEffect,
        chrome: ReactionMotionChrome,
    ) {
        if (!mounted || !enabled || effect != expected) return
        if (!chrome.recorded || chrome.bounds == null) return
        val target = targets[emoji] ?: return
        if (target.glyph && target.chrome !== chrome) targets[emoji] = target.copy(chrome = chrome)
    }

    /** A disposed or ineligible surface cannot leave a released recording in the active target. */
    fun detachChrome(
        emoji: String,
        chrome: ReactionMotionChrome,
    ) {
        val target = targets[emoji] ?: return
        if (target.chrome === chrome) targets[emoji] = target.copy(chrome = null)
    }

    /** Ends [expected] only if it is still current, so a late completion never clears a newer effect. */
    fun settle(expected: LocalReactionEffect? = effect) {
        if (effect == expected) effect = null
        choice = null
    }

    /** Retires the owner for good: later choices and accepts are ignored and measured targets are dropped. */
    fun dispose() {
        mounted = false
        readViewport = null
        settle()
        targets.clear()
    }
}

/**
 * Reuses the enclosing row's motion when one is provided, otherwise remembers one per [owner] and motion eligibility.
 * Each new effect animates progress from 0 to 1 once and then settles, disabled motion snaps it settled.
 */
@Composable
internal fun rememberLocalReactionMotion(
    owner: Any?,
    enabled: Boolean? = null,
): LocalReactionMotion {
    LocalRowReactionMotion.current?.let { return it }
    val motionEnabled = enabled ?: rememberConversationMotionEnabled()
    // A suppressed owner is retired, so a delayed callback cannot revive it after re-enabling motion.
    val state = remember(owner, motionEnabled) { LocalReactionMotion() }
    state.enabled = motionEnabled
    DisposableEffect(state) { onDispose { state.dispose() } }
    val effect = state.effect
    LaunchedEffect(state, effect, motionEnabled) {
        if (!motionEnabled || effect == null) {
            state.settle()
            state.progress.snapTo(1f)
        } else {
            // Optimistic state normally lays out the chip on the next frame. Do not spend the flight
            // before that real endpoint exists; late geometry must not start a partial flight.
            if (effect.origin != null) {
                val target =
                    withTimeoutOrNull(100) {
                        snapshotFlow { state.targets[effect.emoji] }.filterNotNull().first()
                    }
                if (target == null) {
                    state.settle(effect)
                    return@LaunchedEffect
                }
            }
            state.progress.animateTo(1f, tween(LOCAL_REACTION_MOTION_MILLIS, easing = LinearEasing))
            state.settle(effect)
        }
    }
    return state
}

/** Popup and transcript windows have different origins; both endpoints use screen coordinates. */
@Composable
internal fun Modifier.reactionSourceBounds(
    requireFullyVisible: Boolean = true,
    enabled: Boolean = true,
    onBounds: (Rect) -> Unit,
): Modifier {
    if (!enabled) return this
    val view = LocalView.current
    val shared = LocalReactionWindowCoordinates.current?.takeIf { it.view === view }
    val own = remember(view) { ReactionWindowCoordinates(view) }
    return onGloballyPositioned { coordinates ->
        val offset = shared?.offset ?: own.also { it.update() }.offset
        val full = coordinates.boundsInWindow(clipBounds = false)
        val visible = coordinates.boundsInWindow()
        onBounds(
            if (requireFullyVisible && full != visible) {
                Rect.Zero
            } else {
                full.translate(offset)
            },
        )
    }
}

/** No hit target or semantics are added. The canonical chip remains the only accessible reaction. */
@Composable
// One guarded draw pass preserves endpoints and native text/art rasterization.
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun Modifier.localReactionOverlay(state: LocalReactionMotion): Modifier {
    if (LocalRowReactionMotion.current === state) return this
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val effect = state.effect
    val density = LocalDensity.current
    val sourceCell = with(density) { (effect?.origin?.height ?: 28.dp.toPx()).toDp() }
    val metrics = emojiPickerCellTextMetrics(sourceCell, MaterialTheme.typography.headlineMedium, density.fontScale)
    val layout =
        effect?.let {
            measurer.measure(
                it.emoji,
                MaterialTheme.typography.headlineMedium.copy(
                    fontSize = metrics.first,
                    lineHeight = metrics.second,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    val art = effect?.let { EmojiShortcodes.art(it.emoji, LocalCustomEmoji.current, emptyMap()) }
    val painter = art?.let { rememberEmojiArtPainter(it) }
    val targetFontPx = with(density) { ReactionEmojiSize.toPx() }
    val direction = LocalLayoutDirection.current
    val sourceFontPx = with(density) { metrics.first.toPx() }
    val owner =
        if (effect?.origin != null) {
            semantics { if (state.hasVisibleFlight(effect)) testTag = "conversation.reaction.motion.active" }
        } else {
            this
        }
    return owner
        .reactionSourceBounds(requireFullyVisible = false, enabled = effect?.origin != null) {
            state.origin = it.topLeft
        }.drawWithContent {
            drawContent()
            val current = state.effect
            if (!state.enabled || current != effect) return@drawWithContent
            val source = current?.origin ?: return@drawWithContent
            val target = state.targets[current.emoji] ?: return@drawWithContent
            val glyph = layout ?: return@drawWithContent
            if (!state.canFly(source, target.bounds)) return@drawWithContent
            val p = state.progress.value
            val travel = reactionFlightProgress(p)
            val alpha = if (target.glyph) 1f - reactionGlyphHandoff(p) else reactionOverflowAlpha(p)
            val chrome =
                target.chrome?.takeIf {
                    it.recorded &&
                        it.sequence == current.sequence &&
                        it.bounds?.let { bounds -> state.canFly(source, bounds) } == true
                }
            chrome?.layer?.alpha = reactionChromeAlpha(p)
            if (painter != null) {
                val destination =
                    target.artworkContainer() ?: Rect(
                        target.bounds.center - Offset(targetFontPx / 2f, targetFontPx / 2f),
                        Size(targetFontPx, targetFontPx),
                    )
                val sourceFrame = reactionArtworkFrame(source, painter.intrinsicSize, direction)
                val targetFrame = reactionArtworkFrame(destination, painter.intrinsicSize, direction)
                val frame = lerp(sourceFrame, targetFrame, travel).translate(-state.origin)
                translate(frame.left, frame.top) {
                    if (chrome != null) {
                        val bounds = requireNotNull(chrome.bounds)
                        val chromeScale = frame.width / targetFrame.width.coerceAtLeast(1f)
                        scale(chromeScale, pivot = Offset.Zero) {
                            translate(bounds.left - targetFrame.left, bounds.top - targetFrame.top) {
                                drawLayer(chrome.layer)
                            }
                        }
                    }
                    with(painter) { draw(frame.size, alpha = alpha) }
                }
            } else {
                // Draw the destination's own native layout at landing. Scaling a separately
                // rasterized source font down can shift the baseline/advance by a pixel.
                val native = target.layout
                if (native != null) {
                    val placement =
                        Alignment.Center.align(
                            glyph.size,
                            IntSize(source.width.roundToInt(), source.height.roundToInt()),
                            direction,
                        )
                    val sourceText = source.topLeft + Offset(placement.x.toFloat(), placement.y.toFloat())
                    val startScale = sourceFontPx / targetFontPx
                    // Match the source Text's actual line origin and baseline, not the center
                    // of the differently-sized destination line box scaled up around its middle.
                    val start =
                        Offset(
                            sourceText.x + glyph.getLineLeft(0) - native.getLineLeft(0) * startScale,
                            sourceText.y + glyph.firstBaseline - native.firstBaseline * startScale,
                        )
                    val origin = start * (1f - travel) + target.bounds.topLeft * travel - state.origin
                    val glyphScale = startScale + (1f - startScale) * travel
                    translate(origin.x, origin.y) {
                        scale(glyphScale, pivot = Offset.Zero) {
                            if (chrome != null) {
                                val bounds = requireNotNull(chrome.bounds)
                                translate(bounds.left - target.bounds.left, bounds.top - target.bounds.top) {
                                    drawLayer(chrome.layer)
                                }
                            }
                            drawText(native, topLeft = Offset.Zero, alpha = alpha)
                        }
                    }
                } else {
                    val center = source.center * (1f - travel) + target.bounds.center * travel - state.origin
                    val glyphScale = 1f + (targetFontPx / sourceFontPx.coerceAtLeast(1f) - 1f) * travel
                    scale(glyphScale, pivot = center) {
                        drawText(
                            glyph,
                            topLeft = center - Offset(glyph.size.width / 2f, glyph.size.height / 2f),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
}

/**
 * Records native chrome without moving layout or pointer/semantics coordinates.
 * One native draw boundary owns capture, visibility fallback and disposal together.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun Modifier.localReactionChrome(
    state: LocalReactionMotion?,
    emoji: String?,
    newSelectedPill: Boolean,
): Modifier {
    val effect = state?.effect
    if (state == null || emoji == null || !newSelectedPill) return this
    if (!state.enabled || effect?.emoji != emoji || effect.origin == null) return this
    val layer = rememberGraphicsLayer()
    val chrome = remember(state, effect.sequence, layer) { ReactionMotionChrome(effect.sequence, layer) }
    DisposableEffect(state, emoji, chrome) {
        onDispose {
            chrome.bounds = null
            chrome.recorded = false
            state.detachChrome(emoji, chrome)
        }
    }
    return reactionSourceBounds(requireFullyVisible = false) { chrome.bounds = it }.drawWithContent {
        val source = effect.origin
        val target = state.targets[emoji]
        val bounds = chrome.bounds
        val current = state.enabled && state.effect == effect
        val sourceVisible = state.canFly(source, source)
        val targetVisible = target?.let { it.glyph && state.canFly(source, it.bounds) } == true
        val chromeVisible = bounds?.let { state.canFly(source, it) } == true
        val geometryVisible = sourceVisible && targetVisible && chromeVisible
        if (current && geometryVisible) {
            // The glyph already has its own flight alpha. Keep measuring/recording the real
            // surface, but do not park a second outline at the destination while it travels.
            layer.record { this@drawWithContent.drawContent() }
            chrome.recorded = true
            state.attachChrome(emoji, effect, chrome)
        } else {
            state.detachChrome(emoji, chrome)
            drawContent()
        }
    }
}

/** Measures a fallback chip and supplies feedback when no source glyph can fly. */
@Composable
internal fun Modifier.localReactionTarget(
    state: LocalReactionMotion?,
    emoji: String?,
    selected: Boolean,
    allowHostClipping: Boolean = false,
    measureChip: Boolean = true,
): Modifier {
    if (state == null || emoji == null) return this
    val measured = if (measureChip) measuredReactionTarget(state, emoji, selected, allowHostClipping) else this
    return measured.graphicsLayer {
        val effect = state.effect
        val active = state.enabled && selected && effect?.emoji == emoji
        val flying =
            effect?.origin?.let { source ->
                state.targets[emoji]?.let { it.glyph && state.canFly(source, it.bounds) }
            } == true
        val p = if (active && !flying) FastOutSlowInEasing.transform(state.progress.value) else 1f
        val scale = 1f + 0.12f * sin(PI.toFloat() * p)
        scaleX = scale
        scaleY = scale
    }
}

/** The actual emoji text, rather than its 48dp touch target, owns the flight endpoint and handoff. */
@Composable
internal fun Modifier.localReactionGlyphTarget(
    state: LocalReactionMotion?,
    emoji: String,
    selected: Boolean,
    allowHostClipping: Boolean,
    textLayout: () -> TextLayoutResult?,
): Modifier {
    if (state == null) return this
    return measuredReactionTarget(state, emoji, selected, allowHostClipping, textLayout).graphicsLayer {
        val effect = state.effect
        val target = state.targets[emoji]
        val flying =
            state.enabled &&
                selected &&
                effect?.emoji == emoji &&
                effect.origin?.let { source -> target?.let { state.canFly(source, it.bounds) } } == true
        alpha = if (flying) reactionGlyphHandoff(state.progress.value) else 1f
    }
}

/** Active targets use the same screen coordinates as the popup source, including RTL placement. */
@Composable
private fun Modifier.measuredReactionTarget(
    state: LocalReactionMotion,
    emoji: String,
    selected: Boolean,
    allowHostClipping: Boolean,
    textLayout: (() -> TextLayoutResult?)? = null,
): Modifier {
    val measuring = selected && state.enabled && state.effect?.emoji == emoji
    DisposableEffect(state, emoji, measuring) { onDispose { state.targets.remove(emoji) } }
    if (!measuring) return this
    var screenBounds by remember(state, emoji) { mutableStateOf<Rect?>(null) }
    val layout = textLayout?.invoke()

    fun publish(
        bounds: Rect?,
        nativeLayout: TextLayoutResult?,
    ) {
        val current = selected && state.enabled && state.effect?.emoji == emoji
        val complete = textLayout == null || nativeLayout != null
        val currentLayout = current && complete
        if (currentLayout && bounds != null && !bounds.isEmpty) {
            val chrome = state.targets[emoji]?.chrome?.takeIf { it.sequence == state.effect?.sequence }
            state.targets[emoji] = ReactionMotionTarget(bounds, nativeLayout, chrome)
        } else {
            state.targets.remove(emoji)
        }
    }
    // Layout results may change without moving the Text, and window bounds may
    // change without re-laying out its glyphs. Either callback republishes one target.
    SideEffect { publish(screenBounds, layout) }
    return reactionSourceBounds(requireFullyVisible = !allowHostClipping) {
        screenBounds = it
        publish(it, textLayout?.invoke())
    }
}

private const val MAX_REACTION_GLYPH_LENGTH = 32

/** Raise the whole lazy item, so a flying glyph cannot disappear behind a later-painted message. */
@Composable
internal fun ReactionMotionRow(
    state: LocalReactionMotion,
    modifier: Modifier = Modifier,
    viewportBoundsInWindow: () -> Rect? = { null },
    content: @Composable ColumnScope.() -> Unit,
) {
    val shared = LocalReactionWindowCoordinates.current
    state.readViewport = { viewportBoundsInWindow()?.translate(shared?.offset ?: Offset.Zero) }
    val row = modifier.fillMaxWidth().localReactionOverlay(state).zIndex(if (state.effect != null) 1f else 0f)
    CompositionLocalProvider(LocalRowReactionMotion provides state) {
        Column(row, content = content)
    }
}

/** The direct owner tag follows the same endpoint eligibility as its production draw pass. */
private fun LocalReactionMotion.hasVisibleFlight(effect: LocalReactionEffect?): Boolean {
    val source = effect?.origin ?: return false
    return enabled && targets[effect.emoji]?.let { canFly(source, it.bounds) } == true
}

/** One screen/window conversion shared by transcript endpoints; popup windows keep their own origin. */
@Stable
internal class ReactionWindowCoordinates(
    val view: View,
) {
    private val screen = IntArray(2)
    private val window = IntArray(2)
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Re-reads the window's on-screen position from the host view, the offset that maps window bounds to screen. */
    fun update() {
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        offset = Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }
}

internal val LocalReactionWindowCoordinates = compositionLocalOf<ReactionWindowCoordinates?> { null }

/** One window-to-screen converter for the current host view, which the caller provides and refreshes on layout. */
@Composable
internal fun rememberReactionWindowCoordinates(): ReactionWindowCoordinates {
    val view = LocalView.current
    return remember(view) { ReactionWindowCoordinates(view) }
}
