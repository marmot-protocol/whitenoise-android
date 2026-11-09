package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.requiresCompletionControls
import dev.ipf.whitenoise.android.ui.conversation.composer.DictationControlFacts
import kotlin.math.roundToInt

internal const val DICTATION_CONTROL_EXIT_MILLIS = 280

/** The capsule stays fully opaque while it starts shrinking, then fades over the remaining travel. */
private const val DICTATION_CONTROL_EXIT_FADE_START = 0.4f

/** The owner seen on the previous applied frame; only used to detect a fresh floating capsule. */
private class PreviousDictationOwner {
    var owner: ConversationDictationControlOwner? = null
}

/** The last floating capsule frame's inputs, painted by the exit; never read as session truth. */
private data class FloatingDictationFrame(
    val state: ConversationDictationState,
    val facts: DictationControlFacts,
)

private class LastFloatingDictation {
    var frame: FloatingDictationFrame? = null
}

/**
 * Renders the floating dictation capsule and, when its session ends (paste, send, cancel or a
 * finished transcript), keeps an inert copy, frozen with its controller facts, for
 * [DICTATION_CONTROL_EXIT_MILLIS] while it shrinks to nothing into its bottom end corner. The
 * reported size shrinks with the drawing, so the footer space it reserves above the composer
 * closes in step instead of jumping at the end. The
 * live and frozen capsule share one call site, so the exit continues the same composition. Owner
 * changes to the composer are not exits: the shared handoff morph owns those. Reduced motion
 * removes the capsule at once. A floating capsule that appears without a launcher or composer
 * morph (for example, a session resumed from its notification) mirrors the exit and grows out of
 * that corner.
 */
@Composable
@Suppress("FunctionNaming", "CyclomaticComplexMethod") // Exit capture and frozen rendering share one owner.
internal fun DictationControlExit(
    owner: ConversationDictationControlOwner,
    state: ConversationDictationState,
    facts: DictationControlFacts,
    motionAllowed: Boolean,
    morphActive: () -> Boolean = { false },
    control: @Composable (state: ConversationDictationState, facts: DictationControlFacts, modifier: Modifier) -> Unit,
) {
    val last = remember { LastFloatingDictation() }
    val previousOwner = remember { PreviousDictationOwner() }
    val entrance = remember { Animatable(0f) }
    var entering by remember { mutableStateOf(false) }
    // A capsule that appears with no launcher or composer to morph out of grows out of the same
    // corner it shrinks into on exit. The first appearing frame is already drawn collapsed.
    val startsEntrance =
        motionAllowed &&
            owner == ConversationDictationControlOwner.Persistent &&
            previousOwner.owner == ConversationDictationControlOwner.Hidden &&
            !morphActive()
    var exiting by remember { mutableStateOf<FloatingDictationFrame?>(null) }
    val progress = remember { Animatable(0f) }
    val sessionEnded =
        motionAllowed &&
            owner == ConversationDictationControlOwner.Hidden &&
            !state.requiresCompletionControls(facts.recoveryHandedToComposer)
    // The first ended frame still draws the capsule; the exit state is published after it.
    val frozen = exiting ?: last.frame?.takeIf { sessionEnded }
    SideEffect {
        when {
            owner != ConversationDictationControlOwner.Hidden -> exiting = null
            sessionEnded && last.frame != null -> exiting = last.frame
            !sessionEnded -> exiting = null
        }
        last.frame =
            FloatingDictationFrame(state, facts).takeIf { owner == ConversationDictationControlOwner.Persistent }
        if (startsEntrance) entering = true
        if (owner != ConversationDictationControlOwner.Persistent || !motionAllowed) entering = false
        previousOwner.owner = owner
    }
    LaunchedEffect(entering) {
        if (entering) {
            try {
                entrance.snapTo(1f)
                entrance.animateTo(0f, tween(DICTATION_CONTROL_EXIT_MILLIS, easing = FastOutSlowInEasing))
            } finally {
                entering = false
            }
        }
    }
    LaunchedEffect(exiting) {
        if (exiting != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DICTATION_CONTROL_EXIT_MILLIS, easing = FastOutSlowInEasing))
            exiting = null
        }
    }
    val live = owner != ConversationDictationControlOwner.Hidden
    val rendered = if (live) FloatingDictationFrame(state, facts) else frozen
    if (rendered != null) {
        val exit =
            when {
                !live -> Modifier.dictationExit { if (exiting == null) 0f else progress.value }
                startsEntrance || entering -> Modifier.dictationShrink { if (entering) entrance.value else 1f }
                else -> Modifier
            }
        control(rendered.state, rendered.facts, exit)
    }
}

/**
 * Shrinks the inert capsule toward its bottom end corner. Placement is absolute at the end edge in
 * both layout directions, so the anchor is the physical bottom-right corner.
 */
private fun Modifier.dictationExit(progress: () -> Float): Modifier =
    dictationShrink(progress)
        .clearAndSetSemantics {}
        .pointerInput(Unit) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }

/** Scales the capsule's layout and drawing toward its bottom end corner; 0 is full size, 1 is gone. */
private fun Modifier.dictationShrink(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val scale = 1f - progress().coerceIn(0f, 1f)
        val width = (placeable.width * scale).roundToInt()
        val height = (placeable.height * scale).roundToInt()
        layout(width, height) { placeable.place(width - placeable.width, height - placeable.height) }
    }.graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(1f, 1f)
        scaleX = 1f - p
        scaleY = 1f - p
        val fade = (p - DICTATION_CONTROL_EXIT_FADE_START) / (1f - DICTATION_CONTROL_EXIT_FADE_START)
        alpha = 1f - fade.coerceIn(0f, 1f)
    }
