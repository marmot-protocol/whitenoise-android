package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.LifecycleOwner
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLoadFailureEdge
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

// Liveness fallback only. Correctness waits for the IME target/inset settle signal,
// never a guessed number of rendered frames.
private const val FOREGROUND_PRESENTATION_SETTLE_TIMEOUT_MS = 1_500L

/**
 * IME open/close re-anchoring, hoisted out of [ConversationScreen]'s body for
 * the same bytecode-verifier reason as [ConversationForegroundRestoreEffects].
 *
 * Re-anchoring is authorized by the coordinator snapshot taken at the
 * pre-inset focus edge, never by transient live list geometry — and never
 * mid-gesture: the wait settles only when the live inset reaches the IME
 * animation target, so a swipe-up drag that pauses cannot trigger the scroll
 * write under the user's finger.
 */
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationImeReanchorEffect(
    controller: ConversationController,
    scrollCoordinator: ConversationScrollCoordinator,
    imeIsOpen: Boolean,
    initialTimelineAnchored: Boolean,
    imeTransitionBookmark: MutableState<ConversationScrollBookmark?>,
    suppressNextImeOpenReanchor: AtomicBoolean,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    resolveScrollAnchorIndex: (ConversationScrollAnchor) -> Int?,
    currentTailIndex: () -> Int,
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeAnimationTargetInsets = WindowInsets.imeAnimationTarget
    LaunchedEffect(controller, imeIsOpen, initialTimelineAnchored) {
        if (!imeIsOpen) {
            imeTransitionBookmark.value = null
            return@LaunchedEffect
        }
        if (scrollCoordinator.foregroundRestoreInProgress) return@LaunchedEffect
        val suppressForCustomInputSwap = suppressNextImeOpenReanchor.getAndSet(false)
        if (!initialTimelineAnchored || suppressForCustomInputSwap) return@LaunchedEffect
        awaitImeInsetAtTarget(
            readInset = { imeInsets.getBottom(density) },
            readTargetInset = { imeAnimationTargetInsets.getBottom(density) },
            awaitFrame = { withFrameNanos { } },
        )
        val snapshot = imeTransitionBookmark.value ?: scrollCoordinator.bookmark(currentScrollAnchor())
        when (snapshot.settledMode) {
            ConversationScrollMode.FollowingTail ->
                scrollCoordinator.followTailIfAllowed(
                    resolveTailIndex = { currentTailIndex() },
                    reason = ConversationScrollReason.ImeTransition,
                )
            is ConversationScrollMode.ReadingHistory ->
                scrollCoordinator.restoreBookmark(snapshot, resolveAnchorIndex = resolveScrollAnchorIndex)
            else -> Unit
        }
    }
}

/**
 * App-switch pause/resume handling for the conversation viewport, hoisted out
 * of [ConversationScreen]'s body — the machinery lives here so the screen's
 * already-enormous composable method stays within what ART's bytecode
 * verifier accepts from the debug (non-minified) dex pipeline.
 *
 * Android/Compose can restore the composer's focus and IME visibility on its
 * own when the app returns to the foreground, popping a keyboard the user
 * never asked for. Focus is snapshotted on ON_PAUSE and gated through the
 * pure `shouldRestoreComposerFocusOnResume` predicate on ON_RESUME. Scroll,
 * inset, bottom-chrome, and timeline state are captured in the coordinator at
 * the same pause edge; resume commits from the actual IME target/inset settle
 * signal, so an unchanged presentation performs no list write while a real
 * geometry or structure delta gets exactly one correction.
 */
