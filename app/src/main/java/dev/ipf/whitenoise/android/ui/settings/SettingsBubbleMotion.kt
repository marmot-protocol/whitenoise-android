package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.animation.core.Easing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import kotlin.math.ceil

internal const val SETTINGS_BUBBLE_OPEN_MILLIS = APP_MOTION_OPEN_MILLIS
internal const val SETTINGS_BUBBLE_CLOSE_MILLIS = APP_MOTION_CLOSE_MILLIS

/** The bubble leaves its origin quickly and settles softly on its card, like the New message pen. */
internal val SettingsBubbleOpenEasing: Easing = AppMotionOpenEasing

/** Back starts at rest and lands in the origin without lingering. */
internal val SettingsBubbleCloseEasing: Easing = AppMotionCloseEasing

/** How far a route without a measured origin slides in from its logical edge. */
internal val SettingsRouteSlideTravel = 48.dp

/** The card belongs only to the top-level Chats/Settings pair; deeper routes keep the slide. */
internal fun usesSettingsBubble(
    previousDepth: Int,
    nextDepth: Int,
): Boolean = minOf(previousDepth, nextDepth) == 0 && maxOf(previousDepth, nextDepth) == 1

/** Last settled Settings frame, kept per route host so closing can shrink it over live Chats. */
internal class SettingsCardSnapshot {
    var picture: Picture? = null
}

private const val MIN_SHRINK_FROM = 0.001f
private const val SETTINGS_STACK_START_SCALE = 0.56f
private const val SETTINGS_FADE_START = 0.35f

/** A container growing from a measured control rectangle keeps that control's rounded-row corners at first. */
private val SettingsRowContainerCornerRadius = 20.dp

/**
 * How a borderless container leg continues an interrupted one: [shrinking] Back reverses a container
 * still growing from [shrinkFrom], its drawn presence, and an open [resumedFrom] a shrinking one
 * brings its body in from none.
 */
internal class SettingsContainerResume(
    val shrinking: Boolean = false,
    val resumedFrom: Float? = null,
    val shrinkFrom: Float = 1f,
)

/**
 * Route motion for card-morph hosts in borderless themes, which have no outlines to morph. As in the
 * contour morph, the destination's fixed header above [bodyTop] (host coordinates) stays live and
 * still. Its body grows in one container from the activating control's local [origin] when there is
 * one, and otherwise slides [travel] px over the backplate (Back slides). Back while a container is
 * still growing ([resume] shrinking) shows the live parent at once and contracts the bare container
 * from its drawn bounds into the same origin. Nothing is captured.
 */
@Suppress("LongParameterList")
internal fun Modifier.settingsBorderlessRouteMotion(
    progress: () -> Float,
    origin: () -> Rect?,
    bodyTop: () -> Float,
    colors: SettingsMorphColors,
    travel: Float,
    resume: SettingsContainerResume = SettingsContainerResume(),
): Modifier =
    drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        val top = bodyTop().coerceIn(0f, size.height)
        val shrinkInto = origin()?.takeIf { resume.shrinking }
        if (shrinkInto != null) {
            drawContent()
            clipRect(top = top) {
                // The growing leg's backplate stays at first and fades out with the container, uncovering the parent.
                val backplate = ((1f - p) / resume.shrinkFrom.coerceAtLeast(MIN_SHRINK_FROM)).coerceIn(0f, 1f)
                drawRect(colors.backplate.copy(alpha = colors.backplate.alpha * backplate))
                drawBorderlessContainer(shrinkInto, top, 1f - p, colors, null, null)
            }
            return@drawWithContent
        }
        drawRect(colors.backplate)
        if (top > 0f) clipRect(bottom = top) { this@drawWithContent.drawContent() }
        clipRect(top = top) {
            val start = origin()
            if (start == null) {
                translate(left = travel * (1f - p)) { this@drawWithContent.drawContent() }
                return@clipRect
            }
            drawBorderlessContainer(start, top, p, colors, resume.resumedFrom) { this@drawWithContent.drawContent() }
        }
    }

