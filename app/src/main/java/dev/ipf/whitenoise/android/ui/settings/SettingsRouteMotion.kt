package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
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

    /** Whether the last top-level leg was a close that drew no Settings picture. */
    var pictureless = false

    /** The last top-level leg's direction and the Settings ink it continued from, for the next reversal. */
    var opening = true
    var resume: SettingsBubbleResume? = null
}

/**
 * Slide the opaque destination's draw layer. The old route is disposed immediately: it cannot keep
 * Back handlers, editable state, focus, or protected pixels alive during an exit animation. The
 * exceptions are the top-level pair: closing Settings briefly draws an inert picture of its last
 * frame (see [settingsBubbleMotion]), and opening it keeps Chats composed, live but inert (no input,
 * semantics or Back), beneath the arriving page until the open settles. Chats is never
 * captured. Other hosts keep the content call outside a route key, which preserves the existing
 * screen/state ownership semantics. First composition (including a direct privacy-sensitive route)
 * is settled, never a delayed reveal.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    val previousTarget = remember(ownerKey, target) { history.target }
    val forward = depth >= previousDepth
    SideEffect {
        history.target = target
        history.depth = depth
    }
    // Chats and top-level Settings are one outline bubble between the activating control (the account
    // avatar) and the nearest Settings card, never a multi-group contour morph.
    val topLevelPair = topLevelBubble && morph?.page != null && usesSettingsBubble(previousDepth, depth)
    // Read before the page host consumes the click's measured origin and clears the leaving cards.
    val topLevelOrigin = remember(ownerKey, target) { morph?.page?.pendingOrigin?.takeIf { topLevelPair && forward } }
    val leavingCards = remember(ownerKey, target) { morph?.page?.bubbleCards().orEmpty() }
    // Chats stays live beneath the arriving Settings page, until the open settles.
    val opened = remember(ownerKey, target) { mutableStateOf(false) }
    val animatedOpen = topLevelBubble && motion && forward
    val underlay = previousTarget.takeIf { animatedOpen && it != target && usesSettingsBubble(previousDepth, depth) }
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
    val hostModifier =
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
                container =
                    SettingsContainerRouteHooks(
                        onSettled = { opened.value = true },
                        cards = { if (forward) morph?.page?.bubbleCards().orEmpty() else leavingCards },
                    ),
            ),
        )
    if (!topLevelBubble) {
        Box(hostModifier) {
            CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                content(target)
            }
        }
        return
    }
    SettingsTopLevelRouteLayers(
        target = target,
        underlay = underlay?.takeUnless { opened.value },
        hostModifier = hostModifier,
        morph = morph,
        content = content,
    )
}

/**
 * The top-level pair's route layers, each keyed by its route so Chats keeps its composition while it
 * stays beneath the opening Settings page. The [underlay] draws live but takes no pointer, key or
 * IME input, semantics, focus or Back: focus held inside it is cleared (closing the keyboard), focus
 * cannot enter it, and its handlers see an inert dispatcher until it is the [target] again.
 */
@Composable
@Suppress("FunctionNaming")
private fun <T> SettingsTopLevelRouteLayers(
    target: T,
    underlay: T?,
    hostModifier: Modifier,
    morph: SettingsCardMorph?,
    content: @Composable (T) -> Unit,
) {
    val back = LocalOnBackPressedDispatcherOwner.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inertBack =
        remember(lifecycleOwner) {
            object : OnBackPressedDispatcherOwner {
                override val onBackPressedDispatcher = OnBackPressedDispatcher()
                override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            }
        }
    val layers = if (underlay != null) listOf(underlay, target) else listOf(target)
    Box(Modifier.fillMaxSize()) {
        layers.forEach { layer ->
            key(layer) {
                val live = layer == target
                val focused = remember { mutableStateOf(false) }
                if (!live && focused.value) SideEffect { focusManager.clearFocus(force = true) }
                val layerModifier = if (live) hostModifier else Modifier.fillMaxSize().settingsTransitionInputGate(true)
                Box(layerModifier.settingsRouteFocusLayer(live) { focused.value = it }) {
                    CompositionLocalProvider(
                        LocalSettingsCardMorph provides morph,
                        LocalOnBackPressedDispatcherOwner provides (back?.takeIf { live } ?: inertBack),
                    ) {
                        content(layer)
                    }
                }
            }
        }
    }
}

