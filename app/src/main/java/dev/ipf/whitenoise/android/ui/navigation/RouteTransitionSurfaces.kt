package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.drawMeasuredPage
import dev.ipf.whitenoise.android.ui.common.measuredPageBounds
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds

/** One visibility clock owns both drawing and outgoing-slot disposal. */
internal interface ConversationRouteTransition<T> {
    val currentState: T
    val targetState: T
    val isRunning: Boolean
    val visibility: State<Float>
    val retainedStates: List<T>
}

private class RetainedConversationRoute<T>(
    initialState: T,
    private val target: State<T>,
    private val contentKey: (T) -> Any?,
) : ConversationRouteTransition<T> {
    private var settledState by mutableStateOf(initialState)
    val progress = Animatable(if (initialState == null) 0f else 1f)
    override val visibility: State<Float> = progress.asState()
    override var retainedStates by mutableStateOf(listOf(initialState))
        private set
    override val targetState get() = target.value
    override val currentState get() =
        if (contentKey(settledState) == contentKey(targetState)) targetState else settledState
    override val isRunning get() =
        progress.isRunning ||
            contentKey(settledState) != contentKey(targetState) ||
            progress.value != if (targetState == null) 0f else 1f

    fun retainLatestPayload() {
        val latest = targetState
        val latestKey = contentKey(latest)
        retainedStates = retainedStates.map { if (contentKey(it) == latestKey) latest else it }
        if (contentKey(settledState) == latestKey) settledState = latest
    }

    suspend fun moveTo(
        destination: T,
        suppressMotion: Boolean,
    ) {
        retainedStates = (retainedStates + destination).distinctBy(contentKey)
        val end = if (destination == null) 0f else 1f
        if (suppressMotion) {
            progress.snapTo(end)
        } else {
            progress.animateTo(
                end,
                if (destination == null) {
                    tween(APP_MOTION_CLOSE_MILLIS, easing = AppMotionCloseEasing)
                } else {
                    conversationRouteAnimationSpec
                },
            )
        }
        // Cancellation never reaches this point: an interrupted route keeps its live slots.
        val latest = targetState
        settledState = latest
        retainedStates = listOf(latest)
    }
}

@Composable
internal fun <T> rememberConversationRouteTransition(
    targetState: T,
    suppressMotion: Boolean,
    ownerKey: Any? = Unit,
    contentKey: (T) -> Any? = { it },
): ConversationRouteTransition<T> {
    val target = rememberUpdatedState(targetState)
    val latestKey = rememberUpdatedState(contentKey)
    val transition = remember(ownerKey) { RetainedConversationRoute(targetState, target) { latestKey.value(it) } }
    SideEffect { transition.retainLatestPayload() }
    LaunchedEffect(transition, contentKey(targetState), suppressMotion) {
        transition.moveTo(targetState, suppressMotion)
    }
    return transition
}

/**
 * Conversation routes move only their full-screen render layers. Reading the
 * animated value inside [graphicsLayer] keeps every frame in the draw phase:
 * no animated recomposition, measurement, placement, or child-sized integer
 * offset can perturb the final frames. The short travel and crossfade retain
 * our live, already-prepared destination and Back route.
 */
@Composable
// Keep one retained-slot owner and one reversible clock; activation is optional route metadata.
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun <T> ConversationRouteAnimatedContent(
    transition: ConversationRouteTransition<T>,
    routeForwardDirection: Int,
    suppressMotion: Boolean,
    contentKey: (T) -> Any?,
    activatingBounds: (T) -> Rect? = { null },
    originOwner: ConversationRouteOrigin? = null,
    content: @Composable (T) -> Unit,
) {
    val view = LocalView.current
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    val replacingConversation =
        transition.currentState != null &&
            transition.targetState != null &&
            contentKey(transition.currentState) != contentKey(transition.targetState)
    var retainedOrigin by remember(originOwner, transition) { mutableStateOf<Rect?>(null) }
    val directOrigin = activatingBounds(transition.targetState ?: transition.currentState)
    val source = if (transition.targetState != null) directOrigin else directOrigin ?: retainedOrigin
    SideEffect {
        if (transition.targetState != null) {
            retainedOrigin = directOrigin
        } else if (!transition.isRunning && transition.currentState == null) {
            retainedOrigin = null
        }
    }
    val containerMorph =
        !suppressMotion &&
            !replacingConversation &&
            source != null &&
            !source.isEmpty &&
            !hostBounds.isEmpty &&
            source.overlaps(hostBounds)
    val separatedChrome = LocalComposerFabHandoff.current?.morphSurface == true
    val travelPx = with(LocalDensity.current) { CONVERSATION_ROUTE_LAYER_TRAVEL.toPx() }
    // One intrinsic progress value (list = 0, conversation = 1) is stable under
    // interruption. A Back during enter reverses from the exact rendered float
    // instead of creating a new target-relative 0 -> 1 animation that jumps.
    val conversationVisibility = transition.visibility
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { hostBounds = it.settingsMorphBounds(view) }
                .pointerInput(transition, containerMorph) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val unsettled =
                                transition.isRunning ||
                                    transition.currentState != transition.targetState ||
                                    conversationVisibility.value < 1f
                            if (containerMorph && unsettled) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        contentAlignment = Alignment.TopStart,
    ) {
        // Keep each composition key stable, resolve the requested key to its latest payload,
        // and put the requested chat above outgoing chats even for A -> B -> A.
        val targetKey = contentKey(transition.targetState)
        val routes =
            if (suppressMotion) {
                listOf(transition.targetState)
            } else {
                (transition.retainedStates + transition.currentState + transition.targetState)
                    .distinctBy(contentKey)
            }
        routes.forEach { retained ->
            val route = if (contentKey(retained) == targetKey) transition.targetState else retained
            key(transition, contentKey(route)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(
                            if (route == null) {
                                0f
                            } else if (contentKey(route) == targetKey) {
                                2f
                            } else {
                                1f
                            },
                        ).drawWithContent {
                            if (!containerMorph || route == null) {
                                drawContent()
                            } else {
                                val bubble =
                                    measuredPageBounds(checkNotNull(source), hostBounds, conversationVisibility.value)
                                        .translate(-hostBounds.topLeft)
                                val destination = this
                                drawMeasuredPage(bubble, conversationVisibility.value) { destination.drawContent() }
                            }
                        }.graphicsLayer {
                            val progress = conversationVisibility.value
                            val conversationRoute = route != null
                            alpha =
                                if (separatedChrome || containerMorph) {
                                    1f
                                } else {
                                    conversationRouteLayerAlpha(progress, conversationRoute, suppressMotion)
                                }
                            translationX =
                                (if (separatedChrome || containerMorph) 0f else travelPx) *
                                routeForwardDirection *
                                conversationRouteLayerTranslation(progress, conversationRoute, suppressMotion)
                        },
                ) {
                    CompositionLocalProvider(
                        LocalConversationRouteOrigin provides originOwner,
                        LocalControlHandoffEndpointCurrent provides
                            (contentKey(route) == contentKey(transition.targetState)),
                        LocalConversationBodyMotion provides
                            if (separatedChrome && !containerMorph) {
                                ConversationBodyMotion(
                                    conversationVisibility,
                                    route != null,
                                    routeForwardDirection,
                                    suppressMotion,
                                )
                            } else {
                                null
                            },
                    ) {
                        content(route)
                    }
                }
            }
        }
    }
}