@Suppress("FunctionNaming", "LongMethod") // Jetpack Compose functions use UpperCamelCase, and this
// is the conversation screen's pause/resume wiring moved out verbatim — splitting it further would
// scatter one lifecycle across files.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationForegroundRestoreEffects(
    controller: ConversationController,
    scrollCoordinator: ConversationScrollCoordinator,
    lifecycleOwner: LifecycleOwner?,
    listState: LazyListState,
    bottomChromeHeightObserver: ConversationBottomChromeHeightObserver,
    composerFocused: Boolean,
    searchOpen: Boolean,
    hasActiveEditOrReplySession: Boolean,
    composerFocus: FocusRequester,
    initialTimelineAnchored: Boolean,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    resolveScrollAnchorIndex: (ConversationScrollAnchor) -> Int?,
    currentTailIndex: () -> Int,
) {
    val foregroundPreDrawSignals = remember(controller) { Channel<Unit>(capacity = Channel.CONFLATED) }
    var foregroundRestoreToken by remember(controller) { mutableStateOf<ConversationForegroundRestoreToken?>(null) }
    val resumeScrollRestoreCoordinator = remember(controller) { ResumeScrollRestoreCoordinator() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeAnimationTargetInsets = WindowInsets.imeAnimationTarget
    val scope = rememberCoroutineScope()
    val currentInitialTimelineAnchored by rememberUpdatedState(newValue = initialTimelineAnchored)
    val currentScrollAnchorProvider by rememberUpdatedState(newValue = currentScrollAnchor)
    val currentScrollAnchorResolver by rememberUpdatedState(newValue = resolveScrollAnchorIndex)
    val currentTailIndexProvider by rememberUpdatedState(newValue = currentTailIndex)
    val currentForegroundGeometryProvider by
        rememberUpdatedState(
            newValue = {
                ConversationForegroundGeometry(
                    viewportHeightPx = listState.layoutInfo.viewportSize.height,
                    imeBottomPx = imeInsets.getBottom(density),
                    bottomChromeHeightPx = bottomChromeHeightObserver.currentHeightPx,
                )
            },
        )
    val currentTimelineStructureProvider by
        rememberUpdatedState(
            newValue = {
                val liveTimeline = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                ConversationTimelineStructure(
                    rowKeys = liveTimeline.map { it.id to it.record.messageIdHex },
                    olderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0,
                    inlineTopErrorCount =
                        if (
                            liveTimeline.isNotEmpty() &&
                            controller.error != null &&
                            controller.errorEdge == ConversationLoadFailureEdge.TOP
                        ) {
                            1
                        } else {
                            0
                        },
                )
            },
        )
    val currentForegroundSettleStateProvider by
        rememberUpdatedState(
            newValue = {
                ConversationForegroundSettleState(
                    geometry = currentForegroundGeometryProvider(),
                    imeTargetBottomPx = imeAnimationTargetInsets.getBottom(density),
                    bottomChromeMeasured = bottomChromeHeightObserver.hasMeasurement,
                )
            },
        )
    ConversationForegroundDrawGateEffect(
        isBlocked = { scrollCoordinator.foregroundRestoreInProgress },
        onPreDraw = { foregroundPreDrawSignals.trySend(Unit) },
    )
    ConversationComposerLifecycleEffect(
        observerKey = controller,
        lifecycleOwner = lifecycleOwner,
        composerFocused = composerFocused,
        searchOpen = searchOpen,
        hasActiveEditOrReplySession = hasActiveEditOrReplySession,
        onPause = {
            resumeScrollRestoreCoordinator.cancel()
            foregroundRestoreToken =
                if (currentInitialTimelineAnchored) {
                    scrollCoordinator.beginForegroundRestore(
                        ConversationForegroundSnapshot(
                            scrollBookmark = scrollCoordinator.bookmark(currentScrollAnchorProvider()),
                            geometry = currentForegroundGeometryProvider(),
                            timelineStructure = currentTimelineStructureProvider(),
                        ),
                    )
                } else {
                    scrollCoordinator.cancelForegroundRestore()
                    null
                }
        },
        onResume = { restoreFocus, clearFocus ->
            foregroundPreDrawSignals.tryReceive()
            val restoreToken = foregroundRestoreToken
            foregroundRestoreToken = null
            resumeScrollRestoreCoordinator.launchResumeWork(scope) {
                when {
                    restoreFocus -> {
                        runCatching { composerFocus.requestFocus() }
                        keyboardController?.show()
                    }
                    clearFocus -> {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                }
                if (currentInitialTimelineAnchored && restoreToken != null) {
                    var releaseFrameRequested = false
                    val resumedPresentation =
                        awaitConversationForegroundPresentation(
                            preDrawSignals = foregroundPreDrawSignals,
                            currentState = currentForegroundSettleStateProvider,
                            expectedImeVisible = restoreToken.expectedImeVisible || restoreFocus,
                            expectedVisibilityTimeoutMillis = FOREGROUND_PRESENTATION_SETTLE_TIMEOUT_MS,
                            // Past the deadline the snapshot stays armed, so the
                            // wait below still applies one correction when
                            // geometry finally settles — only user intent,
                            // navigation, or disposal discards it. Opening the
                            // gate explicitly invalidates the root because this
                            // flag was historically read only from onPreDraw and
                            // could otherwise wait forever for external input.
                            onSettleDeadlineExpired = {
                                scrollCoordinator.releaseForegroundRestoreGate(restoreToken)
                                requestConversationForegroundFrame(rootView)
                                releaseFrameRequested = true
                            },
                        )
                    try {
                        scrollCoordinator.completeForegroundRestore(
                            token = restoreToken,
                            resumedGeometry = resumedPresentation.geometry,
                            resumedTimelineStructure = currentTimelineStructureProvider(),
                            resumedScrollAnchor = currentScrollAnchorProvider(),
                            resolveAnchorIndex = currentScrollAnchorResolver,
                            resolveTailIndex = { currentTailIndexProvider() },
                        )
                    } finally {
                        if (!releaseFrameRequested) requestConversationForegroundFrame(rootView)
                    }
                } else {
                    scrollCoordinator.cancelForegroundRestore()
                }
            }
        },
        onObserverDisposed = {
            resumeScrollRestoreCoordinator.cancel()
            scrollCoordinator.cancelForegroundRestore()
            foregroundRestoreToken = null
        },
    )
}
