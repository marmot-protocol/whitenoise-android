package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

internal const val SETTINGS_CARD_MORPH_MILLIS = 320

/** Emphasized decelerate, shared with the top-level Settings card. */
internal val SettingsCardMorphEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The new page's text fades in over this window of morph time. The bubbles move alone first, so text
 * never appears outside a bubble that is still travelling, and the page is opaque before they settle.
 */
private const val SETTINGS_CARD_FADE_START = 0.25f
private const val SETTINGS_CARD_FADE_END = 0.85f

private val SettingsCardMorphCorner = 20.dp

/**
 * Geometry-only container transform between two Settings routes.
 *
 * Cards on the visible route report their window bounds. When the route changes, the outgoing
 * cards' bounds (never their pixels) are kept: behind the new page, bubble N travels from old
 * card N to new card N, counted top to bottom among on-screen cards, while the whole new page
 * fades in once over them after they start moving. Old cards without a partner fade where they were; new cards without
 * one arrive with the page. The old route is still disposed immediately, so no Back handler,
 * focus, editable state or protected content outlives it.
 */
internal class SettingsCardMorph {
    private val live = LinkedHashMap<Any, Rect>()
    private var host = Rect.Zero
    private var from: List<Rect> = emptyList()
    private var fan = false

    /**
     * The control a whole route grows out of, such as a chat header. With an anchor on screen and no
     * cards, that one shape fans out into every card of the next route; leaving those cards for a
     * route that shows the anchor, they all fold back into it.
     */
    var anchor: SettingsMorphAnchor? = null

    private val cards: List<Rect> get() = onScreen(live.filterKeys { it !is SettingsMorphAnchor }.values)

    private val anchorBounds: Rect? get() = anchor?.let(live::get)?.takeIf { host.isEmpty || it.overlaps(host) }

    /** Linear morph time from 0 to 1; read during draw so each frame follows the animation. */
    var time: () -> Float = { 1f }

    fun report(
        token: Any,
        bounds: Rect,
    ) {
        live[token] = bounds
    }

    fun remove(token: Any) {
        live.remove(token)
    }

    /**
     * Keeps the on-screen cards of the route being left as the next morph's starting shapes and
     * returns whether there is anything to morph from.
     */
    fun begin(animate: Boolean): Boolean {
        val leaving = cards
        val origin = anchorBounds
        fan = leaving.isEmpty() && origin != null
        from =
            when {
                !animate -> emptyList()
                origin != null && fan -> listOf(origin)
                else -> leaving
            }
        live.clear()
        return from.isNotEmpty()
    }

    fun finish() {
        from = emptyList()
    }

    private fun running(): Boolean = from.isNotEmpty() && time() < 1f

    fun pageAlpha(): Float {
        if (!running()) return 1f
        val fade = (time() - SETTINGS_CARD_FADE_START) / (SETTINGS_CARD_FADE_END - SETTINGS_CARD_FADE_START)
        return fade.coerceIn(0f, 1f)
    }

    fun DrawScope.drawBubbles(
        backplate: Color,
        fill: Color,
        outline: Color,
    ) {
        if (!running()) return
        drawRect(backplate)
        val shape = SettingsCardMorphEasing.transform(time().coerceIn(0f, 1f))
        settingsMorphBubbles(from, cards, anchorBounds.takeIf { !fan }, fan, shape).forEach { (bubble, alpha) ->
            drawBubble(bubble.translate(-host.topLeft), fill, outline, alpha)
        }
    }

    internal fun placeHost(bounds: Rect) {
        host = bounds
    }

    private fun onScreen(cards: Collection<Rect>): List<Rect> =
        cards.filter { host.isEmpty || it.overlaps(host) }.sortedWith(compareBy({ it.top }, { it.left }))
}

/** Marks a control that routes grow out of; see [SettingsCardMorph.anchor]. */
internal interface SettingsMorphAnchor

