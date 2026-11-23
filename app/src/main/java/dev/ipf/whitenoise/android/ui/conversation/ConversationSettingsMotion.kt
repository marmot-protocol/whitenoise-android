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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.LocalNativePageBody
import dev.ipf.whitenoise.android.ui.common.LocalScaffoldBodyInputBlocked
import dev.ipf.whitenoise.android.ui.common.NativePageBody
import dev.ipf.whitenoise.android.ui.navigation.ConversationRouteTransition
import dev.ipf.whitenoise.android.ui.navigation.rememberConversationRouteTransition
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleColors
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleGeometry
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleLanding
import dev.ipf.whitenoise.android.ui.settings.SettingsBubbleSource
import dev.ipf.whitenoise.android.ui.settings.SettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsContour
import dev.ipf.whitenoise.android.ui.settings.drawSettingsArrival
import dev.ipf.whitenoise.android.ui.settings.settingsMorphAnchorOutline
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds
import dev.ipf.whitenoise.android.ui.settings.settingsTransitionInputGate

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
    private var launchHost = Rect.Zero
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

    /** Retire the launched geometry when the receiving host changes size or position. */
    fun rememberLaunch(
        contour: SettingsContour?,
        bounds: Rect,
    ) {
        if (transition.targetState == null && !transition.isRunning) {
            launch = contour
            launchHost = bounds
        }
    }

    fun bubble(): SettingsBubbleGeometry? {
        val native = morph.anchorContour.takeIf { transcriptActive }
        val contour = native ?: launch.takeIf { launchHost == host }
        if (preparedContour !== contour) {
            preparedContour = contour
            preparedSource = contour?.bubbleSource()
        }
        return landing.measure(host, morph.page?.bubbleCards().orEmpty(), preparedSource)
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
    // A launched outline stays at the pre-IME position until the outgoing leg ends.
    val contour = morph.anchorContour
    val bounds = state.host
    SideEffect { state.rememberLaunch(contour, bounds) }
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
    val inputBlocked = remember(motion) { derivedStateOf { motion.progress < 1f } }
    val presentation =
        Modifier.fillMaxSize().zIndex(1f).conversationSettingsHost(motion).drawWithContent {
            drawConversationSettings(motion, colors, body)
        }
    ConversationSettingsLayer(presentation.then(modifier), active = !motion.transcriptActive) {
        CompositionLocalProvider(
            LocalSettingsCardMorph provides motion.morph,
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
    val header = body.nativeBounds?.takeUnless { motion.transcriptActive }?.top
    val bodyTop = header?.let { (it - motion.host.top).coerceIn(0f, size.height) } ?: 0f
    if (bodyTop > 0f) clipRect(bottom = bodyTop) { this@drawConversationSettings.drawContent() }
    clipRect(top = bodyTop) {
        drawSettingsArrival(motion.bubble(), progress, colors, ink = 1f) { this@drawConversationSettings.drawContent() }
    }
}

@Composable
internal fun Modifier.conversationSettingsHost(motion: ConversationSettingsMotion): Modifier {
    val view = LocalView.current
    return onGloballyPositioned { motion.host = it.settingsMorphBounds(view) }
}

internal fun Modifier.conversationSettingsBody(motion: ConversationSettingsMotion): Modifier =
    graphicsLayer { alpha = motion.bodyAlpha }

/** The contour carries the composer's outline while the native input remains in its own layer. */
@Composable
internal fun Modifier.conversationSettingsOutline(
    shape: Shape,
    fill: Color,
    border: Color,
    width: Dp,
): Modifier {
    val source = settingsMorphAnchorOutline(ConversationMorphAnchor.Composer, shape, border, width, fill)
    val motion = LocalConversationSettingsMotion.current ?: return source
    return source.drawWithContent {
        if (motion.route == null || motion.progress >= 1f || motion.bubble() == null) drawContent()
    }
}
