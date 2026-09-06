package dev.ipf.whitenoise.android.ui.conversation

import android.annotation.SuppressLint
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TTS_AUTO_READ_MAX_MESSAGES
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsStartFailure
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

// Foreground catch-up normally materializes almost immediately. Keep the
// background-arrival listener bounded so a later, genuinely foreground
// message does not unexpectedly start an otherwise idle auto-reader.
private const val TTS_AUTO_READ_RESUME_SYNC_TIMEOUT_MS = 10_000L

/**
 * Small conversation-owned handle for TTS-follow UI events. The expensive
 * flow collection and scroll effect live in [ConversationTtsFollowEffects] so
 * they do not inflate the already-large generated ConversationScreen method.
 */
internal class ConversationTtsFollowHandle internal constructor(
    internal val policy: ConversationTtsFollowPolicy,
) {
    internal val sentenceLayouts = ConversationTtsSentenceLayoutRegistry()
    internal var retryGeneration: Long by mutableLongStateOf(0L)
        private set

    val showResumeAction: Boolean
        get() = policy.showResumeAction

    fun suspendForDirectDrag(
        state: TtsState,
        ownsSession: Boolean,
    ) {
        policy.observe(state, ownsSession)
        policy.onUserDrag()
    }

    fun resumeFollow() {
        policy.resumeFollow()
        retryGeneration += 1L
    }

    fun revealCurrentPassage(
        state: TtsState,
        ownsSession: Boolean,
    ) {
        policy.observe(state, ownsSession)
        if (policy.requestExplicitReveal()) retryGeneration += 1L
    }

    fun onSentenceSeek(
        state: TtsState,
        ownsSession: Boolean,
    ) {
        if (!ownsSession) return
        policy.observe(state, ownsSession)
        state.conversationFollowTargetOrNull()?.let(policy::suppressNextFollowFor)
    }

    internal fun retryFailedFollowAttempt(target: ConversationTtsFollowTarget) {
        if (policy.retryFailedFollowAttempt(target)) retryGeneration += 1L
    }
}

@Composable
internal fun rememberConversationTtsFollowHandle(groupIdHex: String): ConversationTtsFollowHandle {
    val policy = rememberConversationTtsFollowPolicy(groupIdHex)
    return remember(policy) { ConversationTtsFollowHandle(policy) }
}

