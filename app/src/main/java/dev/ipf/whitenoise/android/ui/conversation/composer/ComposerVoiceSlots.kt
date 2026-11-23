package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

/** What the previous composition showed, read in the next one to tell a cancel or discard from a hand-off. */
private class VoiceSlotMemory {
    var phase: VoiceComposerPhase = VoiceComposerPhase.Idle
    var clip: VoiceReviewClip? = null
    var sending = false
}

/** One overlay's exit: its close-tween progress, and whether it has finished. */
private class VoiceSlotExit(
    val progress: Animatable<Float, *>,
) {
    var done by mutableStateOf(false)
}

/**
 * Runs one exit per non-null [key] and returns it until it ends. Under "Remove animations" there is no exit, so the
 * overlay leaves in its first frame.
 */
@Composable
private fun rememberVoiceSlotExit(key: Any?): VoiceSlotExit? {
    if (!rememberSystemAnimationsEnabled()) return null
    val spec = voiceTween<Float>(open = false)
    val exit = remember(key) { VoiceSlotExit(Animatable(0f)) }
    LaunchedEffect(exit) {
        if (key != null) {
            exit.progress.animateTo(1f, spec)
            exit.done = true
        }
    }
    return exit.takeIf { key != null && !it.done }
}

/**
 * The review overlay slot for the composer pill, bound to the clip that was reviewed. A take the user discarded fades
 * and slides toward the cancel side; a take handed to its bubble at seed leaves in the same frame.
 */
@Composable
internal fun rememberVoiceReviewSlot(
    review: VoiceRecordingReview?,
    clip: VoiceReviewClip?,
    recorder: VoiceRecordingController?,
): (@Composable () -> Unit)? {
    val memory = remember { VoiceSlotMemory() }
    val leavingClip = remember(clip) { memory.clip.takeIf { clip == null && !memory.sending } }
    SideEffect {
        memory.clip = clip
        memory.sending = review?.isSending == true
    }
    val exit = rememberVoiceSlotExit(leavingClip?.takeIf { recorder?.isRecording != true })
    val shown = clip ?: leavingClip?.takeIf { exit != null }
    val side = cancelSide()
    return shown?.let { reviewedClip ->
        {
            Box(
                Modifier
                    .graphicsLayer {
                        val leaving = if (clip == null) exit?.progress?.value ?: 1f else 0f
                        alpha = 1f - leaving
                        translationX = leaving * side * VoiceExitSlide.toPx()
                    }.then(if (clip == null) Modifier.swallowPointers(reviewedClip) else Modifier),
            ) {
                VoiceReviewComposer(
                    checkNotNull(review),
                    reviewedClip,
                    recorder,
                )
            }
        }
    }
}

/**
 * The recording strip slot. It leaves [actionsWidth] free for the trailing actions, on the start side when a
 * locked take's actions sit there, and swallows every pointer so nothing under the strip reacts. After a cancel the
 * strip slides out toward the cancel side while the composer returns underneath.
 */
@Composable
internal fun rememberVoiceRecordingSlot(
    recorder: VoiceRecordingController?,
    phase: VoiceComposerPhase,
    actionsWidth: Dp,
    actionsAtStart: Boolean,
    expandedControlLayout: Boolean,
): (@Composable BoxScope.() -> Unit)? {
    val memory = remember { VoiceSlotMemory() }
    // A fresh token per cancel, so each cancelled take gets its own exit.
    val cancelled = remember(phase) { Any().takeIf { phase == VoiceComposerPhase.Idle && memory.phase.showsStrip } }
    SideEffect { memory.phase = phase }
    val exit = rememberVoiceSlotExit(cancelled?.takeIf { recorder != null })
    val shown = recorder?.takeIf { phase.showsStrip || exit != null }
    return shown?.let { recordingController ->
        {
            RecordingStripLeading(
                controller = recordingController,
                exit = { if (phase.showsStrip) 0f else exit?.progress?.value ?: 1f },
                modifier =
                    Modifier
                        .matchParentSize()
                        .padding(
                            start =
                                if (actionsAtStart) {
                                    actionsWidth + 4.dp
                                } else {
                                    0.dp
                                },
                            end =
                                if (actionsAtStart) {
                                    0.dp
                                } else {
                                    actionsWidth +
                                        if (expandedControlLayout) 4.dp else 12.dp
                                },
                        ).swallowPointers(recordingController),
            )
        }
    }
}

/** How far review content slides toward the cancel side as a discard leaves. */
private val VoiceExitSlide = 12.dp

/** The physical side of cancel for review: the start edge, as for the default microphone placement. */
@Composable
private fun cancelSide(): Float = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f

/** Consumes every pointer so nothing under a voice overlay reacts. */
private fun Modifier.swallowPointers(key: Any): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            }
        }
    }
