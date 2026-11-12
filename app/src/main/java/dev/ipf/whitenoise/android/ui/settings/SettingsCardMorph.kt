package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

internal const val SETTINGS_CARD_MORPH_MILLIS = APP_MOTION_OPEN_MILLIS

/** Emphasized decelerate, shared with the top-level Settings card. */
internal val SettingsCardMorphEasing: Easing = AppMotionOpenEasing

/**
 * Container transform between two routes, shared by nested Settings and the chat's header and composer.
 *
 * Every card on the visible route reports its window bounds and keeps an inert picture of its last frame.
 * When the route changes, card N of the old route and card N of the new one, counted top to bottom on
 * screen, become one travelling card: the old card's picture fades out inside it while the new card,
 * live, is moved and clipped to the same shape and fades in, so content never floats outside a card
 * or waits for one to land. A route that grows out of an [anchor] (a chat header or composer pill)
 * fans that one shape out into all of its cards, and its cards fold back into the anchor over the
 * live route underneath. The old route is still disposed immediately, so no Back handler, focus,
 * editable state or protected content outlives it; only card pictures of the route being left are kept,
 * never those of a route holding the window's secure flag or pixels of a route without cards such as
 * a chat transcript.
 * Card registration, recording, and disposal operate on one route-scoped picture owner.
 */
