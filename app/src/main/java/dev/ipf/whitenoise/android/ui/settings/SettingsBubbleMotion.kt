package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.animation.core.Easing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.boundingRect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import kotlin.math.ceil

internal const val SETTINGS_BUBBLE_OPEN_MILLIS = APP_MOTION_OPEN_MILLIS
internal const val SETTINGS_BUBBLE_CLOSE_MILLIS = APP_MOTION_CLOSE_MILLIS

/** The card arrives quickly and settles softly. */
internal val SettingsBubbleOpenEasing: Easing = AppMotionOpenEasing

/** The card leaves without lingering over Chats. */
internal val SettingsBubbleCloseEasing: Easing = AppMotionCloseEasing

private const val SETTINGS_CARD_MIN_SCALE = 0.88f
private const val SETTINGS_CARD_OPEN_FADE_END = 0.35f
private const val SETTINGS_CARD_CLOSE_FADE_END = 0.6f
private const val SETTINGS_CARD_SCRIM_ALPHA = 0.32f

/** Below this presence an origin container is an empty shape: Settings ink arrives over the rest of the open. */
internal const val SETTINGS_CONTAINER_BODY_FADE_START = 0.35f

/** The container itself appears and, closing, disappears into its origin over this much presence. */
private const val SETTINGS_CONTAINER_SHAPE_FADE = 0.2f

/** The AMOLED outline leaves before the container reaches the host edges. */
private const val SETTINGS_CONTAINER_OUTLINE_FADE_START = 0.85f
private val SettingsCardCornerRadius = 28.dp

/** How far a route without a measured origin slides in from its logical edge. */
internal val SettingsRouteSlideTravel = 48.dp
private val SettingsContainerOutlineWidth = 1.dp

/** The card belongs only to the top-level Chats/Settings pair; deeper routes keep the slide. */
internal fun usesSettingsBubble(
    previousDepth: Int,
    nextDepth: Int,
): Boolean = minOf(previousDepth, nextDepth) == 0 && maxOf(previousDepth, nextDepth) == 1

/** Last settled Settings frame, kept per route host so closing can shrink it over live Chats. */
internal class SettingsCardSnapshot {
    var picture: Picture? = null
}

/** Paint for the top-level container. A specified [outline] (AMOLED) strokes its edge. */
internal data class SettingsContainerColors(
    val backplate: Color,
    val fill: Color,
    val outline: Color = Color.Unspecified,
)

/**
 * The container at [presence], in host coordinates. With an [origin] it is one rounded shape that
 * starts as the origin's circle and grows to the whole host while its corners ease to square.
 * Without one it is the end-edge card. Nothing is scaled: callers clip native content to it.
 */
internal fun settingsContainerShape(
    origin: Rect?,
    size: Size,
    rtl: Boolean,
    presence: Float,
    fallbackRadius: Float,
): RoundRect {
    val p = presence.coerceIn(0f, 1f)
    val host = Rect(Offset.Zero, size)
    val start: Rect
    val startRadius: Float
    if (origin != null) {
        val half = minOf(origin.width, origin.height) / 2f
        start = Rect(origin.center, half)
        startRadius = half
    } else {
        val pivot = Offset(if (rtl) 0f else size.width, size.height / 2f)
        start =
            Rect(
                pivot.x - pivot.x * SETTINGS_CARD_MIN_SCALE,
                pivot.y - pivot.y * SETTINGS_CARD_MIN_SCALE,
                pivot.x + (size.width - pivot.x) * SETTINGS_CARD_MIN_SCALE,
                pivot.y + (size.height - pivot.y) * SETTINGS_CARD_MIN_SCALE,
            )
        startRadius = fallbackRadius
    }
    val bounds = lerp(start, host, p)
    val radius = (startRadius * (1f - p)).coerceAtMost(minOf(bounds.width, bounds.height) / 2f).coerceAtLeast(0f)
    return RoundRect(bounds, CornerRadius(radius))
}

/** Settings ink inside an origin container: none early, fully native at rest. Shared by both directions. */
internal fun settingsContainerBodyAlpha(presence: Float): Float =
    ((presence - SETTINGS_CONTAINER_BODY_FADE_START) / (1f - SETTINGS_CONTAINER_BODY_FADE_START)).coerceIn(0f, 1f)

