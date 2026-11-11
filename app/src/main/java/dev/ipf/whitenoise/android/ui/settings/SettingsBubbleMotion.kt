package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.animation.core.Easing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
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
private const val SETTINGS_CARD_CLOSE_FADE_START = 0.4f
private const val SETTINGS_CARD_SCRIM_ALPHA = 0.32f
private val SettingsCardCornerRadius = 28.dp

/** The card belongs only to the top-level Chats/Settings pair; deeper routes keep the slide. */
internal fun usesSettingsBubble(
    previousDepth: Int,
    nextDepth: Int,
): Boolean = minOf(previousDepth, nextDepth) == 0 && maxOf(previousDepth, nextDepth) == 1

/** Last settled Settings frame, kept per route host so closing can shrink it over live Chats. */
internal class SettingsCardSnapshot {
    var picture: Picture? = null
}

/**
 * Draws [content] as a card anchored to the vertical middle of the logical end edge. [presence]
 * runs from 0 (small rounded card) to 1 (full screen, square corners).
 */
private fun DrawScope.drawSettingsCard(
    presence: Float,
    alpha: Float,
    content: DrawScope.() -> Unit,
) {
    if (alpha <= 0f) return
    val scale = SETTINGS_CARD_MIN_SCALE + (1f - SETTINGS_CARD_MIN_SCALE) * presence
    val pivot = Offset(if (layoutDirection == LayoutDirection.Rtl) 0f else size.width, size.height / 2f)
    val radius = SettingsCardCornerRadius.toPx() * (1f - presence)
    val clip = Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(radius))) }
    val layered = alpha < 1f
    if (layered) drawIntoCanvas { it.saveLayer(size.toRect(), Paint().apply { this.alpha = alpha }) }
    withTransform({
        scale(scale, scale, pivot)
        clipPath(clip)
    }) { content() }
    if (layered) drawIntoCanvas { it.restore() }
}

/** Draws the content and keeps a copy of the frame for the next close. */
private fun ContentDrawScope.drawAndRecord(snapshot: SettingsCardSnapshot) {
    val picture = snapshot.picture ?: Picture().also { snapshot.picture = it }
    drawAndRecord(picture)
}

/** Draws the content and records the same frame into [picture]. */
internal fun ContentDrawScope.drawAndRecord(picture: Picture) {
    drawContent()
    val width = ceil(size.width).toInt()
    val height = ceil(size.height).toInt()
    if (width <= 0 || height <= 0) return
    val recording = Canvas(picture.beginRecording(width, height))
    draw(this, layoutDirection, recording, size) { this@drawAndRecord.drawContent() }
    picture.endRecording()
}

/** Keeps the settled top-level Settings frame when it was reached without the card motion. */
internal fun Modifier.recordSettingsCard(
    snapshot: SettingsCardSnapshot,
    settled: () -> Boolean,
): Modifier = drawWithContent { if (settled()) drawAndRecord(snapshot) else drawContent() }

/**
 * Opening grows the live Settings destination as a card out of the end edge over the themed
 * backplate. Closing never retains the Settings composition: Chats is live at once and only an
 * inert picture of the last settled Settings frame shrinks back into the end edge over it, so no
 * old-route handlers, focus or editable state survive the switch. Chats itself is never captured,
 * because its window privacy flag is released while Settings is shown.
 */
internal fun Modifier.settingsBubbleMotion(
    opening: Boolean,
    progress: () -> Float,
    animating: () -> Boolean,
    backplate: Color,
    snapshot: SettingsCardSnapshot?,
): Modifier =
    if (opening) {
        drawWithContent {
            if (!animating()) {
                if (snapshot != null) drawAndRecord(snapshot) else drawContent()
                return@drawWithContent
            }
            val presence = progress().coerceIn(0f, 1f)
            drawRect(backplate)
            drawSettingsCard(presence, (presence / SETTINGS_CARD_OPEN_FADE_END).coerceIn(0f, 1f)) {
                this@drawWithContent.drawContent()
            }
        }
    } else {
        drawWithContent {
            drawContent()
            val picture = snapshot?.picture
            if (!animating() || picture == null) return@drawWithContent
            val p = progress().coerceIn(0f, 1f)
            drawRect(Color.Black.copy(alpha = SETTINGS_CARD_SCRIM_ALPHA * (1f - p)))
            val fade = ((p - SETTINGS_CARD_CLOSE_FADE_START) / (1f - SETTINGS_CARD_CLOSE_FADE_START)).coerceIn(0f, 1f)
            drawSettingsCard(1f - p, 1f - fade) {
                drawIntoCanvas { it.nativeCanvas.drawPicture(picture) }
            }
        }
    }