@Suppress("TooManyFunctions")
internal class SettingsCardMorph(
    fullScreen: Boolean = false,
    private val parent: SettingsCardMorph? = null,
) {
    val page: SettingsPageMorph? = if (fullScreen) SettingsPageMorph() else null
    private val live = LinkedHashMap<Any, Rect>()
    private val pictures = HashMap<Any, Picture>()
    private var host = Rect.Zero
    private var from: List<SettingsMorphCard> = emptyList()
    private var fan = false
    private var secureHolds = 0
    var captureContent = true

    /**
     * The control a whole route grows out of, such as a chat header. With an anchor on screen and no
     * cards, that one shape fans out into every card of the next route; leaving those cards for a
     * route that shows the anchor, they all fold back into it.
     */
    var anchor: SettingsMorphAnchor? = null

    /** Linear morph time from 0 to 1; read during draw so each frame follows the animation. */
    var time: () -> Float = { 1f }

    private val cardTokens: List<Any>
        get() =
            live.entries
                .filter { (token, bounds) -> token !is SettingsMorphAnchor && onScreen(bounds) }
                .sortedWith(compareBy({ it.value.top }, { it.value.left }))
                .map { it.key }

    private val anchorBounds: Rect? get() = anchor?.let(live::get)?.takeIf(::onScreen)

    private val folding: Boolean get() = !fan && anchorBounds != null && cardTokens.isEmpty()

    /** Whether leaving cards are folding over the live route, which must not take input until they are gone. */
    val coversRoute: Boolean get() = (page?.active == true && !page.opening && time() < 1f) || (running && folding)

    val blocksPointerInput: Boolean get() = coversRoute || (page?.active == true && time() < 1f)

    /** Observe the existing child's viewport in every ancestor that retains its pixels. */
    fun observeCaptureInputs(read: () -> Any?): () -> Unit {
        page?.captureInputs = read
        val releaseParent = parent?.observeCaptureInputs(read)
        return {
            page?.let { if (it.captureInputs === read) it.captureInputs = null }
            releaseParent?.invoke()
        }
    }

    fun report(
        token: Any,
        bounds: Rect,
    ) {
        live[token] = bounds
    }

    fun remove(token: Any) {
        live.remove(token)
        pictures.remove(token)
    }

    /** The picture [token]'s card records each frame, kept as its look if the route is left. */
    fun picture(token: Any): Picture = pictures.getOrPut(token, ::Picture)

    /** Whether cards record pictures: never while a route on this host holds the window's secure flag. */
    val recording: Boolean get() = secureHolds == 0

    /**
     * Marks a route on this host as protected until the returned release runs. Its cards are not
     * recorded and leave as bare shapes, so no picture of them outlives the window's secure flag.
     */
    fun holdSecure(): () -> Unit {
        secureHolds++
        val releaseParent = parent?.holdSecure()
        from = from.map { SettingsMorphCard(it.bounds, null) }
        pictures.clear()
        page?.clearPictures()
        return {
            secureHolds--
            releaseParent?.invoke()
        }
    }

    /** Cards kept from the route being left that carry a picture of their last frame. */
    internal val keptPictures: Int get() = from.count { it.picture != null } + (page?.keptPictures ?: 0)

    /**
     * Keeps the on-screen cards of the route being left as the next morph's starting shapes and
     * returns whether there is anything to morph from.
     */
    fun begin(animate: Boolean): Boolean {
        val leaving = cardTokens.map { SettingsMorphCard(live.getValue(it), pictures[it]?.takeIf { recording }) }
        val origin = anchorBounds
        fan = leaving.isEmpty() && origin != null
        from =
            when {
                !animate -> emptyList()
                origin != null && fan -> listOf(SettingsMorphCard(origin, null))
                else -> leaving
            }
        live.clear()
        pictures.clear()
        return from.isNotEmpty()
    }

    fun finish() {
        page?.finish()
        from = emptyList()
    }

    private val running: Boolean get() = from.isNotEmpty() && time() < 1f

    /** Labels and other non-card content of an arriving route fade in as its cards start to travel. */
    fun pageAlpha(): Float = if (page != null || !running || folding) 1f else settingsMorphContentIn(time())

    /**
     * The arriving card [token]'s settled bounds and the travelling card it is drawn in this frame,
     * both in window coordinates, or null when it is settled or has no partner to travel from.
     */
    fun travel(token: Any): Pair<Rect, Rect>? {
        val end = live[token]?.takeIf { running } ?: return null
        val start = if (fan) from.singleOrNull() else from.getOrNull(cardTokens.indexOf(token))
        return start?.let { end to lerp(it.bounds, end, SettingsCardMorphEasing.transform(time())) }
    }

    /**
     * Draws the route with the morph: travelling cards behind an arriving route, or, when cards fold
     * into an anchor, over the live route beneath them.
     */
    fun ContentDrawScope.drawMorph(colors: SettingsMorphColors) {
        page?.let {
            with(it) { draw(host, time(), recording, colors.backplate, captureContent) }
            return
        }
        if (!running) {
            drawContent()
            return
        }
        val t = time().coerceIn(0f, 1f)
        val leavingOver = folding
        if (leavingOver) {
            drawContent()
            drawRect(colors.backplate, alpha = settingsMorphFoldBackplate(t))
        } else {
            drawRect(colors.backplate)
        }
        val to = cardTokens.map(live::getValue)
        val shape = SettingsCardMorphEasing.transform(t)
        val bubbles = settingsMorphBubbles(from.map { it.bounds }, to, anchorBounds.takeIf { !fan }, fan, shape)
        val handover = settingsMorphHandover(t, leavingOver)
        val pictureAlpha = settingsMorphContentOut(t, leavingOver)
        bubbles.forEachIndexed { index, (bubble, alpha) ->
            val local = bubble.translate(-host.topLeft)
            drawSettingsMorphBubble(local, colors, alpha * handover)
            val card = if (fan) null else from.getOrNull(index)
            val picture = card?.picture
            if (picture != null) drawSettingsMorphPicture(picture, card.bounds, local, alpha * pictureAlpha)
        }
        if (!leavingOver) drawContent()
    }

    internal fun beginPage(
        target: Any?,
        forward: Boolean,
        animate: Boolean,
    ): Boolean {
        if (forward && page?.pendingOrigin == null) page?.pendingOrigin = anchorBounds
        return page?.begin(target, forward, animate, host) == true
    }

    internal fun placeHost(bounds: Rect) {
        host = bounds
    }

    private fun onScreen(bounds: Rect): Boolean = host.isEmpty || bounds.overlaps(host)
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
 * morph endpoint. A card travels from its partner on the previous route and keeps a picture of its
 * last frame for the next one. No effect outside a route host.
 */
@Composable
internal fun Modifier.settingsCardMorph(anchor: SettingsMorphAnchor? = null): Modifier {
    val morph = LocalSettingsCardMorph.current
    if (morph == null || (morph.page != null && anchor == null)) return this
    val view = LocalView.current
    val token = remember(morph, anchor) { anchor ?: Any() }
    DisposableEffect(morph, token) { onDispose { morph.remove(token) } }
    // Reported outside the card's own layer, so the bounds stay the settled ones while it travels.
    val positioned = onGloballyPositioned { morph.report(token, it.settingsMorphBounds(view)) }
    // An anchor only marks where routes grow from; it neither travels nor is recorded.
    return if (anchor != null) {
        positioned
    } else {
        positioned
            .graphicsLayer { applySettingsMorphTravel(morph.travel(token)) }
            .drawWithContent { if (morph.recording) drawAndRecord(morph.picture(token)) else drawContent() }
    }
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
    forward: Boolean = true,
    captureContent: Boolean = true,
): Pair<Boolean, Modifier> {
    morph?.captureContent = captureContent
    // Runs while the new route composes, before the old route's cards have reported their removal.
    val morphing =
        morph != null &&
            remember(morph, ownerKey, target, motion) {
                if (morph.page != null) morph.beginPage(target, forward, motion) else morph.begin(motion)
            }
    val morphProgress = remember(morph, ownerKey, target, motion) { Animatable(if (morphing) 0f else 1f) }
    morph?.time = { morphProgress.value }
    LaunchedEffect(morphProgress) {
        // Linear time: card geometry eases on its own and content fades on its own schedule.
        morphProgress.animateTo(
            1f,
            tween(
                if (forward) SETTINGS_CARD_MORPH_MILLIS else APP_MOTION_CLOSE_MILLIS,
                easing = LinearEasing,
            ),
        )
        morph?.finish()
    }
    if (morph == null) return false to Modifier
    androidx.compose.runtime.SideEffect { morph.page?.pendingOrigin = null }
    val colors =
        SettingsMorphColors(
            backplate = MaterialTheme.colorScheme.surfaceContainerLow,
            fill = MaterialTheme.colorScheme.surfaceContainerLowest,
            outline = if (isAmoledSurfaceTheme()) MaterialTheme.colorScheme.outline else Color.Unspecified,
        )
    val view = LocalView.current
    return morphing to
        Modifier
            .settingsCardMorphHost(morph, colors, view)
            .then(if (morph.coversRoute) Modifier.clearAndSetSemantics {} else Modifier)
}

/**
 * Draws the travelling cards with the route, fades an arriving route's non-card content in as one layer
 * and holds back input while leaving cards fold over the live route.
 */
internal fun Modifier.settingsCardMorphHost(
    morph: SettingsCardMorph,
    colors: SettingsMorphColors,
    view: android.view.View? = null,
): Modifier =
    onGloballyPositioned { morph.placeHost(if (view != null) it.settingsMorphBounds(view) else it.boundsInWindow()) }
        .onPreviewKeyEvent { morph.coversRoute }
        .pointerInput(morph) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (morph.blocksPointerInput) event.changes.forEach { it.consume() }
                }
            }
        }.drawWithContent { with(morph) { drawMorph(colors) } }
        // Full-screen pages already transform during draw and always have unit page alpha.
        // An extra retained layer here can hide nested redraws from an ancestor's recorder.
        .then(if (morph.page == null) Modifier.graphicsLayer { alpha = morph.pageAlpha() } else Modifier)
