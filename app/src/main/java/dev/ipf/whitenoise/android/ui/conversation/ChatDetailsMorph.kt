package dev.ipf.whitenoise.android.ui.conversation

import android.graphics.Picture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal const val CHAT_DETAILS_MORPH_MILLIS = 300

/** Standard emphasized curve: the card leaves its origin quickly and settles softly at either end. */
internal val ChatDetailsMorphEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val CONTENT_FADE_IN_START = 0.1f
private const val CONTENT_FADE_OUT_START = 0.5f
private const val CONTENT_FADE_LENGTH = 0.4f
private const val CARD_FADE_OUT_START = 0.85f
private const val SCRIM_ALPHA = 0.32f

/** A captured close waits at most this many frames for the details page to draw once more. */
private const val CAPTURE_FRAME_LIMIT = 3

/** Matches the composer pill, so the card starts and ends as the same shape. */
private val ChatDetailsCardCornerRadius = 24.dp

/** Card bounds at [fraction] of the way from [from] to [to]. */
internal fun chatDetailsCardRect(
    from: Rect,
    to: Rect,
    fraction: Float,
): Rect = lerp(from, to, fraction.coerceIn(0f, 1f))

/** Opening fades the page in after the pill starts moving; closing fades it out before the pill lands. */
internal fun chatDetailsContentAlpha(
    opening: Boolean,
    time: Float,
): Float =
    if (opening) {
        ((time - CONTENT_FADE_IN_START) / CONTENT_FADE_LENGTH).coerceIn(0f, 1f)
    } else {
        1f - ((time - CONTENT_FADE_OUT_START) / CONTENT_FADE_LENGTH).coerceIn(0f, 1f)
    }

/** Only the closing card fades, in its last moments, so the live composer takes over without a cut. */
internal fun chatDetailsCardAlpha(
    opening: Boolean,
    time: Float,
): Float = if (opening) 1f else 1f - ((time - CARD_FADE_OUT_START) / (1f - CARD_FADE_OUT_START)).coerceIn(0f, 1f)

/** Per-conversation morph bookkeeping. Nothing here outlives the conversation owner. */
private class ChatDetailsMorphState(
    var showing: Boolean,
) {
    /** Composer bounds in window when details opened; the close target if the composer is not laid out. */
    var origin: Rect? = null

    /** An inert frame of the details page, recorded only for the close that requested it. */
    var picture: Picture? = null
    var hostOrigin: Offset = Offset.Zero
    var captureRequest by mutableStateOf<(() -> Unit)?>(null)
    var captured = false

    /** Records a route change and reports whether the morph, rather than the route slide, owns it. */
    fun commit(
        showDetails: Boolean,
        motionEnabled: Boolean,
        composerBounds: () -> Rect?,
    ): Boolean {
        val changed = showing != showDetails
        showing = showDetails
        // An unanimated open leaves no origin, so its close keeps the route slide too.
        if (changed && showDetails) origin = if (motionEnabled) composerBounds() else null
        return when {
            !changed || !motionEnabled -> false
            showDetails -> origin != null
            else -> picture != null && origin != null
        }
    }
}

/** The details page container transform, shared by group and direct-message details. */
internal class ChatDetailsMorph(
    /** True while the morph is running; the route slide must stay still. */
    val active: Boolean,
    /** Applied to both the details host and the transcript host. */
    val modifier: Modifier,
    /**
     * Runs its action after the details page has drawn one inert frame for the card to shrink. The
     * page keeps its own composition and handlers until the action runs, at most a few frames later.
     */
    val close: (action: () -> Unit) -> Unit,
)

/**
 * Opening grows the live details page out of the composer pill over the themed backplate. Closing
 * shows the live transcript at once and shrinks an inert picture of the last details frame back
 * into the live composer, so no old-route handlers, focus or editable state survive the switch.
 * Without a measured composer, or with reduced motion, the caller's route motion applies instead.
 */
@Composable
internal fun rememberChatDetailsMorph(
    showDetails: Boolean,
    ownerKey: Any?,
    motionEnabled: Boolean,
    composerBounds: () -> Rect?,
): ChatDetailsMorph {
    val state = remember(ownerKey) { ChatDetailsMorphState(showDetails) }
    // Runs while the new route composes, before the transcript's composer is detached.
    val active = remember(ownerKey, showDetails) { state.commit(showDetails, motionEnabled, composerBounds) }
    val progress = remember(ownerKey, showDetails) { Animatable(if (active) 0f else 1f) }
    // Settles back to false so a later Composer settings change keeps its own slide.
    val running by remember(progress) { derivedStateOf { active && progress.value < 1f } }
    LaunchedEffect(progress) {
        try {
            if (active) progress.animateTo(1f, tween(CHAT_DETAILS_MORPH_MILLIS, easing = LinearEasing))
        } finally {
            // Also drops the frame when details reopen mid-shrink; an open interrupted by a close keeps it.
            if (!showDetails) state.picture = null
        }
    }
    val request = state.captureRequest
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        repeat(CAPTURE_FRAME_LIMIT) { if (!state.captured) withFrameNanos { } }
        state.captureRequest = null
        request()
    }
    val colors = MaterialTheme.colorScheme
    val card = CardColors(colors.background, colors.surfaceContainerHigh, colors.outlineVariant)
    // The drawn card is not where the laid-out page is, so touches wait until the morph settles.
    val modifier =
        Modifier
            .pointerInput(progress) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (running) event.changes.forEach { it.consume() }
                    }
                }
            }.chatDetailsMorph(state, showDetails, active, { progress.value }, card, composerBounds)
    val close: (() -> Unit) -> Unit = { action ->
        if (!motionEnabled || state.origin == null) {
            action()
        } else {
            state.captured = false
            state.captureRequest = action
        }
    }
    return ChatDetailsMorph(running, modifier, close)
}

