package dev.ipf.whitenoise.android.ui.conversation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.LocalNativePageBody
import dev.ipf.whitenoise.android.ui.common.LocalScaffoldBodyInputBlocked
import dev.ipf.whitenoise.android.ui.common.NativePageBody
import dev.ipf.whitenoise.android.ui.common.nativePageBodyPresence
import dev.ipf.whitenoise.android.ui.navigation.ConversationRouteTransition
import dev.ipf.whitenoise.android.ui.navigation.rememberConversationRouteTransition
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleCard
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleColors
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleGeometry
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleLanding
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleSource
import dev.ipf.whitenoise.android.ui.settings.SettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsContour
import dev.ipf.whitenoise.android.ui.settings.drawAtAlpha
import dev.ipf.whitenoise.android.ui.settings.drawSettingsArrival
import dev.ipf.whitenoise.android.ui.settings.settingsBubbleShape
import dev.ipf.whitenoise.android.ui.settings.settingsMorphAnchorOutline
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds
import dev.ipf.whitenoise.android.ui.settings.settingsTransitionInputGate

internal val ComposerResizeHandleWidth = 32.dp
internal val ComposerResizeHandleThickness = 4.dp

internal enum class ConversationSettingsRoute { Details, Composer }

internal val LocalConversationSettingsMotion = staticCompositionLocalOf<ConversationSettingsMotion?> { null }

/** One reversible route clock. The outgoing transcript is live and its viewport fades at its native position. */
internal class ConversationSettingsMotion(
    private val transition: ConversationRouteTransition<ConversationSettingsRoute?>,
    val morph: SettingsCardMorph,
    private val suppressed: Boolean,
) {
    var host by mutableStateOf(Rect.Zero)
    private var launch: SettingsContour? = null
    private var launchedWithGrip = false
    private var launchImeBottom: Int? = null
    private var composerHasGrip = false
    private var composerPreview by mutableStateOf<List<SettingsBubbleCard>>(emptyList())
    private var preparedContour: SettingsContour? = null
    private var preparedSource: SettingsBubbleSource? = null
    private val landing = SettingsBubbleLanding()

    val route by derivedStateOf {
        transition.targetState ?: transition.retainedStates.lastOrNull { it != null }.takeUnless { suppressed }
    }
    val keepTranscript by derivedStateOf {
        transition.targetState == null || (!suppressed && transition.isRunning)
    }
    val transcriptActive get() = transition.targetState == null
    val progress get() =
        if (!suppressed) {
            transition.visibility.value
        } else if (transcriptActive) {
            0f
        } else {
            1f
        }
    val bodyAlpha get() = (1f - progress).coerceIn(0f, 1f)

    /** Capture the real source before the activation dismisses the keyboard or popup. */
    fun launchFromComposer(imeBottom: Int? = null) {
        if (!suppressed && !transition.isRunning) {
            launch = morph.anchorContour
            launchedWithGrip = composerHasGrip
            launchImeBottom = imeBottom
        }
    }

    val outgoingImeBottom get() = launchImeBottom.takeUnless { transcriptActive }

    fun clearLaunchWhenSettled() {
        if (transcriptActive && !transition.isRunning) {
            launch = null
            launchImeBottom = null
            launchedWithGrip = false
        }
    }

    fun reportComposerGrip(visible: Boolean) {
        composerHasGrip = visible
    }

    val movingGripAlpha: Float
        get() =
            if (!launchedWithGrip) {
                0f
            } else if (route == ConversationSettingsRoute.Composer) {
                1f
            } else {
                nativePageBodyPresence(progress, destination = false)
            }

    private val drawsMovingOutline get() = route != null && progress > 0f && progress < 1f && bubble() != null

    fun drawsNativeComposer() = !drawsMovingOutline

    fun reportComposerPreview(card: SettingsBubbleCard?) {
        composerPreview = listOfNotNull(card)
    }

    fun bubble(): SettingsBubbleGeometry? {
        // Back during entry keeps the same origin. A settled page may return to a newly placed
        // composer after the IME closed; its live bounds take precedence when available.
        val native = morph.anchorContour.takeIf { transcriptActive && transition.currentState != null }
        val contour = native ?: launch
        if (preparedContour !== contour) {
            preparedContour = contour
            preparedSource = contour?.bubbleSource()
        }
        val cards = if (route == ConversationSettingsRoute.Composer) composerPreview else morph.page?.bubbleCards().orEmpty()
        return landing.measure(host, cards, preparedSource)
    }

    private fun SettingsContour.bubbleSource() =
        SettingsBubbleSource(
            bounds,
            ControlMorphSurface(
                fill.takeIf { it.isSpecified } ?: Color.Transparent,
                color,
                corners.topLeftCornerRadius.x,
                width,
            ),
        )
}

