package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Avatar

/**
 * Conversation routes move only their full-screen render layers. Reading the
 * animated value inside [graphicsLayer] keeps every frame in the draw phase:
 * no animated recomposition, measurement, placement, or child-sized integer
 * offset can perturb the final frames. The short travel and crossfade retain
 * our live, already-prepared destination and Back route.
 */
@Composable
@Suppress("FunctionNaming")
internal fun <T> ConversationRouteAnimatedContent(
    transition: Transition<T>,
    routeForwardDirection: Int,
    suppressMotion: Boolean,
    contentKey: (T) -> Any?,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val travelPx = with(LocalDensity.current) { CONVERSATION_ROUTE_LAYER_TRAVEL.toPx() }
    // One intrinsic progress value (list = 0, conversation = 1) is stable under
    // interruption. A Back during enter reverses from the exact rendered float
    // instead of creating a new target-relative 0 -> 1 animation that jumps.
    val conversationVisibility =
        transition.animateFloat(
            transitionSpec = {
                if (suppressMotion) {
                    snap()
                } else {
                    tween(
                        durationMillis = CONVERSATION_ROUTE_TRANSITION_MILLIS,
                        easing = FastOutSlowInEasing,
                    )
                }
            },
            label = "conversation route visibility",
        ) { state ->
            if (state != null) 1f else 0f
        }
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = { routeContentTransform(suppressMotion) },
        contentKey = contentKey,
    ) { route ->
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = conversationVisibility.value
                    val conversationRoute = route != null
                    alpha = conversationRouteLayerAlpha(progress, conversationRoute, suppressMotion)
                    translationX =
                        travelPx *
                        routeForwardDirection *
                        conversationRouteLayerTranslation(progress, conversationRoute, suppressMotion)
                },
        ) {
            content(route)
        }
    }
}

private fun <T> AnimatedContentTransitionScope<T>.routeContentTransform(suppressMotion: Boolean): ContentTransform {
    if (suppressMotion) return EnterTransition.None togetherWith ExitTransition.None
    val animationSpec =
        tween<Float>(
            durationMillis = CONVERSATION_ROUTE_TRANSITION_MILLIS,
            easing = FastOutSlowInEasing,
        )
    // A non-zero delta keeps AnimatedContent's outgoing slot alive for the
    // layer animation. Visible alpha is owned by conversationRouteLayerAlpha.
    return (
        fadeIn(
            initialAlpha = CONVERSATION_ROUTE_SLOT_RETENTION_ALPHA,
            animationSpec = animationSpec,
        ) togetherWith
            fadeOut(
                targetAlpha = CONVERSATION_ROUTE_SLOT_RETENTION_ALPHA,
                animationSpec = animationSpec,
            )
    ).using(SizeTransform(clip = false))
}

internal fun conversationRouteLayerAlpha(
    conversationVisibility: Float,
    conversationRoute: Boolean,
    suppressMotion: Boolean,
): Float {
    if (suppressMotion) return 1f
    val boundedProgress = conversationVisibility.coerceIn(0f, 1f)
    return if (conversationRoute) boundedProgress else 1f - boundedProgress
}

internal fun conversationRouteLayerTranslation(
    conversationVisibility: Float,
    conversationRoute: Boolean,
    suppressMotion: Boolean,
): Float {
    if (suppressMotion) return 0f
    val boundedProgress = conversationVisibility.coerceIn(0f, 1f)
    return if (conversationRoute) 1f - boundedProgress else -boundedProgress
}

internal enum class QuickAccountSwitchMotion {
    Animated,
    Reduced,
}

internal enum class QuickAccountSwitchPhase {
    AwaitingTarget,
    RevealingTarget,
    RevealComplete,
}

internal enum class QuickAccountSwitchRequestDisposition {
    Start,
    CancelPendingToCurrent,
    Ignore,
}

internal data class QuickAccountSwitchTransition(
    val requestId: Long,
    val sourceAccountRef: String,
    val targetAccountRef: String,
    val targetTitle: String,
    val targetSeed: String,
    val targetPictureUrl: String?,
    val motion: QuickAccountSwitchMotion,
    val phase: QuickAccountSwitchPhase = QuickAccountSwitchPhase.AwaitingTarget,
)

internal fun quickAccountSwitchRequestIsCurrent(
    transition: QuickAccountSwitchTransition?,
    requestId: Long,
    targetAccountRef: String,
): Boolean =
    transition?.requestId == requestId &&
        transition.targetAccountRef == targetAccountRef