/**
 * Bubble shapes and alphas at eased progress [shape]. A [fan] spreads the single starting shape into
 * every destination card; an [anchor] with no destination cards gathers every old card into it.
 * Otherwise bubble N travels from old card N to new card N, and old cards without a partner fade
 * as the others reach their places.
 */
internal fun settingsMorphBubbles(
    from: List<Rect>,
    to: List<Rect>,
    anchor: Rect?,
    fan: Boolean,
    shape: Float,
): List<Pair<Rect, Float>> {
    val origin = from.singleOrNull()
    return when {
        fan && origin != null && to.isNotEmpty() -> to.map { lerp(origin, it, shape) to 1f }
        anchor != null && to.isEmpty() -> from.map { lerp(it, anchor, shape) to 1f }
        else ->
            from.mapIndexed { index, start ->
                val end = to.getOrNull(index)
                if (end == null) start to 1f - shape else lerp(start, end, shape) to 1f
            }
    }
}

internal val LocalSettingsCardMorph = compositionLocalOf<SettingsCardMorph?> { null }

/**
 * Marks one Settings card (a row group or panel), or the [anchor] control a route grows from, as a
 * morph endpoint. No effect outside a route host.
 */
@Composable
internal fun Modifier.settingsCardMorph(anchor: SettingsMorphAnchor? = null): Modifier {
    val morph = LocalSettingsCardMorph.current ?: return this
    val token = remember(morph, anchor) { anchor ?: Any() }
    DisposableEffect(morph, token) { onDispose { morph.remove(token) } }
    return onGloballyPositioned { morph.report(token, Rect(it.positionInWindow(), it.size.toSize())) }
}

/**
 * Starts [morph] whenever [target] changes and returns whether it is running, with the host modifier
 * for the route layer. A null [morph] never morphs.
 */
@Composable
internal fun <T> rememberSettingsCardMorphHost(
    morph: SettingsCardMorph?,
    target: T,
    ownerKey: Any?,
    motion: Boolean,
): Pair<Boolean, Modifier> {
    // Runs while the new route composes, before the old route's cards have reported their removal.
    val morphing = morph != null && remember(ownerKey, target) { morph.begin(motion) }
    val morphProgress = remember(ownerKey, target) { Animatable(if (morphing) 0f else 1f) }
    morph?.time = { morphProgress.value }
    LaunchedEffect(morphProgress) {
        // Linear time: the bubbles ease their geometry and the page fades on its own schedule.
        morphProgress.animateTo(1f, tween(SETTINGS_CARD_MORPH_MILLIS, easing = LinearEasing))
        morph?.finish()
    }
    if (morph == null) return false to Modifier
    val host =
        Modifier.settingsCardMorphHost(
            morph,
            backplate = MaterialTheme.colorScheme.surfaceContainerLow,
            fill = MaterialTheme.colorScheme.surfaceContainerLowest,
            outline = if (isAmoledSurfaceTheme()) MaterialTheme.colorScheme.outline else Color.Unspecified,
        )
    return morphing to host
}

/** Draws the travelling bubbles behind the route and fades the new page in over them as one layer. */
internal fun Modifier.settingsCardMorphHost(
    morph: SettingsCardMorph,
    backplate: Color,
    fill: Color,
    outline: Color,
): Modifier =
    onGloballyPositioned { morph.placeHost(it.boundsInWindow()) }
        .drawBehind { with(morph) { drawBubbles(backplate, fill, outline) } }
        .graphicsLayer { alpha = morph.pageAlpha() }

private fun DrawScope.drawBubble(
    bubble: Rect,
    fill: Color,
    outline: Color,
    alpha: Float,
) {
    val corner = CornerRadius(SettingsCardMorphCorner.toPx())
    drawRoundRect(fill, bubble.topLeft, bubble.size, corner, alpha = alpha)
    if (outline != Color.Unspecified) {
        drawRoundRect(outline, bubble.topLeft, bubble.size, corner, style = Stroke(1.dp.toPx()), alpha = alpha)
    }
}
