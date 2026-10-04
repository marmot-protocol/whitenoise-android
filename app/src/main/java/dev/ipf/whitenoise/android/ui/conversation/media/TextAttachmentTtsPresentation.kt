package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.TtsSentenceActions
import dev.ipf.whitenoise.android.ui.TtsSentenceChoice
import dev.ipf.whitenoise.android.ui.TtsSentenceLayoutReporter
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowPolicy
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowTarget
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutRegistry
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutReport
import dev.ipf.whitenoise.android.ui.conversation.conversationFollowTargetOrNull
import dev.ipf.whitenoise.android.ui.conversation.messages.RenderedTextHit
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsHighlightProjectionResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsLinkTapCoordinator
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightStyle
import dev.ipf.whitenoise.android.ui.conversation.messages.effectiveTtsHighlightPassage
import dev.ipf.whitenoise.android.ui.conversation.messages.observeMessageTextDoubleTap
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberTtsHighlightProjectionResolver
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberTtsReadAloudHighlightStyle
import dev.ipf.whitenoise.android.ui.conversation.messages.speakableProjection
import dev.ipf.whitenoise.android.ui.conversation.messages.ttsSentenceBoundsInWindow
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationTtsFollowPolicy
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Reader-only view of the existing process-wide speech owner; it stores no second queue. */
internal data class TextAttachmentPlayback(
    val entry: TtsSpeakableEntry,
    val state: TtsState,
    val prepared: PreparedSpeechMessage?,
    val isCurrent: () -> Boolean,
    val seek: (Int, String) -> TtsState?,
    val startAt: (RenderedTextHit) -> Unit,
)

/** Rejects selected-text, stale attachment, replaced projection and non-playing coordinates. */
internal fun textAttachmentPlaybackPassage(playback: TextAttachmentPlayback?): TtsPassage? =
    playback
        ?.takeIf { it.isCurrent() && (it.state is TtsState.Speaking || it.state is TtsState.Paused) }
        ?.let { effectiveTtsHighlightPassage(it.state.passage, it.entry.messageIdHex, it.entry.projectionId, false) }

/** Optional renderer hooks, with only document-local geometry and follow controls. */
internal data class TextAttachmentTtsUi(
    val bodyModifier: Modifier,
    val viewportModifier: Modifier,
    val highlight: TtsLeafHighlightResolver?,
    val style: TtsReadAloudHighlightStyle,
    val layoutReporter: TtsSentenceLayoutReporter?,
    val sentenceActions: TtsSentenceActions?,
    val deferLinkActivation: ((() -> Unit) -> Unit),
    val showResumeFollow: Boolean,
    val resumeFollow: () -> Unit,
)

/** Adapts shared passage/gesture/follow contracts to the reader's eager scroll container. */
@Composable
@Suppress("LongMethod")
internal fun rememberTextAttachmentTtsUi(
    playback: TextAttachmentPlayback?,
    selection: TextAttachmentSelectionController,
    scroll: ScrollState,
): TextAttachmentTtsUi {
    val currentPlayback = rememberUpdatedState(playback)
    val projection = remember(playback?.entry) { playback?.entry?.speakableProjection() }
    val resolver = rememberTtsHighlightProjectionResolver(projection, playback?.prepared)
    val passage = textAttachmentPlaybackPassage(playback)
    val target = playback?.state?.conversationFollowTargetOrNull()?.takeIf { passage != null }
    val layouts = remember(selection) { ConversationTtsSentenceLayoutRegistry() }
    val row = remember(selection) { Any() }
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    var viewport by remember(selection) { mutableStateOf<Rect?>(null) }
    val policy = rememberReaderFollowPolicy(playback, passage != null, selection.active, dragging, viewport)
    LaunchedEffect(scroll.interactionSource, policy) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) policy.onUserDrag()
        }
    }
    var retryGeneration by remember(selection) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val timeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val links =
        remember(selection, scope, timeout) {
            TtsLinkTapCoordinator(scope, { textAttachmentPlaybackPassage(currentPlayback.value) != null }, timeout)
        }
    DisposableEffect(layouts, row, playback?.entry?.messageIdHex, links) {
        val id = playback?.entry?.messageIdHex.orEmpty()
        layouts.mountRow(id, row)
        onDispose {
            layouts.unmountRow(id, row)
            links.cancelPendingActivation()
        }
    }
    val reporter = rememberReaderSentenceLayoutReporter(target, resolver, layouts, row, selection.active)
    val follower =
        remember(policy, layouts, scroll, currentPlayback) {
            TextAttachmentTtsFollow(policy, layouts, scroll, currentPlayback)
        }
    LaunchedEffect(target, policy.isFollowEnabled, viewport, dragging, selection.active, retryGeneration) {
        if (dragging || selection.active) return@LaunchedEffect
        val visible = viewport ?: return@LaunchedEffect
        if (follower.reveal(visible)) retryGeneration += 1
    }
    val interactions =
        remember(currentPlayback, resolver, selection, policy, links) {
            ReaderSpeechInteractions(currentPlayback, resolver, selection, policy, links)
        }
    val actions =
        remember(resolver, target, selection.active) {
            if (resolver == null || target == null || selection.active) {
                null
            } else {
                TtsSentenceActions(
                    choices = resolver::sentenceChoices,
                    select = interactions::choose,
                )
            }
        }
    val colors = MaterialTheme.colorScheme
    val style =
        rememberTtsReadAloudHighlightStyle(
            colors.surface,
            colors.onSurface,
            colors.outlineVariant,
            colors.tertiary,
            isAmoledSurfaceTheme(),
        )
    val bodyModifier = rememberReaderSeekModifier(selection, currentPlayback, interactions, links)
    return TextAttachmentTtsUi(
        bodyModifier = bodyModifier,
        viewportModifier =
            Modifier.onGloballyPositioned {
                val bounds = it.boundsInWindow()
                viewport = bounds
                layouts.updateViewportBounds(bounds)
            },
        highlight =
            if (selection.active || passage == null) {
                null
            } else {
                resolver?.resolverFor(passage, requireNotNull(playback).entry.messageIdHex)
            },
        style = style,
        layoutReporter = reporter,
        sentenceActions = actions,
        deferLinkActivation = links::activate,
        showResumeFollow = target != null && policy.showResumeAction && !selection.active,
        resumeFollow = { policy.requestExplicitReveal() },
    )
}