@Composable
internal fun rememberConversationSettingsMotion(
    target: ConversationSettingsRoute?,
    owner: Any,
    morph: SettingsCardMorph,
    animate: Boolean,
): ConversationSettingsMotion {
    val transition = rememberConversationRouteTransition(target, suppressMotion = !animate, ownerKey = owner)
    val state = remember(transition, morph, animate) { ConversationSettingsMotion(transition, morph, !animate) }
    val running = transition.isRunning
    SideEffect { if (!running) state.clearLaunchWhenSettled() }
    return state
}

/** Keep live content beneath its child without retaining input, focus, semantics or Back ownership. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationSettingsLayer(
    modifier: Modifier,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    if (!active && focused) SideEffect { focusManager.clearFocus(force = true) }
    val back = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inertBack =
        remember(lifecycleOwner) {
            object : OnBackPressedDispatcherOwner {
                override val onBackPressedDispatcher = OnBackPressedDispatcher()
                override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            }
        }
    Box(
        modifier
            .settingsTransitionInputGate(!active)
            .focusProperties { if (!active) onEnter = { cancelFocusChange() } }
            .onFocusChanged { focused = it.hasFocus }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides (back.takeIf { active } ?: inertBack)) {
            content()
        }
    }
}

/** Native details share the composer outline painter while keeping their nested routes live. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationSettingsPage(
    motion: ConversationSettingsMotion,
    modifier: Modifier,
    content: @Composable (ConversationSettingsRoute) -> Unit,
) {
    val route = motion.route ?: return
    val scheme = MaterialTheme.colorScheme
    val colors = SettingsBubbleColors(scheme.surfaceContainerLow, scheme.outlineVariant)
    val body = remember(motion) { NativePageBody() }
    val view = LocalView.current
    val inputBlocked = remember(motion) { derivedStateOf { motion.progress < 1f } }
    val presentation =
        Modifier
            .fillMaxSize()
            .zIndex(1f)
            .onGloballyPositioned { motion.host = it.settingsMorphBounds(view) }
            .drawWithContent { drawConversationSettings(motion, colors, body) }
    ConversationSettingsLayer(presentation.then(modifier), active = !motion.transcriptActive) {
        CompositionLocalProvider(
            LocalSettingsCardMorph provides motion.morph,
            LocalConversationSettingsMotion provides motion,
            LocalNativePageBody provides body,
            LocalScaffoldBodyInputBlocked provides inputBlocked,
        ) { content(route) }
    }
}

/** The native toolbar stays visible and can reverse the entry while the body is still moving. */
private fun ContentDrawScope.drawConversationSettings(
    motion: ConversationSettingsMotion,
    colors: SettingsBubbleColors,
    body: NativePageBody,
) {
    val progress = motion.progress
    if (progress >= 1f) {
        drawContent()
        return
    }
    if (progress <= 0f) return
    val header = body.nativeBounds?.top
    val bodyTop = header?.let { (it - motion.host.top).coerceIn(0f, size.height) } ?: 0f
    val bubble = motion.bubble()
    val pageAlpha = if (bubble == null) progress else nativePageBodyPresence(progress, destination = true)
    if (bodyTop > 0f && pageAlpha > 0f) {
        clipRect(bottom = bodyTop) {
            drawAtAlpha(Rect(0f, 0f, size.width, bodyTop), pageAlpha) { this@drawConversationSettings.drawContent() }
        }
    }
    clipRect(top = bodyTop) {
        drawSettingsArrival(bubble, progress, colors, ink = 1f, pageAlpha = pageAlpha) {
            this@drawConversationSettings.drawContent()
        }
        if (bubble != null && motion.movingGripAlpha > 0f) {
            val shape = settingsBubbleShape(bubble, progress)
            val width = ComposerResizeHandleWidth.roundToPx().toFloat()
            val height = ComposerResizeHandleThickness.roundToPx().toFloat()
            val top = shape.top + ((1.dp - ComposerResizeHandleThickness) / 2).roundToPx()
            drawRoundRect(
                color = colors.edge,
                topLeft = Offset((shape.left + shape.right - width) / 2, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(height / 2),
                alpha = motion.movingGripAlpha,
            )
        }
    }
}

internal fun Modifier.conversationSettingsBody(motion: ConversationSettingsMotion): Modifier =
    graphicsLayer {
        alpha = motion.bodyAlpha
    }

/** The contour carries the composer's outline while the native input remains in its own layer. */
@Composable
internal fun Modifier.conversationSettingsOutline(
    shape: Shape,
    fill: Color,
    border: Color,
    width: Dp,
    hasGrip: Boolean = false,
): Modifier {
    val source = settingsMorphAnchorOutline(ConversationMorphAnchor.Composer, shape, border, width, fill)
    val motion = LocalConversationSettingsMotion.current ?: return source
    SideEffect { motion.reportComposerGrip(hasGrip) }
    return source.drawWithContent { if (motion.drawsNativeComposer()) drawContent() }
}

/** One outline owns the resize grip while it moves between the two native surfaces. */
@Composable
internal fun Modifier.conversationSettingsGrip(): Modifier {
    val motion = LocalConversationSettingsMotion.current ?: return this
    return drawWithContent { if (motion.drawsNativeComposer()) drawContent() }
}