/** One borderless container at [presence] between the control at [start] and the body below [top]. */
@Suppress("LongParameterList")
private fun DrawScope.drawBorderlessContainer(
    start: Rect,
    top: Float,
    presence: Float,
    colors: SettingsMorphColors,
    resumedFrom: Float?,
    content: (DrawScope.() -> Unit)?,
) {
    val bounds = lerp(start, Rect(0f, top, size.width, size.height), presence)
    val startRadius = minOf(SettingsRowContainerCornerRadius.toPx(), minOf(start.width, start.height) / 2f)
    val radius = (startRadius * (1f - presence)).coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
    drawContainer(
        RoundRect(bounds, CornerRadius(radius)),
        settingsContainerShapeAlpha(presence),
        settingsResumedBodyAlpha(settingsContainerBodyAlpha(presence), presence, resumedFrom),
        SettingsContainerColors(colors.backplate, colors.fill),
        content = content,
    )
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
 * The top-level Chats/Settings pair. Settings scales out from the pen contour, with the landing
 * card fading in inside its own outline. The avatar flies independently into its profile slot.
 * An alternate measured origin gets its own outline. When the pen has no measured landing, the
 * remaining Settings content fades in place. The backplate quickly covers the live Chats underlay.
 *
 * Back runs the same scene in reverse over live Chats using [closingPicture], a copy of the last
 * permitted Settings frame. The moving elements still return without a picture. Interrupted legs
 * pick up the Settings ink they turned around with ([resume]). [recording] applies to Settings;
 * the Chats avatar uses its live display list.
 */
@Suppress("LongParameterList")
internal fun Modifier.settingsBubbleMotion(
    opening: Boolean,
    progress: () -> Float,
    animating: () -> Boolean,
    colors: SettingsBubbleColors,
    snapshot: SettingsCardSnapshot?,
    closingPicture: () -> Picture? = { snapshot?.picture },
    scene: () -> SettingsBubbleScene = { SettingsBubbleScene() },
    recording: () -> Boolean = { true },
    resume: SettingsBubbleResume? = null,
): Modifier =
    if (opening) {
        drawWithContent {
            if (!animating()) {
                if (snapshot != null && recording()) drawAndRecord(snapshot) else drawContent()
                return@drawWithContent
            }
            // A Back during the open closes with this first permitted frame.
            if (snapshot != null && snapshot.picture == null && recording()) record(snapshot)
            val presence = progress().coerceIn(0f, 1f)
            drawRect(colors.backplate, alpha = settingsBubbleUnderlayCover(presence))
            val ink = settingsBubbleInk(presence, opening = true, resume)
            drawSettingsArrival(scene(), presence, colors, ink) { this@drawWithContent.drawContent() }
        }
    } else {
        drawWithContent {
            drawContent()
            if (!animating()) return@drawWithContent
            val presence = 1f - progress().coerceIn(0f, 1f)
            val picture = closingPicture()
            val drawn = scene()
            // Without a picture, a bubble or the avatar there is nothing of Settings left to reverse.
            if (picture == null && drawn.isEmpty) {
                return@drawWithContent
            }
            drawRect(colors.backplate, alpha = settingsBubbleUnderlayCover(presence))
            val content: (DrawScope.() -> Unit)? =
                picture?.let { frame -> { drawIntoCanvas { it.nativeCanvas.drawPicture(frame) } } }
            val ink = settingsBubbleInk(presence, opening = false, resume)
            drawSettingsArrival(drawn, presence, colors, ink, content)
        }
    }

/** The pen contour and the stack share an anchor; the avatar follows its own path. */
internal fun DrawScope.drawSettingsArrival(
    scene: SettingsBubbleScene,
    presence: Float,
    colors: SettingsBubbleColors,
    ink: Float,
    content: (DrawScope.() -> Unit)?,
) {
    val flight = scene.flight
    val page: (DrawScope.() -> Unit)? =
        content?.let { drawPage ->
            {
                drawPage()
                if (flight != null) {
                    // Cover the avatar and its antialiased edge with the surrounding card fill.
                    val slot = flight.to.inflate(1f)
                    drawRect(flight.profileFill, slot.topLeft, slot.size)
                }
            }
        }
    val alpha = settingsStackAlpha(presence, scene.pen != null) * ink
    if (page != null && alpha > 0f) drawSettingsStack(scene, presence, alpha, page)
    scene.origin?.let { drawClippedBubble(it, presence, colors, ink, page) }
    scene.pen?.let { drawClippedBubble(it, presence, colors, ink, page) }
    flight?.let { drawSettingsAvatarFlight(it, presence) }
}

private fun settingsStackAlpha(
    presence: Float,
    fromPen: Boolean,
): Float = if (fromPen) presence else ((presence - SETTINGS_FADE_START) / (1f - SETTINGS_FADE_START)).coerceAtLeast(0f)

private fun DrawScope.drawSettingsStack(
    scene: SettingsBubbleScene,
    presence: Float,
    alpha: Float,
    content: DrawScope.() -> Unit,
) {
    val viewport = Rect(Offset.Zero, size)
    val landing = scene.pen
    val holes =
        Path().apply {
            scene.origin?.let { addRect(it.card.visibleBounds) }
            landing?.let { addRect(it.card.visibleBounds) }
        }
    val stack: DrawScope.() -> Unit = { drawAtAlpha(viewport, alpha) { clipPath(holes, ClipOp.Difference, content) } }
    if (landing == null) {
        stack()
        return
    }
    val pivot = landing.card.bounds.topCenter
    val outline = settingsBubbleShape(landing, presence)
    val offset = Offset((outline.left + outline.right) / 2f, outline.top) - pivot
    val stackScale = SETTINGS_STACK_START_SCALE + (1f - SETTINGS_STACK_START_SCALE) * presence
    translate(offset.x, offset.y) { scale(stackScale, pivot, stack) }
}

/** Records this route's current frame into [snapshot] without drawing it. */
private fun ContentDrawScope.record(snapshot: SettingsCardSnapshot) {
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    if (width <= 0 || height <= 0) return
    val picture = Picture().also { snapshot.picture = it }
    val recording = Canvas(picture.beginRecording(width, height))
    try {
        draw(this, layoutDirection, recording, size) { this@record.drawContent() }
    } finally {
        picture.endRecording()
    }
}