/**
 * Routes arrive on the shared opening curve. The visibility owner applies the shared closing
 * curve on Back; both directions continue from the exact rendered value when interrupted.
 */
internal val ConversationRouteEasing = AppMotionOpenEasing

/** Visibility and slot retention must finish on the same immutable timing specification. */
private val conversationRouteAnimationSpec =
    tween<Float>(
        durationMillis = CONVERSATION_ROUTE_TRANSITION_MILLIS,
        easing = ConversationRouteEasing,
    )

/** Resolves one route layer's alpha from the shared intrinsic conversation visibility. */
internal fun conversationRouteLayerAlpha(
    conversationVisibility: Float,
    conversationRoute: Boolean,
    suppressMotion: Boolean,
): Float {
    if (suppressMotion) return 1f
    val boundedProgress = conversationVisibility.coerceIn(0f, 1f)
    return if (conversationRoute) boundedProgress else 1f - boundedProgress
}

/** Resolves one route layer's bounded travel fraction from shared visibility. */
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

/** Returns whether an activation callback still belongs to the latest quick-switch request. */
internal fun quickAccountSwitchRequestIsCurrent(
    transition: QuickAccountSwitchTransition?,
    requestId: Long,
    targetAccountRef: String,
): Boolean =
    transition?.requestId == requestId &&
        transition.targetAccountRef == targetAccountRef

/** Classifies a quick-switch tap against the active account and pending request. */
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

/** Requires both target-account ownership and a completed local projection. */
internal fun quickAccountSwitchOwnsTargetFrame(
    transition: QuickAccountSwitchTransition?,
    activeAccountRef: String?,
    targetLocallyReady: Boolean,
): Boolean =
    transition != null &&
        transition.targetAccountRef == activeAccountRef &&
        targetLocallyReady

/** Treats authoritative empty and populated local snapshots as equally ready. */
internal fun quickAccountSwitchTargetLocallyReady(
    controllerAccountRef: String?,
    activeAccountRef: String?,
    hasLoadedLocalSnapshot: Boolean,
): Boolean =
    controllerAccountRef == activeAccountRef &&
        hasLoadedLocalSnapshot

/** Shows the decorative cue only while a populated, ready target waits to reveal. */
internal fun quickAccountSwitchShouldShowCue(
    transition: QuickAccountSwitchTransition?,
    activeAccountRef: String?,
    targetLocallyReady: Boolean,
    targetHasAnyChats: Boolean,
): Boolean =
    quickAccountSwitchOwnsTargetFrame(transition, activeAccountRef, targetLocallyReady) &&
        targetHasAnyChats &&
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
            QuickAccountSwitchCue(cue, blocksInput = visible)
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

/**
 * Draws the full-surface target identity used during the bounded reveal. While [blocksInput], the cue swallows
 * touches, so none reach the frame beneath before the target is ready. Once the reveal starts the target below is
 * already safe, and touches pass through the fading cue to it: the fade never gates input (#3496).
 */
@Composable
@Suppress("FunctionNaming")
private fun QuickAccountSwitchCue(
    cue: QuickAccountSwitchTransition,
    blocksInput: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Preserve the cue's internal contrast while the whole frame
                // crossfades over an AMOLED-black destination.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .background(MaterialTheme.colorScheme.background)
                .then(if (blocksInput) Modifier.pointerInput(Unit) {} else Modifier)
                .testTag(QUICK_ACCOUNT_SWITCH_CUE_TAG),
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
                pictureDomain = AvatarImageLoader.publicProfiles(),
            )
            EmojiLabel(
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