/** Reopening reveals the live paused/speaking cursor, but never submits text to the speech engine. */
@Composable
private fun rememberReaderFollowPolicy(
    playback: TextAttachmentPlayback?,
    ownsPassage: Boolean,
    selecting: Boolean,
    dragging: Boolean,
    viewport: Rect?,
): ConversationTtsFollowPolicy {
    val policy = rememberConversationTtsFollowPolicy(playback?.entry?.messageIdHex.orEmpty())
    LaunchedEffect(playback?.state, ownsPassage, dragging, selecting) {
        policy.observe(playback?.state ?: TtsState.Idle(), ownsPassage)
        if (dragging || selecting) policy.onUserDrag()
    }
    LaunchedEffect(playback?.entry?.projectionId, playback?.state?.sessionId, ownsPassage) {
        if (ownsPassage && !selecting && !dragging) {
            policy.observe(requireNotNull(playback).state, true)
            policy.requestExplicitReveal()
        }
    }
    // Insets/transport or rotation can invalidate a measured sentence without changing its identity.
    LaunchedEffect(viewport) {
        val directInteraction = selecting || dragging
        if (viewport != null && !directInteraction && policy.isFollowEnabled) policy.requestExplicitReveal()
    }
    return policy
}

/** Active hits and accessibility choices never replace the process-wide queue on mapping failure. */
private class ReaderSpeechInteractions(
    private val playback: State<TextAttachmentPlayback?>,
    private val resolver: TtsHighlightProjectionResolver?,
    private val selection: TextAttachmentSelectionController,
    private val policy: ConversationTtsFollowPolicy,
    private val links: TtsLinkTapCoordinator,
) {
    fun seek(hit: RenderedTextHit) {
        val live = playback.value?.takeIf { it.isCurrent() && !selection.active } ?: return
        if (textAttachmentPlaybackPassage(live) != null) {
            links.cancelPendingActivation()
            val sentence = resolver?.sentenceIndexAtRenderedOffset(hit)
            if (sentence != null) suppressFollow(live.seek(sentence, live.entry.projectionId))
        } else {
            live.startAt(hit)
        }
    }

    fun choose(choice: TtsSentenceChoice): Boolean {
        val live = playback.value ?: return false
        return if (!selection.active && live.isCurrent() && choice.revision == live.entry.projectionId) {
            suppressFollow(live.seek(choice.ordinal, choice.revision))
        } else {
            false
        }
    }

    private fun suppressFollow(state: TtsState?): Boolean =
        state?.conversationFollowTargetOrNull()?.let {
            links.cancelPendingActivation()
            policy.suppressNextFollowFor(it)
            true
        } ?: false
}

@Composable
private fun rememberReaderSeekModifier(
    selection: TextAttachmentSelectionController,
    playback: State<TextAttachmentPlayback?>,
    interactions: ReaderSpeechInteractions,
    links: TtsLinkTapCoordinator,
): Modifier {
    var coordinates by remember(selection) { mutableStateOf<LayoutCoordinates?>(null) }
    return Modifier.onGloballyPositioned { coordinates = it }.observeMessageTextDoubleTap(
        enabled = playback.value != null && !selection.active,
        allowConsumedTapAt = { local ->
            coordinates?.localToWindow(local)?.let {
                textAttachmentPlaybackPassage(playback.value) != null && selection.hasLinkAt(it)
            } == true
        },
        onPointerDown = { links.beginPointerActivation() },
        onPointerFinished = links::endPointerActivation,
        onDoubleTap = { local ->
            coordinates?.localToWindow(local)?.let { position ->
                if (textAttachmentPlaybackPassage(playback.value) != null || !selection.hasLinkAt(position)) {
                    selection.renderedHitAt(position)?.let(interactions::seek)
                }
            }
        },
    )
}

@Composable
private fun rememberReaderSentenceLayoutReporter(
    target: ConversationTtsFollowTarget?,
    resolver: TtsHighlightProjectionResolver?,
    layouts: ConversationTtsSentenceLayoutRegistry,
    row: Any,
    selecting: Boolean,
): TtsSentenceLayoutReporter? =
    remember(target, resolver, layouts, row, selecting, layouts.viewportBoundsInWindow) {
        if (target == null || resolver == null || selecting) {
            null
        } else {
            { leaf, text, layout, position ->
                val mapped = resolver.sentenceLayoutFor(target.sentenceIndex, leaf, text)
                val bounds =
                    if (layout != null && position != null && mapped != null) {
                        ttsSentenceBoundsInWindow(layout, position, mapped.renderedRanges)
                    } else {
                        null
                    }
                if (mapped == null || bounds == null) {
                    layouts.clear(target, row, leaf)
                } else {
                    layouts.report(
                        ConversationTtsSentenceLayoutReport(
                            target,
                            row,
                            leaf,
                            bounds,
                            mapped.coverage,
                            mapped.expectedCoverage,
                        ),
                    )
                }
            }
        }
    }