/**
 * Owns open-time, live, and foreground-return auto-read orchestration outside
 * ConversationScreen. This is deliberately a code-motion boundary: MDK and
 * the controller remain the source of truth and no new cache is introduced.
 * These lifecycle-coupled effects intentionally share one conversation-owned
 * state boundary so session ownership and resume cursors cannot drift apart.
 */
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
@Composable
internal fun ConversationTtsAutoReadEffects(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    chatId: String,
    entryUnreadCount: Int,
    entryFirstUnreadMessageId: String?,
    initialTimelineAnchored: Boolean,
) {
    suspend fun projectEntry(record: AppMessageRecordFfi) = projectConversationTtsEntry(appState, controller, record)

    suspend fun autoReadBacklogEntries(): List<TtsSpeakableEntry> {
        val ready =
            appState.ttsHasUsableEngine &&
                appState.isConversationAutoRead(controller.group.groupIdHex) &&
                entryUnreadCount > 0
        val start =
            if (ready) {
                entryFirstUnreadMessageId
                    ?.let { id -> controller.timeline.indexOfFirst { it.record.messageIdHex == id } }
                    ?.takeIf { it >= 0 }
                    ?: controller.firstUnreadTimelineIndex(entryUnreadCount)
            } else {
                -1
            }
        return if (start < 0) {
            emptyList()
        } else {
            controller.timeline
                .drop(start)
                // Bound BEFORE mapping so the cost scales with the speak cap,
                // not the unread count; 2x slack absorbs filtered-out entries.
                .take(TTS_AUTO_READ_MAX_MESSAGES * 2)
                .mapNotNull { message -> projectEntry(message.record) }
        }
    }

    // Auto-read (#1483): once the timeline is anchored, read the unread backlog.
    LaunchedEffect(controller, chatId, initialTimelineAnchored) {
        if (!initialTimelineAnchored) return@LaunchedEffect
        val entries = autoReadBacklogEntries()
        if (entries.isNotEmpty()) {
            val started =
                appState.speakAloudAutoRead(
                    controller.group.groupIdHex,
                    entries,
                    Locale.getDefault(),
                )
            if (!started && appState.ttsController.lastStartFailure == TtsStartFailure.MediaNotActive) {
                appState.present(R.string.tts_media_mix_no_active_media)
            }
        }
    }

    // Live continuation only extends the conversation-owned active session.
    LaunchedEffect(controller, chatId) {
        var seededLastId = false
        snapshotFlow {
            controller.timeline
                .lastOrNull()
                ?.record
                ?.messageIdHex
        }.distinctUntilChanged()
            .collect { lastId ->
                if (lastId == null) return@collect
                if (!seededLastId) {
                    seededLastId = true
                    return@collect
                }
                if (!appState.ownsTtsAutoReadSession(controller.group.groupIdHex)) return@collect
                val ttsState = appState.ttsController.state.value
                if (ttsState !is TtsState.Speaking && ttsState !is TtsState.Paused) return@collect
                val record = controller.timeline.lastOrNull()?.record ?: return@collect
                if (record.messageIdHex != lastId) return@collect
                val entry = projectEntry(record) ?: return@collect
                appState.appendSpeech(entry, Locale.getDefault())
            }
    }

    // On a real foreground return, narrate rows materialized after the paused
    // timeline cursor without replacing a newer manual/active speech session.
    var autoReadResumeCursor by
        remember(controller, chatId) {
            mutableStateOf(conversationAutoReadCursor(controller.timeline))
        }
    var autoReadResumeGeneration by remember(controller, chatId) { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalContext.current.lifecycleOwner()
    DisposableEffect(controller, chatId, lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            var hadPaused = false
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            autoReadResumeCursor = conversationAutoReadCursor(controller.timeline)
                            hadPaused = true
                        }
                        Lifecycle.Event.ON_RESUME ->
                            if (hadPaused) {
                                hadPaused = false
                                autoReadResumeGeneration += 1L
                            }
                        else -> Unit
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(controller, chatId, autoReadResumeGeneration) {
        if (autoReadResumeGeneration == 0L) return@LaunchedEffect
        if (!appState.ttsHasUsableEngine) return@LaunchedEffect
        if (!appState.isConversationAutoRead(controller.group.groupIdHex)) return@LaunchedEffect
        val cursor = autoReadResumeCursor
        val entries =
            withTimeoutOrNull(TTS_AUTO_READ_RESUME_SYNC_TIMEOUT_MS) {
                snapshotFlow {
                    conversationMessagesAfterAutoReadCursor(controller.timeline, cursor)
                        .take(TTS_AUTO_READ_MAX_MESSAGES * 2)
                }.map { messages ->
                    messages.mapNotNull { message -> projectEntry(message.record) }
                }.first { it.isNotEmpty() }
            } ?: return@LaunchedEffect
        val ttsState = appState.ttsController.state.value
        if (ttsState is TtsState.Speaking || ttsState is TtsState.Paused) return@LaunchedEffect
        if (!appState.isConversationAutoRead(controller.group.groupIdHex)) return@LaunchedEffect
        val started =
            appState.speakAloudAutoRead(
                controller.group.groupIdHex,
                entries,
                Locale.getDefault(),
            )
        if (!started && appState.ttsController.lastStartFailure == TtsStartFailure.MediaNotActive) {
            appState.present(R.string.tts_media_mix_no_active_media)
        }
    }
}

/**
 * Collects the active spoken sentence and performs guarded viewport following
 * outside ConversationScreen's generated method. Direct-drag and resume UI
 * events enter through [handle]. The guarded follow attempt is one scroll
 * transaction with a single retry outcome; keeping it contiguous prevents
 * stale targets from partially moving the viewport after speech or timeline
 * state changes.
 */
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
@Composable
internal fun ConversationTtsFollowEffects(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    listState: LazyListState,
    scrollCoordinator: ConversationScrollCoordinator,
    handle: ConversationTtsFollowHandle,
    initialTimelineAnchored: Boolean,
    renderedTimeline: List<TimelineMessage>,
    timelineItemHeightsPx: Map<String, Int>,
    currentTimelineListIndex: (String) -> Int?,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    explicitRevealRequestId: Long = 0L,
) {
    @SuppressLint("StateFlowValueCalledInComposition")
    val followSignal by
        remember(appState.ttsController) {
            appState.ttsController.state
                .map(TtsState::conversationFollowSignal)
                .distinctUntilChanged()
        }.collectAsState(
            initial =
                appState.ttsController.state.value
                    .conversationFollowSignal(),
        )
    val ownsSession = appState.ownsTtsAutoReadSession(controller.group.groupIdHex)

    LaunchedEffect(explicitRevealRequestId, ownsSession) {
        if (explicitRevealRequestId > 0L) {
            handle.revealCurrentPassage(
                state = appState.ttsController.state.value,
                ownsSession = ownsSession,
            )
        }
    }

    fun isCurrentTarget(target: ConversationTtsFollowTarget): Boolean =
        handle.policy.isCurrentTarget(target) &&
            appState.ownsTtsAutoReadSession(controller.group.groupIdHex) &&
            appState.ttsController.state.value
                .conversationFollowTargetOrNull() == target

    val observedTarget = followSignal.target
    val targetMessageId = observedTarget?.messageIdHex
    val targetEdit = targetMessageId?.let(controller.editsByTarget::get)
    val targetDeleted = targetMessageId != null && targetMessageId in controller.deletedMessageIds
    LaunchedEffect(
        observedTarget,
        followSignal.isSpeaking,
        ownsSession,
        handle.retryGeneration,
        initialTimelineAnchored,
        targetEdit,
        targetDeleted,
    ) {
        handle.policy.observe(appState.ttsController.state.value, ownsSession)
        if (!initialTimelineAnchored) return@LaunchedEffect
        val request = handle.policy.claimPendingRequest() ?: return@LaunchedEffect
        val target = request.target
        var followSucceeded = false
        try {
            if (!isCurrentTarget(target)) return@LaunchedEffect

            var row = renderedTimeline.firstOrNull { it.record.messageIdHex == target.messageIdHex }
            if (row == null) {
                if (target.timelineAt == 0uL ||
                    !controller.loadTimelineMessageAvailable(target.messageIdHex, target.timelineAt)
                ) {
                    return@LaunchedEffect
                }
                if (!isCurrentTarget(target)) return@LaunchedEffect
                withFrameNanos { }
                if (!isCurrentTarget(target)) return@LaunchedEffect
                row =
                    controller.timeline
                        .firstOrNull { it.record.messageIdHex == target.messageIdHex }
                        ?: return@LaunchedEffect
            }
            if (
                target.messageIdHex in controller.deletedMessageIds ||
                row.projected?.deleted == true ||
                row.projected?.invalidationStatus != null
            ) {
                return@LaunchedEffect
            }
            val currentProjection =
                projectConversationTtsEntry(appState, controller, row.record) ?: return@LaunchedEffect
            if (!isCurrentTarget(target)) return@LaunchedEffect
            if (target.projectionId.isBlank() || currentProjection.projectionId != target.projectionId) {
                return@LaunchedEffect
            }

            val targetIndex = currentTimelineListIndex(target.messageIdHex) ?: return@LaunchedEffect
            val layoutInfo = listState.layoutInfo
            val visibleTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.id }
            val renderedForHeightSample = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
            val visibleTimelineHeights =
                layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                    val liveOlderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                    val timelineIndex = visible.index - 1 - liveOlderHeaderCount
                    renderedForHeightSample
                        .getOrNull(timelineIndex)
                        ?.takeIf {
                            timelineRowKind(it.record, appState.streamingDebugEnabled) == TimelineRowKind.Bubble
                        }?.let { visible.size }
                }
            val itemHeight =
                ReplyNavigation.itemHeightForScrollPx(
                    targetMessageId = target.messageIdHex,
                    measuredItemHeightsByMessageId = timelineItemHeightsPx,
                    visibleTargetHeightPx = visibleTarget?.size,
                    visibleTimelineItemHeightsPx = visibleTimelineHeights,
                )
            if (scrollCoordinator.isFollowingTail) {
                scrollCoordinator.settleReadingAt(currentScrollAnchor())
            }
            followSucceeded =
                followTtsTargetInViewport(
                    target = target,
                    direction = request.direction,
                    anchorAtTop = request.anchorAtTop,
                    itemKey = row.id,
                    targetIndex = targetIndex,
                    estimatedItemHeightPx = itemHeight,
                    listState = listState,
                    scrollCoordinator = scrollCoordinator,
                    sentenceLayouts = handle.sentenceLayouts,
                    claimPreposition = { handle.policy.claimPreposition(target) },
                    claimCorrectiveScroll = { handle.policy.claimCorrectiveScroll(target) },
                    resolveTargetIndex = { currentTimelineListIndex(target.messageIdHex) },
                    isCurrentTarget = { isCurrentTarget(target) },
                    currentScrollAnchor = currentScrollAnchor,
                )
        } finally {
            if (followSucceeded) {
                handle.policy.onFollowSucceeded(target)
            } else if (isCurrentTarget(target)) {
                handle.retryFailedFollowAttempt(target)
            }
        }
    }
}

private suspend fun projectConversationTtsEntry(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    record: AppMessageRecordFfi,
): TtsSpeakableEntry? =
    projectTtsSpeakableEntry(
        message = record,
        editedText = controller.editsByTarget[record.messageIdHex]?.latestText,
        senderDisplayName = appState.displayName(record.sender),
        parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
        mentionDisplayName = appState::mentionSpeechName,
        isGroupMember =
            if (controller.membersLoaded) {
                { bech32 -> appState.isRosterMember(bech32, controller.members) }
            } else {
                null
            },
    )
