@file:Suppress("FunctionNaming") // Compose surface functions follow UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerCellTextMetrics
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled
import kotlin.math.PI
import kotlin.math.sin

internal const val LOCAL_REACTION_MOTION_MILLIS = 220
internal val LocalRowReactionMotion = compositionLocalOf<LocalReactionMotion?> { null }

internal data class LocalReactionEffect(
    val sequence: Long,
    val emoji: String,
    val origin: Rect?,
)

/** One mounted row's decorative local intent. Counts and mutation state remain in the controller. */
@Stable
internal class LocalReactionMotion {
    var effect: LocalReactionEffect? by mutableStateOf(null)
        private set
    val progress = Animatable(1f)
    val targets = mutableStateMapOf<String, Rect>()
    var viewport: Rect? by mutableStateOf(null)
    var readViewport: (() -> Rect?)? = null
    var origin: Offset by mutableStateOf(Offset.Zero)
    var enabled: Boolean by mutableStateOf(true)
    private var mounted = true
    private var sequence = 0L
    private var choice: Pair<String, Rect>? = null

    fun choose(
        emoji: String,
        bounds: Rect,
    ) {
        choice = if (mounted && enabled && !bounds.isEmpty) emoji to bounds else null
    }

    fun takeOrigin(emoji: String): Rect? = choice?.takeIf { it.first == emoji }?.second.also { choice = null }

    fun canFly(
        source: Rect,
        target: Rect,
    ): Boolean =
        (readViewport?.invoke() ?: viewport)?.let { bounds ->
            listOf(source, target).all {
                val horizontal = it.left >= bounds.left && it.right <= bounds.right
                val vertical = it.top >= bounds.top && it.bottom <= bounds.bottom
                horizontal && vertical
            }
        } ?: true

    fun accept(
        emoji: String,
        added: Boolean,
        source: Rect? = null,
    ) {
        val validAdd = added && emoji.length <= MAX_REACTION_GLYPH_LENGTH
        effect =
            if (mounted && enabled && validAdd) {
                LocalReactionEffect(++sequence, emoji, source?.takeIf { !it.isEmpty && !emoji.startsWith(":") })
            } else {
                null
            }
    }

    fun settle(expected: LocalReactionEffect? = effect) {
        if (effect == expected) effect = null
        choice = null
    }

    fun dispose() {
        mounted = false
        readViewport = null
        settle()
        targets.clear()
    }
}

@Composable
internal fun rememberLocalReactionMotion(
    owner: Any?,
    enabled: Boolean? = null,
): LocalReactionMotion {
    LocalRowReactionMotion.current?.let { return it }
    val motionEnabled = enabled ?: rememberConversationMotionEnabled()
    // A suppressed owner is retired, so a delayed callback cannot revive it after re-enabling motion.
    val state = remember(owner, motionEnabled) { LocalReactionMotion() }
    state.enabled = motionEnabled
    DisposableEffect(state) { onDispose { state.dispose() } }
    val effect = state.effect
    LaunchedEffect(state, effect, motionEnabled) {
        if (!motionEnabled || effect == null) {
            state.settle()
            state.progress.snapTo(1f)
        } else {
            state.progress.snapTo(0f)
            state.progress.animateTo(1f, tween(LOCAL_REACTION_MOTION_MILLIS, easing = FastOutSlowInEasing))
            state.settle(effect)
        }
    }
    return state
}

/** Popup and transcript windows have different origins; both endpoints use screen coordinates. */
@Composable
internal fun Modifier.reactionSourceBounds(
    requireFullyVisible: Boolean = true,
    enabled: Boolean = true,
    onBounds: (Rect) -> Unit,
): Modifier {
    if (!enabled) return this
    val view = LocalView.current
    val shared = LocalReactionWindowCoordinates.current?.takeIf { it.view === view }
    val own = remember(view) { ReactionWindowCoordinates(view) }
    return onGloballyPositioned { coordinates ->
        val offset = shared?.offset ?: own.also { it.update() }.offset
        val full = coordinates.boundsInWindow(clipBounds = false)
        val visible = coordinates.boundsInWindow()
        onBounds(
            if (requireFullyVisible && full != visible) {
                Rect.Zero
            } else {
                full.translate(offset)
            },
        )
    }
}

