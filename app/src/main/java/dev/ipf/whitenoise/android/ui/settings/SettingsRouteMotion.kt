package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlin.math.roundToInt

/** Navigation hierarchy supplies direction; peer destinations use the forward treatment. */
internal fun settingsRouteDirection(
    previousDepth: Int,
    nextDepth: Int,
): Int = if (nextDepth < previousDepth) -1 else 1

/** Derives nested depth from the same parent relation used by Back. */
internal fun settingsDetailDepth(detail: SettingsDetail?): Int {
    if (detail == null) return 0
    return 1 + settingsDetailDepth(settingsDetailParent(detail))
}

private class SettingsMotionHistory<T>(
    var target: T,
    var depth: Int,
) {
    /** The last route clock and whether it drove the top-level container, for a mid-flight reversal. */
    var progress: Animatable<Float, AnimationVector1D>? = null
    var containerLeg = false

    /** Where top-level Settings last opened from, valid only while the host keeps [originHost]. */
    var origin: Rect? = null
    var originHost: Rect = Rect.Zero
}

/**
 * Slide the opaque destination's draw layer. The old route is disposed immediately: it cannot keep
 * Back handlers, editable state, focus, or protected pixels alive during an exit animation. The
 * only exception is closing top-level Settings, which briefly draws an inert picture of its last
 * frame (see [settingsBubbleMotion]); Chats is never captured. Keeping
 * the content call outside a route key preserves the existing screen/state ownership semantics.
 * First composition (including a direct privacy-sensitive route) is settled, never a delayed reveal.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun <T> SettingsRouteMotion(
    target: T,
    depth: Int,
    ownerKey: Any?,
    suppressMotion: Boolean = false,
    contentVisible: Boolean = true,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    topLevelBubble: Boolean = false,
    cardMorph: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    if (!contentVisible) return
    val parent = LocalSettingsCardMorph.current
    val morph =
        if (cardMorph) {
            remember(ownerKey, parent) { SettingsCardMorph(fullScreen = true, parent = parent) }
        } else {
            null
        }
    val motion = animationsEnabled && !suppressMotion
    val history = remember(ownerKey) { SettingsMotionHistory(target, depth) }
    val previousDepth = remember(ownerKey, target) { history.depth }
    val forward = depth >= previousDepth
    SideEffect {
        history.target = target
        history.depth = depth
    }
    // Chats and top-level Settings are one container transform from the activating control (the
    // account avatar), never a contour morph: Chats has no outlined groups to morph from.
    val topLevelPair = topLevelBubble && morph?.page != null && usesSettingsBubble(previousDepth, depth)
    // Read before the page host consumes the click's measured origin.
    val topLevelOrigin = remember(ownerKey, target) { morph?.page?.pendingOrigin?.takeIf { topLevelPair && forward } }
    val (morphing, morphHost) =
        rememberSettingsCardMorphHost(
            morph,
            target,
            ownerKey,
            motion && !topLevelPair,
            forward,
            captureContent =
                !topLevelBubble || depth > 0,
        )
    Box(
        Modifier.fillMaxSize().then(morphHost).then(
            // The existing route clock owns block contraction and exclusive entry; unmeasured entries settle directly.
            rememberRouteMotionModifier(
                target = target,
                depth = depth,
                ownerKey = ownerKey,
                suppressMotion = suppressMotion || morphing || (morph?.page != null && !topLevelPair),
                animationsEnabled = animationsEnabled,
                topLevelBubble = topLevelBubble,
                origin = topLevelOrigin,
                closeTarget = { morph?.reportedAnchorBounds(SettingsHomeOriginAnchor) },
                snapshot = morph?.routeSnapshot,
                recording = { morph?.recording != false },
            ),
        ),
    ) {
        CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
            content(target)
        }
    }
}

/**
 * A single destination draw layer for route hosts with existing early-return rendering. For the
 * top-level pair, [origin] is the activating control in screen coordinates; closing contracts into
 * [closeTarget] (the live control) or else that remembered origin. [snapshot] is the host's purgeable
 * Settings frame, recorded only while [recording] allows it.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun <T> rememberRouteMotionModifier(
    target: T,
    depth: Int,
    ownerKey: Any?,
    suppressMotion: Boolean = false,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    topLevelBubble: Boolean = false,
    origin: Rect? = null,
    closeTarget: () -> Rect? = { null },
    snapshot: SettingsCardSnapshot? = null,
    recording: () -> Boolean = { true },
): Modifier {
    val history = remember(ownerKey) { SettingsMotionHistory(target, depth) }
    val ownSnapshot = remember(ownerKey) { SettingsCardSnapshot() }
    val frame = snapshot ?: ownSnapshot
    val host = remember(ownerKey) { arrayOf(Rect.Zero) }
    val view = LocalView.current
    val layoutDirection = LocalLayoutDirection.current
    val previousDepth = remember(ownerKey, target) { history.depth }
    val logicalDirection = settingsRouteDirection(previousDepth, depth)
    val bubble = topLevelBubble && usesSettingsBubble(previousDepth, depth)
    val opening = logicalDirection > 0
    val direction = if (layoutDirection == LayoutDirection.Rtl) -logicalDirection else logicalDirection
    val motion = animationsEnabled && !suppressMotion
    val interrupted =
        remember(ownerKey, target) { bubble && history.containerLeg && (history.progress?.value ?: 1f) < 1f }
    val progress =
        remember(ownerKey, target, motion) {
            val changed = motion && history.target != target
            // Reversing the container mid-flight resumes from the presence it is drawing, not from rest.
            val previous = history.progress?.takeIf { interrupted }
            Animatable(if (!changed) 1f else previous?.let { 1f - it.value } ?: 0f)
        }
    // Taken as Chats composes, before its own secure hold purges the host's pictures: the frame shows
    // only unprotected Settings (holds stop recording and purge it) and is drawn under the Chats flag.
    val closingPicture = remember(ownerKey, target) { frame.picture.takeIf { bubble && !opening && recording() } }
    if (bubble && opening && history.progress !== progress) {
        // Keep this open's origin for the close; a moved or resized host invalidates it.
        history.origin = origin
        history.originHost = host[0]
    }
    SideEffect {
        history.target = target
        history.depth = depth
        history.progress = progress
        history.containerLeg = bubble
    }
    LaunchedEffect(progress) {
        runRouteMotion(progress, motion, opening)
        // A kept Settings frame is only valid while top-level Settings stays shown.
        if (depth != 1) frame.picture = null
    }
    val travel = with(LocalDensity.current) { SettingsRouteSlideTravel.toPx() }
    // Draw the themed backplate outside the translated child layer. Moving a whole opaque page
    // otherwise exposes the Activity window behind its vacated edge, including in AMOLED mode.
    val backplate = MaterialTheme.colorScheme.background
    if (bubble) {
        val colors =
            SettingsContainerColors(
                backplate = backplate,
                fill = MaterialTheme.colorScheme.surfaceContainerLow,
                outline = if (isAmoledSurfaceTheme()) MaterialTheme.colorScheme.outline else Color.Unspecified,
            )
        val localOrigin: () -> Rect? = {
            val bounds = host[0]
            val measured =
                if (opening) {
                    origin
                } else {
                    val previousOrigin = history.origin?.takeIf { history.originHost == bounds }
                    if (interrupted) previousOrigin ?: closeTarget() else closeTarget() ?: previousOrigin
                }
            measured?.takeIf { it.visibleIn(bounds) }?.translate(-bounds.topLeft)
        }
        return Modifier
            .onGloballyPositioned { host[0] = it.settingsMorphBounds(view) }
            .settingsTransitionInputGate(opening && motion && progress.value < 1f)
            .settingsBubbleMotion(
                opening = opening,
                progress = { progress.value },
                animating = { motion && progress.value < 1f },
                colors = colors,
                snapshot = frame.takeIf { animationsEnabled },
                closingPicture = closingPicture.takeIf { animationsEnabled },
                origin = localOrigin,
                recording = recording,
            )
    }
    val recorder =
        if (topLevelBubble && depth == 1 && animationsEnabled) {
            // Refresh the close frame after deeper routes return without the container motion.
            Modifier.recordSettingsCard(frame) { progress.value >= 1f && recording() }
        } else {
            Modifier
        }
    return Modifier
        .onGloballyPositioned { host[0] = it.settingsMorphBounds(view) }
        .then(recorder)
        .settingsSlide(motion, { progress.value }, backplate, direction * travel)
}

private suspend fun runRouteMotion(
    progress: Animatable<Float, *>,
    motion: Boolean,
    opening: Boolean,
) {
    if (motion) {
        val millis = if (opening) SETTINGS_BUBBLE_OPEN_MILLIS else SETTINGS_BUBBLE_CLOSE_MILLIS
        val easing = if (opening) SettingsBubbleOpenEasing else SettingsBubbleCloseEasing
        // A resumed reversal covers only the remaining distance, at the same speed.
        val remaining = (millis * (1f - progress.value)).roundToInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = easing))
    } else {
        progress.snapTo(1f)
    }
}

/** Slides the page in from [travel] px while a themed backplate covers the vacated edge. */
private fun Modifier.settingsSlide(
    motion: Boolean,
    progress: () -> Float,
    backplate: Color,
    travel: Float,
): Modifier =
    drawBehind {
        if (motion && progress() < 1f) drawRect(backplate)
    }.graphicsLayer {
        val remaining = if (motion) 1f - progress() else 0f
        translationX = travel * remaining
    }
