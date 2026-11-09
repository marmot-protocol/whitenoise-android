@file:Suppress("FunctionNaming") // Compose surface functions follow UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.graphics.Picture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

internal const val COMPOSER_ACCESSORY_ENTRANCE_MILLIS = 180
internal const val COMPOSER_ACCESSORY_EXIT_MILLIS = 150
private val ComposerAccessoryExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val COMPOSER_ACCESSORY_START_SCALE = 0.96f

/** Bottom-centre origin so the accessory rises out of the composer edge. */
private val ComposerAccessoryOrigin = TransformOrigin(0.5f, 1f)

/**
 * Remembers whether the composer has already been drawn. A reply restored with the draft is part of
 * the first frame and appears without motion; only a reply chosen while the composer is open animates.
 */
internal class ComposerAccessoryEntranceGate {
    var armed: Boolean = false
}

@Composable
internal fun rememberComposerAccessoryEntranceGate(): ComposerAccessoryEntranceGate {
    val gate = remember { ComposerAccessoryEntranceGate() }
    SideEffect { gate.armed = true }
    return gate
}

/**
 * Entrance progress for one accessory [key]. Only the drawn layer moves, so the composer height and
 * the timeline anchor that follows it change once, exactly as before.
 */
@Composable
internal fun rememberComposerAccessoryEntrance(
    key: Any?,
    gate: ComposerAccessoryEntranceGate,
    motionEnabled: Boolean,
): () -> Float {
    val progress = remember(key) { Animatable(if (motionEnabled && gate.armed) 0f else 1f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(COMPOSER_ACCESSORY_ENTRANCE_MILLIS, easing = FastOutSlowInEasing))
    }
    return { progress.value }
}

/** Fades the accessory in while it rises 8dp and grows from its bottom edge into place. */
internal fun Modifier.composerAccessoryEntrance(progress: () -> Float): Modifier =
    graphicsLayer {
        val value = progress().coerceIn(0f, 1f)
        alpha = value
        val scale = COMPOSER_ACCESSORY_START_SCALE + (1f - COMPOSER_ACCESSORY_START_SCALE) * value
        scaleX = scale
        scaleY = scale
        translationY = (1f - value) * 8.dp.toPx()
        transformOrigin = ComposerAccessoryOrigin
    }

/**
 * Keeps the last drawn frame of an accessory (the reply preview) so it can leave the way it came in.
 * The live accessory is removed at once, so its click targets, semantics and state are gone; only
 * this inert picture plays the mirrored exit while the slot keeps its height, and the composer then
 * shrinks once, exactly as it grew once on entrance.
 */
@Stable
internal class ComposerAccessoryExit {
    internal var picture: Picture? = null
    internal var widthPx: Int = 0
    internal var heightPx: Int = 0
    internal val progress = Animatable(1f)
    var exiting by mutableStateOf(false)
        internal set

    internal fun clear() {
        exiting = false
        picture = null
        heightPx = 0
    }
}

@Composable
internal fun rememberComposerAccessoryExit(
    present: Boolean,
    motionEnabled: Boolean,
): ComposerAccessoryExit {
    val exit = remember { ComposerAccessoryExit() }
    LaunchedEffect(exit, present, motionEnabled) {
        if (present) {
            exit.exiting = false
            return@LaunchedEffect
        }
        val captured = exit.picture != null && exit.heightPx > 0
        if (!motionEnabled || !captured) {
            exit.clear()
            return@LaunchedEffect
        }
        exit.exiting = true
        try {
            exit.progress.snapTo(1f)
            exit.progress.animateTo(0f, tween(COMPOSER_ACCESSORY_EXIT_MILLIS, easing = ComposerAccessoryExitEasing))
        } finally {
            exit.clear()
        }
    }
    return exit
}

/** Records the accessory's latest frame into [exit] while drawing it normally. */
internal fun Modifier.composerAccessoryExitSource(exit: ComposerAccessoryExit): Modifier =
    drawWithContent {
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width > 0 && height > 0) {
            val picture = exit.picture ?: Picture().also { exit.picture = it }
            val recording = Canvas(picture.beginRecording(width, height))
            try {
                draw(this, layoutDirection, recording, size) { this@drawWithContent.drawContent() }
            } finally {
                picture.endRecording()
            }
            exit.widthPx = width
            exit.heightPx = height
        }
        drawContent()
    }

/** Draws the exiting accessory picture: the entrance in reverse (fade, shrink, sink 8dp). */
internal fun Modifier.composerAccessoryExitGhost(exit: ComposerAccessoryExit): Modifier =
    composerAccessoryEntrance { exit.progress.value }
        .drawBehind {
            val picture = exit.picture ?: return@drawBehind
            drawIntoCanvas { it.nativeCanvas.drawPicture(picture) }
        }