/** The empty container fades in leaving its origin and out landing in it, revealing the live control. */
internal fun settingsContainerShapeAlpha(presence: Float): Float =
    (presence / SETTINGS_CONTAINER_SHAPE_FADE).coerceIn(0f, 1f)

/**
 * Draws the top-level container at [presence]. With an origin, every alpha is a function of
 * [presence] alone, so a reversed transition continues from the drawn frame in either direction.
 */
private fun DrawScope.drawSettingsContainer(
    origin: Rect?,
    presence: Float,
    opening: Boolean,
    colors: SettingsContainerColors,
    content: (DrawScope.() -> Unit)?,
) {
    val rtl = layoutDirection == LayoutDirection.Rtl
    val shape = settingsContainerShape(origin, size, rtl, presence, SettingsCardCornerRadius.toPx())
    if (origin != null) {
        val shapeAlpha = settingsContainerShapeAlpha(presence)
        drawContainer(shape, presence, shapeAlpha, settingsContainerBodyAlpha(presence), colors, content)
    } else {
        // The end-edge card keeps its legacy single fade for the whole card.
        val fadeEnd = if (opening) SETTINGS_CARD_OPEN_FADE_END else SETTINGS_CARD_CLOSE_FADE_END
        val alpha = (presence / fadeEnd).coerceIn(0f, 1f)
        drawContainer(shape, presence, alpha, alpha, colors, content)
    }
}

/** One rounded container: its fill, [content] clipped to it at native coordinates, and the optional outline. */
@Suppress("LongParameterList")
private fun DrawScope.drawContainer(
    shape: RoundRect,
    presence: Float,
    shapeAlpha: Float,
    bodyAlpha: Float,
    colors: SettingsContainerColors,
    content: (DrawScope.() -> Unit)?,
) {
    if (shapeAlpha <= 0f) return
    val clip = Path().apply { addRoundRect(shape) }
    drawPath(clip, colors.fill, alpha = shapeAlpha)
    if (content != null && bodyAlpha > 0f) {
        clipPath(clip) {
            val layered = bodyAlpha < 1f
            if (layered) drawIntoCanvas { it.saveLayer(shape.boundingRect, Paint().apply { alpha = bodyAlpha }) }
            content()
            if (layered) drawIntoCanvas { it.restore() }
        }
    }
    if (!colors.outline.isSpecified) return
    val leaving = (presence - SETTINGS_CONTAINER_OUTLINE_FADE_START) / (1f - SETTINGS_CONTAINER_OUTLINE_FADE_START)
    val edge = 1f - leaving.coerceIn(0f, 1f)
    val width = SettingsContainerOutlineWidth.toPx()
    val inset = shape.boundingRect.deflate(width / 2f)
    if (edge > 0f && !inset.isEmpty) {
        val radius = (shape.topLeftCornerRadius.x - width / 2f).coerceAtLeast(0f)
        drawRoundRect(
            colors.outline,
            inset.topLeft,
            inset.size,
            CornerRadius(radius),
            style = Stroke(width),
            alpha = shapeAlpha * edge,
        )
    }
}

/** A container growing from a measured control rectangle keeps that control's rounded-row corners at first. */
private val SettingsRowContainerCornerRadius = 20.dp

/**
 * Route motion for card-morph hosts in borderless themes, which have no outlines to morph: the live
 * destination grows in one container from the activating control's local [origin] when there is
 * one, and otherwise slides [travel] px over the backplate (Back always slides). Nothing is captured.
 */
internal fun Modifier.settingsBorderlessRouteMotion(
    progress: () -> Float,
    origin: () -> Rect?,
    colors: SettingsMorphColors,
    travel: Float,
): Modifier =
    drawWithContent {
        if (progress() < 1f && origin() == null) drawRect(colors.backplate)
        drawContent()
    }.graphicsLayer {
        translationX = if (origin() == null) travel * (1f - progress().coerceIn(0f, 1f)) else 0f
    }.drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        val start = origin()
        if (p >= 1f || start == null) {
            drawContent()
            return@drawWithContent
        }
        drawRect(colors.backplate)
        val bounds = lerp(start, Rect(Offset.Zero, size), p)
        val startRadius = minOf(SettingsRowContainerCornerRadius.toPx(), minOf(start.width, start.height) / 2f)
        val radius = (startRadius * (1f - p)).coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
        val shape = RoundRect(bounds, CornerRadius(radius))
        drawContainer(
            shape,
            p,
            settingsContainerShapeAlpha(p),
            settingsContainerBodyAlpha(p),
            SettingsContainerColors(colors.backplate, colors.fill),
        ) { this@drawWithContent.drawContent() }
    }