/**
 * How the top-level pair meets its routes: [onSettled] once an open finishes, and the Settings
 * [cards] (screen coordinates) the bubble may land on, live while opening and as last reported
 * when closing.
 */
internal class SettingsContainerRouteHooks(
    val onSettled: () -> Unit = {},
    val cards: () -> List<SettingsBubbleCard> = { emptyList() },
)

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
    container: SettingsContainerRouteHooks = SettingsContainerRouteHooks(),
): Modifier {
    val hooks by rememberUpdatedState(container)
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
            // Reversing the bubble mid-flight resumes from the presence it is drawing, not from rest.
            val previous = history.progress?.takeIf { interrupted }
            Animatable(if (!changed) 1f else previous?.let { 1f - it.value } ?: 0f)
        }
    // Taken as Chats composes, before its own secure hold purges the host's pictures: the frame shows
    // only unprotected Settings (holds stop recording and purge it) and is drawn under the Chats flag.
    // Only an animated close keeps it, and only until that close settles.
    val closingPicture =
        remember(ownerKey, target, motion) {
            arrayOf(frame.picture.takeIf { bubble && !opening && motion && animationsEnabled && recording() })
        }
    // A reversal continues the Settings ink drawn at the turn: none after a close that drew no picture.
    val resume =
        remember(ownerKey, target, motion) {
            history.progress?.takeIf { bubble && interrupted && motion }?.let { old ->
                val presence = if (history.opening) old.value else 1f - old.value
                val ink = if (history.pictureless) 0f else settingsBubbleInk(presence, history.opening, history.resume)
                SettingsBubbleResume(presence, ink)
            }
        }
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
        history.pictureless = bubble && !opening && closingPicture[0] == null
        history.opening = opening
        history.resume = resume
    }
    LaunchedEffect(progress) {
        runRouteMotion(progress, motion, opening)
        closingPicture[0] = null
        hooks.onSettled()
        // A kept Settings frame is only valid while top-level Settings stays shown.
        if (depth != 1) frame.picture = null
    }
    val travel = with(LocalDensity.current) { SettingsRouteSlideTravel.toPx() }
    // Draw the themed backplate outside the translated child layer. Moving a whole opaque page
    // otherwise exposes the Activity window behind its vacated edge, including in AMOLED mode.
    val backplate = MaterialTheme.colorScheme.background
    if (bubble) {
        // Settings pages rest on surfaceContainerLow; the bubble's backplate is that page surface.
        val scheme = MaterialTheme.colorScheme
        val colors = SettingsBubbleColors(scheme.surfaceContainerLow, scheme.outlineVariant)
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
                closingPicture = { closingPicture[0] },
                geometry = {
                    val bounds = host[0]
                    localOrigin()?.let { start ->
                        nearestSettingsBubbleCard(hooks.cards(), start.translate(bounds.topLeft), bounds)?.let {
                            SettingsBubbleGeometry(start, it.translate(-bounds.topLeft))
                        }
                    }
                },
                recording = recording,
                resume = resume,
            )
    }
    val recorder =
        if (topLevelBubble && depth == 1 && animationsEnabled) {
            // Refresh the close frame after deeper routes return without the bubble motion.
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

/** Groups a route layer's focus, reports whether it holds focus, and keeps focus out of it unless [live]. */
private fun Modifier.settingsRouteFocusLayer(
    live: Boolean,
    onFocus: (Boolean) -> Unit,
): Modifier =
    focusProperties { if (!live) onEnter = { cancelFocusChange() } }
        .onFocusChanged { onFocus(it.hasFocus) }
        .focusGroup()