/** No hit target or semantics are added. The canonical chip remains the only accessible reaction. */
@Composable
internal fun Modifier.localReactionOverlay(state: LocalReactionMotion): Modifier {
    if (LocalRowReactionMotion.current === state) return this
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val effect = state.effect
    val density = LocalDensity.current
    val sourceCell = with(density) { (effect?.origin?.height ?: 28.dp.toPx()).toDp() }
    val metrics = emojiPickerCellTextMetrics(sourceCell, MaterialTheme.typography.headlineMedium, density.fontScale)
    val layout =
        effect?.let {
            measurer.measure(
                it.emoji,
                MaterialTheme.typography.headlineMedium.copy(
                    fontSize = metrics.first,
                    lineHeight = metrics.second,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    val targetCellPx = with(density) { 14.dp.toPx() }
    val owner =
        if (effect?.origin != null) {
            semantics { if (state.hasVisibleFlight(effect)) testTag = "conversation.reaction.motion.active" }
        } else {
            this
        }
    return owner
        .reactionSourceBounds(requireFullyVisible = false, enabled = effect?.origin != null) {
            state.origin = it.topLeft
        }.drawWithContent {
            drawContent()
            val current = state.effect
            if (!state.enabled || current != effect) return@drawWithContent
            val source = current?.origin ?: return@drawWithContent
            val target = state.targets[current.emoji] ?: return@drawWithContent
            val glyph = layout ?: return@drawWithContent
            if (!state.canFly(source, target)) return@drawWithContent
            val p = state.progress.value
            val center = source.center * (1f - p) + target.center * p - state.origin
            val glyphScale = 1f + (targetCellPx / source.height.coerceAtLeast(1f) - 1f) * p
            scale(glyphScale, pivot = center) {
                drawText(
                    glyph,
                    topLeft = center - Offset(glyph.size.width / 2f, glyph.size.height / 2f),
                    alpha = (1f - p).coerceIn(0f, 1f),
                )
            }
        }
}

/** A selected local add pulses once; refreshes and remote count changes cannot start it. */
@Composable
internal fun Modifier.localReactionTarget(
    state: LocalReactionMotion?,
    emoji: String?,
    selected: Boolean,
    allowHostClipping: Boolean = false,
): Modifier {
    if (state == null || emoji == null) return this
    val measuring = selected && state.enabled && state.effect?.emoji == emoji
    DisposableEffect(state, emoji, measuring) { onDispose { state.targets.remove(emoji) } }
    return reactionSourceBounds(requireFullyVisible = !allowHostClipping, enabled = measuring) {
        if (selected && !it.isEmpty) state.targets[emoji] = it else state.targets.remove(emoji)
    }.graphicsLayer {
        val active = state.enabled && selected && state.effect?.emoji == emoji
        val p = if (active) state.progress.value else 1f
        val scale = 1f + 0.12f * sin(PI.toFloat() * p)
        scaleX = scale
        scaleY = scale
    }
}

private const val MAX_REACTION_GLYPH_LENGTH = 32

/** Raise the whole lazy item, so a flying glyph cannot disappear behind a later-painted message. */
@Composable
internal fun ReactionMotionRow(
    state: LocalReactionMotion,
    modifier: Modifier = Modifier,
    viewportBoundsInWindow: () -> Rect? = { null },
    content: @Composable ColumnScope.() -> Unit,
) {
    val shared = LocalReactionWindowCoordinates.current
    state.readViewport = { viewportBoundsInWindow()?.translate(shared?.offset ?: Offset.Zero) }
    val row = modifier.fillMaxWidth().localReactionOverlay(state).zIndex(if (state.effect != null) 1f else 0f)
    CompositionLocalProvider(LocalRowReactionMotion provides state) {
        Column(row, content = content)
    }
}

/** The direct owner tag follows the same endpoint eligibility as its production draw pass. */
private fun LocalReactionMotion.hasVisibleFlight(effect: LocalReactionEffect?): Boolean {
    val source = effect?.origin ?: return false
    return enabled && targets[effect.emoji]?.let { canFly(source, it) } == true
}

/** One screen/window conversion shared by transcript endpoints; popup windows keep their own origin. */
@Stable
internal class ReactionWindowCoordinates(
    val view: View,
) {
    private val screen = IntArray(2)
    private val window = IntArray(2)
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun update() {
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        offset = Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }
}

internal val LocalReactionWindowCoordinates = compositionLocalOf<ReactionWindowCoordinates?> { null }

@Composable
internal fun rememberReactionWindowCoordinates(): ReactionWindowCoordinates {
    val view = LocalView.current
    return remember(view) { ReactionWindowCoordinates(view) }
}