internal fun quickAccountSwitchRequestDisposition(
    activeAccountRef: String?,
    pending: QuickAccountSwitchTransition?,
    targetAccountRef: String,
): QuickAccountSwitchRequestDisposition =
    when {
        pending?.targetAccountRef == targetAccountRef -> QuickAccountSwitchRequestDisposition.Ignore
        activeAccountRef == targetAccountRef && pending != null ->
            QuickAccountSwitchRequestDisposition.CancelPendingToCurrent
        activeAccountRef == targetAccountRef -> QuickAccountSwitchRequestDisposition.Ignore
        else -> QuickAccountSwitchRequestDisposition.Start
    }

internal fun quickAccountSwitchOwnsTargetFrame(
    transition: QuickAccountSwitchTransition?,
    activeAccountRef: String?,
    targetLocallyReady: Boolean,
): Boolean =
    transition != null &&
        transition.targetAccountRef == activeAccountRef &&
        targetLocallyReady

internal fun quickAccountSwitchTargetLocallyReady(
    controllerAccountRef: String?,
    activeAccountRef: String?,
    hasLoadedLocalSnapshot: Boolean,
): Boolean =
    controllerAccountRef == activeAccountRef &&
        hasLoadedLocalSnapshot

internal fun quickAccountSwitchShouldShowCue(
    transition: QuickAccountSwitchTransition?,
    activeAccountRef: String?,
    targetLocallyReady: Boolean,
): Boolean =
    quickAccountSwitchOwnsTargetFrame(transition, activeAccountRef, targetLocallyReady) &&
        transition?.motion == QuickAccountSwitchMotion.Animated &&
        transition.phase == QuickAccountSwitchPhase.AwaitingTarget

/** A branded identity frame above the already-composed target account. */
@Composable
@Suppress("FunctionNaming")
internal fun QuickAccountSwitchTransitionOverlay(
    transition: QuickAccountSwitchTransition?,
    visible: Boolean,
    onFinished: (Long) -> Unit,
) {
    key(transition?.requestId) {
        val visibility = androidx.compose.runtime.remember { MutableTransitionState(visible) }
        SideEffect { visibility.targetState = visible }
        androidx.compose.animation.AnimatedVisibility(
            visibleState = visibility,
            enter = EnterTransition.None,
            exit = fadeOut(tween(QUICK_ACCOUNT_SWITCH_TRANSITION_MILLIS)),
        ) {
            val cue = transition ?: return@AnimatedVisibility
            QuickAccountSwitchCue(cue)
        }

        LaunchedEffect(
            transition?.requestId,
            transition?.phase,
            transition?.motion,
            visibility.currentState,
            visibility.targetState,
            visibility.isIdle,
        ) {
            val request = transition ?: return@LaunchedEffect
            val fadeHidden =
                visibility.isIdle &&
                    !visibility.currentState &&
                    !visibility.targetState
            val revealFinished =
                request.motion == QuickAccountSwitchMotion.Animated &&
                    request.phase == QuickAccountSwitchPhase.RevealingTarget &&
                    fadeHidden
            if (revealFinished) {
                onFinished(request.requestId)
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun QuickAccountSwitchCue(cue: QuickAccountSwitchTransition) {
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                // Preserve the cue's internal contrast while the whole frame
                // crossfades over an AMOLED-black destination.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .testTag(QUICK_ACCOUNT_SWITCH_CUE_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(
                title = cue.targetTitle,
                seed = cue.targetSeed,
                pictureUrl = cue.targetPictureUrl,
                size = 72.dp,
            )
            Text(
                text = stringResource(R.string.switching_to_account, cue.targetTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// Leave three 60 Hz presentation frames inside the issue's 180 ms end-to-end
// budget: one to commit the reveal target, one to settle the fade, and one to
// dispose the cue and deliver its completion effect.
internal const val QUICK_ACCOUNT_SWITCH_TRANSITION_MILLIS = 128
internal const val QUICK_ACCOUNT_SWITCH_MAX_PRESENTATION_MILLIS = 180
internal const val QUICK_ACCOUNT_SWITCH_CUE_TAG = "quick-account-switch-cue"
internal val CONVERSATION_ROUTE_LAYER_TRAVEL = 48.dp
private const val CONVERSATION_ROUTE_SLOT_RETENTION_ALPHA = 0.999f