/** Draws the content and keeps a copy of the frame for the next close. */
private fun ContentDrawScope.drawAndRecord(snapshot: SettingsCardSnapshot) {
    val picture = snapshot.picture ?: Picture().also { snapshot.picture = it }
    drawAndRecord(picture)
}

/** Draws the content and records the same frame into [picture]. */
internal fun ContentDrawScope.drawAndRecord(picture: Picture) {
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    if (width <= 0 || height <= 0) {
        drawContent()
        return
    }
    val recording = Canvas(picture.beginRecording(width, height))
    try {
        draw(this, layoutDirection, recording, size) { this@drawAndRecord.drawContent() }
    } finally {
        picture.endRecording()
    }
    // Nested RenderNode invalidations must reach the live canvas, not a replayed Picture.
    // Retain the inert copy only for the outgoing transition.
    drawContent()
}

/** Keeps the settled top-level Settings frame when it was reached without the card motion. */
internal fun Modifier.recordSettingsCard(
    snapshot: SettingsCardSnapshot,
    settled: () -> Boolean,
): Modifier = drawWithContent { if (settled()) drawAndRecord(snapshot) else drawContent() }

/**
 * Opening grows the live Settings destination in one container over the themed backplate: from
 * [origin] (the activating control, host coordinates) when there is one, else from the end edge.
 * Closing never retains the Settings composition: Chats is live at once and only [closingPicture], an
 * inert picture of the last available Settings frame, contracts back into the origin over it, so no
 * old-route handlers, focus or editable state survive the switch. Without a picture the bare
 * container still contracts. Chats itself is never captured, because its window privacy flag is
 * released while Settings is shown. Opening records into [snapshot] only while [recording] allows it.
 */
internal fun Modifier.settingsBubbleMotion(
    opening: Boolean,
    progress: () -> Float,
    animating: () -> Boolean,
    colors: SettingsContainerColors,
    snapshot: SettingsCardSnapshot?,
    closingPicture: Picture? = snapshot?.picture,
    origin: () -> Rect? = { null },
    recording: () -> Boolean = { true },
): Modifier =
    if (opening) {
        drawWithContent {
            if (!animating()) {
                if (snapshot != null && recording()) drawAndRecord(snapshot) else drawContent()
                return@drawWithContent
            }
            val presence = progress().coerceIn(0f, 1f)
            val start = origin()
            drawRect(colors.backplate)
            // The vacated Chats area dims behind an origin container so it reads in every theme.
            if (start != null) {
                drawRect(Color.Black.copy(alpha = SETTINGS_CARD_SCRIM_ALPHA * settingsContainerShapeAlpha(presence)))
            }
            drawSettingsContainer(start, presence, opening = true, colors) {
                if (snapshot != null && snapshot.picture == null && recording()) {
                    this@drawWithContent.drawAndRecord(snapshot)
                } else {
                    this@drawWithContent.drawContent()
                }
            }
        }
    } else {
        drawWithContent {
            drawContent()
            if (!animating()) return@drawWithContent
            val presence = 1f - progress().coerceIn(0f, 1f)
            val picture = closingPicture
            val target = origin()
            // The end-edge card has nothing to contract without its picture.
            if (picture == null && target == null) return@drawWithContent
            val scrim = if (target != null) settingsContainerShapeAlpha(presence) else presence
            drawRect(Color.Black.copy(alpha = SETTINGS_CARD_SCRIM_ALPHA * scrim))
            drawSettingsContainer(
                target,
                presence,
                opening = false,
                colors,
                picture?.let { frame -> { drawIntoCanvas { it.nativeCanvas.drawPicture(frame) } } },
            )
        }
    }