private class CardColors(
    val backplate: Color,
    val fill: Color,
    val outline: Color,
)

/** Records a requested close frame, then draws the opening or closing card over or under the content. */
@Suppress("LongParameterList")
private fun Modifier.chatDetailsMorph(
    state: ChatDetailsMorphState,
    showDetails: Boolean,
    active: Boolean,
    progress: () -> Float,
    card: CardColors,
    composerBounds: () -> Rect?,
): Modifier =
    onGloballyPositioned { state.hostOrigin = it.positionInWindow() }
        .drawWithContent {
            if (showDetails && state.captureRequest != null && !state.captured) record(state)
            val time = progress()
            val origin = state.origin
            if (!active || time >= 1f || origin == null) {
                drawContent()
                return@drawWithContent
            }
            if (showDetails) {
                drawRect(card.backplate)
                drawOpening(origin.translate(-state.hostOrigin), time, card)
            } else {
                drawContent()
                drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * (1f - time)))
                val target = (composerBounds() ?: origin).translate(-state.hostOrigin)
                state.picture?.let { drawClosing(it, target, time, card) }
            }
        }

/** Records the current frame only; the visible draw follows unchanged. */
private fun ContentDrawScope.record(state: ChatDetailsMorphState) {
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    if (width <= 0 || height <= 0) return
    val picture = Picture()
    val recording = Canvas(picture.beginRecording(width, height))
    draw(this, layoutDirection, recording, size) { this@record.drawContent() }
    picture.endRecording()
    state.picture = picture
    state.captured = true
}

private fun ContentDrawScope.drawOpening(
    anchor: Rect,
    time: Float,
    colors: CardColors,
) {
    val grow = ChatDetailsMorphEasing.transform(time)
    drawCard(
        rect = chatDetailsCardRect(anchor, size.toRect(), grow),
        presence = grow,
        cardAlpha = chatDetailsCardAlpha(opening = true, time),
        contentAlpha = chatDetailsContentAlpha(opening = true, time),
        colors = colors,
    ) { this@drawOpening.drawContent() }
}

private fun DrawScope.drawClosing(
    picture: Picture,
    target: Rect,
    time: Float,
    colors: CardColors,
) {
    val shrink = ChatDetailsMorphEasing.transform(time)
    drawCard(
        rect = chatDetailsCardRect(size.toRect(), target, shrink),
        presence = 1f - shrink,
        cardAlpha = chatDetailsCardAlpha(opening = false, time),
        contentAlpha = chatDetailsContentAlpha(opening = false, time),
        colors = colors,
    ) { drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
}

/**
 * Draws a composer-colored rounded card at [rect] with the full-size page scaled to its width.
 * [presence] runs from 0 (the composer pill) to 1 (the full page with square corners).
 */
@Suppress("LongParameterList")
private fun DrawScope.drawCard(
    rect: Rect,
    presence: Float,
    cardAlpha: Float,
    contentAlpha: Float,
    colors: CardColors,
    content: DrawScope.() -> Unit,
) {
    if (cardAlpha <= 0f || rect.width <= 0f || size.width <= 0f) return
    val radius = CornerRadius(ChatDetailsCardCornerRadius.toPx() * (1f - presence))
    withLayer(rect, cardAlpha) {
        clipPath(Path().apply { addRoundRect(RoundRect(rect, radius)) }) {
            drawRect(colors.fill, rect.topLeft, rect.size)
            withLayer(rect, contentAlpha) {
                val scale = rect.width / size.width
                withTransform({
                    translate(rect.left, rect.top)
                    scale(scale, scale, Offset.Zero)
                }) { content() }
            }
        }
        drawRoundRect(
            color = colors.outline,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = radius,
            alpha = 1f - presence,
            style = Stroke(1.dp.toPx()),
        )
    }
}

private inline fun DrawScope.withLayer(
    bounds: Rect,
    alpha: Float,
    block: DrawScope.() -> Unit,
) {
    if (alpha <= 0f) return
    val layered = alpha < 1f
    if (layered) drawIntoCanvas { it.saveLayer(bounds, Paint().apply { this.alpha = alpha }) }
    block()
    if (layered) drawIntoCanvas { it.restore() }
}
