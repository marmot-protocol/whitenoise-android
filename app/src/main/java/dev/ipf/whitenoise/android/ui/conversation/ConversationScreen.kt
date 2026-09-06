package dev.ipf.whitenoise.android.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.AgentOperationProjector
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardEligibility
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageSearch
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.core.usesPersistedFailurePresentation
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenConversationTimingEvent
import dev.ipf.whitenoise.android.state.ChatCreateOpenConversationTimingState
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLoadFailureEdge
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.ConversationUnreadJumpState
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.advanceConversationReadAnchor
import dev.ipf.whitenoise.android.state.chatCreateOpenConversationTimingStage
import dev.ipf.whitenoise.android.state.countUnreadIncoming
import dev.ipf.whitenoise.android.state.currentTtsConversationDestination
import dev.ipf.whitenoise.android.state.logUnreadCountDivergence
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.reconcileConversationUnreadJump
import dev.ipf.whitenoise.android.state.reduceChatCreateOpenConversationTiming
import dev.ipf.whitenoise.android.state.unreadCountDivergenceReport
import dev.ipf.whitenoise.android.state.unreadReceivedMentionIds
import dev.ipf.whitenoise.android.ui.MentionDetectionCache
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.canInviteFromEmptyGroup
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.DragSelectionVisibleItem
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
import dev.ipf.whitenoise.android.ui.common.LoadFailurePlacement
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarContentInset
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.anchoredDragSelection
import dev.ipf.whitenoise.android.ui.common.dragSelectionAutoScrollDelta
import dev.ipf.whitenoise.android.ui.common.dragSelectionEndpoint
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.common.loadFailurePlacement
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.conversationComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerAttachmentSheetState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerShareRevision
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberConversationMentionPickerState
import dev.ipf.whitenoise.android.ui.conversation.media.NullableFileSaver
import dev.ipf.whitenoise.android.ui.conversation.media.NullableUriSaver
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlotListSaver
import dev.ipf.whitenoise.android.ui.conversation.media.UriListSaver
import dev.ipf.whitenoise.android.ui.conversation.media.aggregateMessageAttachmentSaveSummaries
import dev.ipf.whitenoise.android.ui.conversation.media.appendPendingMediaSlots
import dev.ipf.whitenoise.android.ui.conversation.media.createImageCaptureFile
import dev.ipf.whitenoise.android.ui.conversation.media.fileProviderUri
import dev.ipf.whitenoise.android.ui.conversation.media.materializeReceiveContentImageUri
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.presentAttachmentSaveOutcome
import dev.ipf.whitenoise.android.ui.conversation.media.rememberDocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.media.saveMessageMediaAttachments
import dev.ipf.whitenoise.android.ui.conversation.media.voicePlaybackKey
import dev.ipf.whitenoise.android.ui.conversation.messages.BatchMessageDeleteDialog
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessageSheet
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageInfoSheet
import dev.ipf.whitenoise.android.ui.conversation.messages.RestoredForwardRequestHost
import dev.ipf.whitenoise.android.ui.conversation.messages.dismissTextSelectionOnOutsideTap
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberTtsQuickTransportViewportLock
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.nostr.publicEventCardRelays
import dev.ipf.whitenoise.android.ui.conversation.share.ContactPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.share.LocationPickerScreen
import dev.ipf.whitenoise.android.ui.conversation.share.PickContactPhoneRow
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.formatLocationShareText
import dev.ipf.whitenoise.android.ui.conversation.share.formatUserShareText
import dev.ipf.whitenoise.android.ui.conversation.share.locationGrantAllowsSharing
import dev.ipf.whitenoise.android.ui.conversation.share.readSharedContact
import dev.ipf.whitenoise.android.ui.documentMentionsAccount
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.medialibrary.rememberSharedMediaTiles
import dev.ipf.whitenoise.android.ui.medialibrary.toViewerPages
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private data class ConversationSearchScrollAnchor(
    val bookmark: ConversationScrollBookmark,
    val match: ConversationSearchMatch?,
)

/**
 * UI-only navigation state kept behind one remembered reference so the already
 * large [ConversationScreen] method does not keep every independent snapshot
 * state and job in its verifier register set for the full composition.
 */
private class ConversationNavigationState(
    initialFollowedLatestId: String?,
    initialSeedTailAwaitingAuthoritative: Boolean,
) {
    var lastFollowedLatestId by mutableStateOf(initialFollowedLatestId)
    var seedTailAwaitingAuthoritative by mutableStateOf(initialSeedTailAwaitingAuthoritative)
    var initialTimelineLoadStarted by mutableStateOf(false)
    var initialTimelineBackfillNoProgress by mutableStateOf(false)
    var initialTimelineBackfillRetryGeneration by mutableLongStateOf(0L)
    val targetHighlight = MessageTargetHighlightLifecycle()
    val targetNavigation = MessageTargetNavigationOwner()
    var navigateReplyJob by mutableStateOf<Job?>(null)
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchPinnedMatchId by mutableStateOf<String?>(null)
    var searchJob by mutableStateOf<Job?>(null)
    var preSearchScrollAnchor by mutableStateOf<ConversationSearchScrollAnchor?>(null)
    var historySearchMatches by mutableStateOf<List<ConversationSearchMatch>?>(null)
    val timelineItemHeightsPx = mutableStateMapOf<String, Int>()
    val searchFocusRequester = FocusRequester()

    /** Cancels navigation work whose lifetime is owned by this remembered route state. */
    fun cancelJobs() {
        searchJob?.cancel()
        navigateReplyJob?.cancel()
        targetNavigation.cancel()
        targetHighlight.clear()
    }
}

/** Checks the complete measured row against the usable lazy-list viewport. */
private fun LazyListLayoutInfo.isItemFullyVisible(index: Int): Boolean {
    val item = visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= viewportStartOffset && item.offset + item.size <= viewportEndOffset
}

/** Captures only the bounded state needed to decide whether startup backfill can progress. */
private fun ConversationController.initialTimelineBackfillSnapshot() =
    ConversationInitialTimelineBackfillSnapshot(
        hasRenderableRows = timeline.any { !MessageProjector.isEdit(it.record) },
        hasMoreBefore = hasMoreBefore,
        loadInFlight = isLoading || isLoadingOlder,
        hasLoadFailure = error != null,
        rawWindowMessageIds = timeline.map { it.id },
    )

private val InitialTimelineBackfillNoProgressError =
    ErrorPresentation(
        message = AppText.Resource(R.string.error_loaded_content_kept),
        report =
            "Operation: CONVERSATION_INITIAL_BACKFILL_NO_PROGRESS\n" +
                "No backward timeline progress was observed.",
    )

/** Remembers navigation state per controller and cancels all controller-owned jobs on disposal. */
@Composable
private fun rememberConversationNavigationState(
    controller: ConversationController,
    initialFollowedLatestId: String?,
    initialSeedTailAwaitingAuthoritative: Boolean,
): ConversationNavigationState {
    val state =
        remember(controller) {
            ConversationNavigationState(
                initialFollowedLatestId = initialFollowedLatestId,
                initialSeedTailAwaitingAuthoritative = initialSeedTailAwaitingAuthoritative,
            )
        }
    DisposableEffect(state) {
        onDispose(state::cancelJobs)
    }
    return state
}

// Maximum images per multi-pick. The Android Photo Picker enforces this
// cap on the system dialog side; 10 keeps the album payload bounded
// (10 * 1920px JPEG ≈ a few MB encrypted) without feeling artificially low.
// Approximate clearance the conversation composer occupies above the
// navigation bar. Used by ConversationScreen to push the global
// snackbar host above the composer so toasts don't intercept touches
// on the message input — see [LocalSnackbarBottomInset] + issue #122.
// 72.dp covers the single-line composer plus its vertical padding.
// Only a seed for the first frames: the bottom bar's measured height
// replaces it as soon as layout runs (#796), so multi-line composers,
// reply/edit banners, and the invite bar stay cleared exactly.
private val COMPOSER_SNACKBAR_INSET = 72.dp

private const val MEDIA_PICKER_MAX_ITEMS = 10

/** Maps recorder failures to localized, privacy-safe user presentation. */
private fun presentVoiceRecordingFailure(
    appState: WhiteNoiseAppState,
    throwable: Throwable,
    voiceTooShortMessage: String,
) {
    when {
        throwable is IllegalStateException && throwable.message == "voice recording too short" ->
            appState.present(voiceTooShortMessage)
        throwable.message?.contains("audio is in use", ignoreCase = true) == true ->
            appState.presentFailure(
                R.string.voice_message_recording_failed,
                "VOICE_RECORDING_START",
                throwable,
                AppText.Resource(R.string.voice_message_microphone_busy),
            )
        else ->
            appState.presentFailure(
                R.string.voice_message_recording_failed,
                "VOICE_RECORDING",
                throwable,
            )
    }
}

/** Renders the shared retryable conversation-load error surface. */
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
private fun ConversationLoadErrorContent(
    error: ErrorPresentation,
    onRetry: () -> Unit,
) {
    ErrorContent(
        title = stringResource(R.string.couldnt_load_conversation),
        error = error,
        onRetry = onRetry,
    )
}

/** Inserts an inline load error only at the failing timeline edge. */
private fun LazyListScope.conversationLoadErrorItem(
    key: String,
    error: ErrorPresentation?,
    placement: LoadFailurePlacement,
    errorEdge: ConversationLoadFailureEdge,
    targetEdge: ConversationLoadFailureEdge,
    onRetry: () -> Unit,
) {
    if (error == null || placement != LoadFailurePlacement.Inline || errorEdge != targetEdge) return
    item(key = key) {
        InlineErrorBanner(
            error = error,
            onRetry = onRetry,
        )
    }
}

/** Whether adjacent timeline items participate in one visible message-bubble sender run. */
internal fun conversationBubbleRowsShareSenderRun(
    first: TimelineMessage,
    second: TimelineMessage,
    streamingDebugEnabled: Boolean,
    deletedMessageIds: Set<String>,
): Boolean =
    timelineItemRendersAsConversationBubble(first, streamingDebugEnabled, deletedMessageIds) &&
        timelineItemRendersAsConversationBubble(second, streamingDebugEnabled, deletedMessageIds) &&
        GroupProjector.messagesShareTranscriptSenderRun(
            firstSender = first.record.sender,
            firstRecordedAt = first.record.recordedAt,
            secondSender = second.record.sender,
            secondRecordedAt = second.record.recordedAt,
            sameDay = !differentDay(first.record.recordedAt, second.record.recordedAt),
        )

/** Classifies rows that participate in adjacent sender grouping as message bubbles. */
private fun timelineItemRendersAsConversationBubble(
    item: TimelineMessage,
    streamingDebugEnabled: Boolean,
    deletedMessageIds: Set<String>,
): Boolean =
    when (timelineRowKind(item.record, streamingDebugEnabled)) {
        TimelineRowKind.Bubble -> true
        TimelineRowKind.AgentOperation ->
            !shouldRenderDedicatedAgentOperationRow(
                projectedDeleted = item.projected?.deleted == true,
                optimisticallyDeleted = MessageProjector.isDeleted(item.record.messageIdHex, deletedMessageIds),
                invalidated = item.projected?.invalidationStatus != null,
            ) ||
                AgentOperationProjector.project(item.record) == null
        TimelineRowKind.GroupSystem,
        TimelineRowKind.DebugRow,
        -> false
    }

private data class ConversationBatchSelectionUiState(
    val selections: List<BatchMessageSelection>,
    val actionItems: List<BatchMessageActionItem>,
    val copyText: String,
    val forwardPayloads: List<ForwardMessagePayload>,
    val forwardBlockedReason: ForwardBlockedReason?,
    val deleteBreakdown: BatchDeleteBreakdown,
    val actionAvailability: BatchSelectionActionAvailability,
)

/** Derives stable batch actions from the current account-scoped message selection. */
@Composable
private fun rememberConversationBatchSelectionUiState(
    selectedMessages: Map<String, BatchMessageSelection>,
    chatId: String,
    activeAccountRef: String?,
    runtimeGeneration: Int,
    composerGate: ComposerGate,
    appState: WhiteNoiseAppState,
): ConversationBatchSelectionUiState {
    val selections by
        remember(chatId, activeAccountRef, runtimeGeneration) {
            derivedStateOf { orderedBatchSelections(selectedMessages.values) }
        }
    return remember(selections, appState.profileRevisionForCompose, composerGate) {
        val actionItems =
            selections.map { selection ->
                selection.action.copy(senderDisplayName = appState.displayName(selection.action.senderId))
            }
        val forwardPayloads = batchForwardPayloads(actionItems)
        ConversationBatchSelectionUiState(
            selections = selections,
            actionItems = actionItems,
            copyText = batchCopyText(actionItems),
            forwardPayloads = forwardPayloads,
            forwardBlockedReason =
                if (forwardPayloads.isEmpty()) {
                    actionItems.firstNotNullOfOrNull(BatchMessageActionItem::forwardBlockedReason)
                } else {
                    null
                },
            deleteBreakdown = batchDeleteBreakdown(actionItems),
            actionAvailability = batchSelectionActionAvailability(actionItems, composerGate),
        )
    }
}

/**
 * Read anchor stored as the message id of the deepest row the user has
 * settled on. The candidate id is a key so an optimistic UUID being replaced
 * in the same list slot still advances the anchor to the confirmed message.
 * A missing durable watermark rebases only at the fully loaded tail; while a
 * newer page exists it may be off-window and must remain monotonic.
 */
@Composable
private fun rememberConversationReadAnchor(
    controller: ConversationController,
    entrySessionIdentity: Any,
    renderedTimeline: List<TimelineMessage>,
    listState: LazyListState,
    hasOlderHeader: Boolean,
    hasInlineTopError: Boolean,
    initialTimelineAnchored: Boolean,
): MutableState<String?> {
    val readAnchor = remember(entrySessionIdentity) { mutableStateOf(controller.lastReadMessageId) }
    val renderedSize = renderedTimeline.size
    val currentHighestVisibleTimelineIndex by
        remember(listState, renderedSize, hasOlderHeader, hasInlineTopError) {
            derivedStateOf {
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) return@derivedStateOf -1
                val olderHeader = if (hasOlderHeader) 1 else 0
                val inlineTopError = if (hasInlineTopError) 1 else 0
                // LazyColumn layout: [top spacer][maybe top error]
                // [maybe older-loading][timeline items].
                // Tail clearance is content padding, not a zero-sized list item.
                val firstTimelineListIndex = 1 + inlineTopError + olderHeader
                (visible.last().index - firstTimelineListIndex)
                    .coerceAtMost(renderedSize - 1)
            }
        }
    val currentHighestVisibleMessageId =
        renderedTimeline
            .getOrNull(currentHighestVisibleTimelineIndex)
            ?.record
            ?.messageIdHex
    LaunchedEffect(
        controller,
        initialTimelineAnchored,
        currentHighestVisibleTimelineIndex,
        currentHighestVisibleMessageId,
        controller.lastReadMessageId,
    ) {
        val idx =
            conversationReadAnchorCandidateIndex(
                initialTimelineAnchored = initialTimelineAnchored,
                highestVisibleTimelineIndex = currentHighestVisibleTimelineIndex,
            )
        if (idx < 0) return@LaunchedEffect
        readAnchor.value =
            advanceConversationReadAnchor(
                timeline = renderedTimeline,
                currentUiAnchorId = readAnchor.value,
                durableAnchorId = controller.lastReadMessageId,
                candidateIndex = idx,
                canRebaseMissingAnchor =
                    idx == renderedTimeline.lastIndex &&
                        !controller.hasMoreAfterTimeline,
            )
    }
    return readAnchor
}

/** Renders one account-owned conversation and coordinates its route-stable presentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    appState: WhiteNoiseAppState,
    chat: ChatListItem,
    controller: ConversationController,
    onBack: () -> Unit,
    // When opened from a chat-list message-body search hit (issue #290), the
    // matched message id to scroll to and briefly highlight once the timeline
    // has paged it in. Null for every normal open path.
    focusMessageId: String? = null,
    // Advances for repeated shell-level focus requests to the same message.
    focusMessageRequestId: Long = 0L,
    // Non-null only for a transport-body return to a live TTS session.
    ttsFocusSessionId: Long? = null,
    // Non-zero when opened by tapping a message notification. Each tap gets a
    // fresh id so an already-mounted conversation re-runs its first-unread
    // anchor; it also implies current membership while verification catches up.
    notificationOpenRequestId: Long = 0L,
    // Persist the notification's read-through cursor only after this screen
    // freezes the pre-read projection. This keeps the entry divider stable
    // without giving up the durable notification-tap read behavior (#1016).
    notificationReadThroughMessageId: String? = null,
    onNotificationUnreadBoundaryCaptured: (String) -> Unit = {},
    onNotificationTimelineVisibilityChanged: (Boolean) -> Unit = {},
    onFirstFrameCommitted: () -> Unit = {},
    // True only when this conversation was just created in the same navigation
    // step (issue #321) — drives a one-shot composer focus + keyboard raise so
    // the user can type the first message without an extra tap. False for row
    // taps, notification routing, and search hits.
    justCreated: Boolean = false,
    // True only when the opener knows this conversation is a newly-created DM.
    // The live roster can briefly report 0/1 members before the peer arrives;
    // keep that transient state in the DM presentation instead of falling into
    // the group subtitle branch (#998).
    openedAsDmHint: Boolean = false,
    // Route-owned presentation changes stay frozen through the first settled
    // frame so late top/bottom chrome hydration cannot retarget the slide.
    routeTransitionInProgress: Boolean = false,
    // Scroll position captured when the user last left this chat while reading
    // history (issue #1107). Null when none was saved or they left near-bottom.
    restoredScrollSnapshot: ConversationScrollSnapshot? = null,
    onSaveScrollSnapshot: (ConversationScrollSnapshot?) -> Unit = {},
    onOpenConversation: (ChatListItem, Boolean) -> Unit = { _, _ -> },
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ -> onOpenConversation(item, false) },
    onGroupCreateFlowSuperseded: () -> Unit = {},
    onTtsTransportBodyClick: (() -> Unit)? = null,
) {
    WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
    // The conversation's own account. Identical to the active account except
    // during a notification-routed early open (#586), while the switch is
    // still landing; conversation-scoped state keys and self-identity follow
    // the conversation, not the in-flight active ref, so the open neither
    // misattributes "is me" nor resets scroll/selection state when the
    // active ref catches up.
    val conversationAccountRef = controller.boundAccountRef
    val conversationSelfAccountIdHex = controller.boundAccountIdHex
    // Push the global snackbar host above the conversation composer so
    // a toast (e.g. the post-invite-accept confirmation) doesn't
    // overlap and intercept touches on the message input. Resets to
    // zero on dispose so other surfaces aren't affected. Issue #122.
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    val snackbarContentInset = LocalSnackbarContentInset.current
    // Keyed on chat.id so that a back-to-back conversation push (Compose
    // reusing the same node across nav) re-runs the effect: the
    // previous chat's onDispose may not have fired before the next
    // enters, leaving the inset at zero on a stale snackbar host.
    // Seeds the resting-composer estimate; the bottom bar's measured
    // height takes over on first layout (#796).
    DisposableEffect(chat.id) {
        snackbarBottomInset.value = COMPOSER_SNACKBAR_INSET
        onDispose { snackbarBottomInset.value = 0.dp }
    }
    // Capture the unread boundary at chat open. Stays fixed for this controller
    // so the divider doesn't move as messages are marked read. A new controller
    // or a fresh notification-open request starts a distinct entry session.
    val entryUnreadSessionIdentity =
        remember(controller, notificationOpenRequestId) {
            controller to notificationOpenRequestId
        }
    val projectedEntryUnreadCount = chat.unreadCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val entryProjectionAvailable = chat.projection != null
    val entryUnreadSnapshot =
        rememberConversationEntryUnreadSnapshot(
            controllerIdentity = entryUnreadSessionIdentity,
            projectionUnread = projectedEntryUnreadCount,
            projectionFirstUnreadMessageId = chat.projection?.firstUnreadMessageIdHex,
            projectionAvailable = entryProjectionAvailable,
            // A one-row chat-list seed cannot establish the authoritative unread
            // boundary. Freeze only after MDK's first real page replaces it.
            timeline = if (controller.initialTimelineSeedActive) emptyList() else controller.timeline,
            readAnchorMessageId = chat.projection?.lastReadMessageIdHex,
        )
    val entryUnreadCount = entryUnreadSnapshot.count
    val entryFirstUnreadMessageId = entryUnreadSnapshot.firstUnreadMessageId
    LaunchedEffect(
        entryUnreadSessionIdentity,
        notificationReadThroughMessageId,
        entryUnreadSnapshot.projectionCaptured,
    ) {
        val messageId = notificationReadThroughMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (entryUnreadSnapshot.projectionCaptured) {
            onNotificationUnreadBoundaryCaptured(messageId)
        }
    }
    val collapseLongMessages = appState.collapseLongMessagesInGroup(chat.group.groupIdHex)
    // When the developer streaming-debug toggle flips, re-publish the timeline.
    // Turning it off drops the transient QUIC debug rows so they don't linger.
    LaunchedEffect(controller, appState.streamingDebugEnabled) {
        controller.refreshStreamingDebugPresentation()
    }
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on the controller as well as chat.id so the same shared group under
    // another account cannot inherit this account's details route.
    var showDetails by remember(controller, chat.id) { mutableStateOf(false) }
    // Notification suppression must follow the visible *timeline*, not merely an
    // open chat. While group details/settings (and its sub-screens) are up, the
    // user can't see incoming messages, so those must notify — lift the
    // active-conversation suppression for the group while details are showing
    // and restore it on return to the timeline.
    LaunchedEffect(controller, showDetails) {
        onNotificationTimelineVisibilityChanged(!showDetails)
    }
    var pendingTopBarLeaveAction by remember { mutableStateOf<LeaveAction?>(null) }
    // Sole-admin Leave gate: a sole admin with other members can't leave until
    // they hand admin to someone else. Instead of the old toast-only dead end,
    // the Leave action surfaces a "Transfer admin first" dialog that routes
    // into the group details transfer picker (#417, adversarial review).
    var showTransferAdminFirst by remember { mutableStateOf(false) }
    // Set when the user opts to transfer from that dialog: opens details with
    // the transfer picker auto-expanded. Keyed on chat.id so it doesn't leak
    // across a conversation switch.
    var openTransferOnDetails by remember(chat.id) { mutableStateOf(false) }
    // Empty newly-created groups should route users into the existing member
    // invite flow instead of carrying a duplicate picker in the create sheet.
    var openAddMemberOnDetails by remember(chat.id) { mutableStateOf(false) }
    // Re-open after back-to-list should land where the reader left off only
    // when fully read. Unread, search-hit, just-created, and notification opens
    // let their dedicated unread/newest/focus anchor own the position.
    val scrollRestore =
        restoredScrollSnapshot?.takeIf {
            shouldRestoreConversationScrollSnapshot(
                focusMessageId = focusMessageId,
                justCreated = justCreated,
                notificationOpenRequestId = notificationOpenRequestId,
                entryUnreadCount = entryUnreadCount,
            )
        }
    val positionalScrollRestore =
        scrollRestore?.takeIf {
            it.anchorItemId.isNullOrBlank() && it.anchorMessageIdHex.isNullOrBlank()
        }
    val firstFrameSeed =
        remember(controller, notificationOpenRequestId) {
            conversationFirstFrameSeedPresentation(
                controller = controller,
                entryUnreadCount = entryUnreadCount,
                projectionAvailable = entryProjectionAvailable,
                hasScrollRestore = scrollRestore != null,
                hasFocusedDestination = focusMessageId != null || ttsFocusSessionId != null,
                notificationOpenRequestId = notificationOpenRequestId,
            )
        }
    val listState =
        key(controller) {
            rememberLazyListState(
                initialFirstVisibleItemIndex =
                    positionalScrollRestore?.firstVisibleItemIndex ?: firstFrameSeed.initialListIndex,
                initialFirstVisibleItemScrollOffset = positionalScrollRestore?.firstVisibleItemScrollOffset ?: 0,
            )
        }
    val ttsQuickTransportViewportLock = rememberTtsQuickTransportViewportLock(listState)
    var unreadJumpState by
        remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration) {
            mutableStateOf(ConversationUnreadJumpState())
        }
    val scrollCoordinator =
        remember(
            controller,
            listState,
            chat.id,
            conversationAccountRef,
            appState.runtimeGeneration,
        ) {
            ConversationScrollCoordinator(
                writer = LazyListConversationScrollWriter(listState),
                initialMode =
                    if (scrollRestore != null) {
                        ConversationScrollMode.ReadingHistory(
                            anchorMessageId = scrollRestore.anchorMessageIdHex,
                            pixelOffset = scrollRestore.firstVisibleItemScrollOffset,
                        )
                    } else {
                        ConversationScrollMode.FollowingTail
                    },
                onExplicitNavigation = {
                    unreadJumpState = unreadJumpState.suppressCurrentStack()
                },
            )
        }

    val ttsFollowHandle = rememberConversationTtsFollowHandle(controller.group.groupIdHex)
    val postInitialReanchorGate =
        remember(controller, listState) {
            ConversationPostInitialReanchorGate()
        }
    val bottomChromeHeightObserver =
        remember(chat.id) {
            ConversationBottomChromeHeightObserver()
        }
    var measuredBottomChromeHeightPx by remember(controller) { mutableStateOf<Int?>(null) }
    var bottomInputRevision by remember(controller) { mutableLongStateOf(0L) }
    var routePresentationFrozen by
        remember(controller) { mutableStateOf(routeTransitionInProgress) }
    val freezeRoutePresentation =
        conversationRoutePresentationShouldFreeze(
            routeTransitionInProgress = routeTransitionInProgress,
            retainedPresentationFreeze = routePresentationFrozen,
        )
    // Single conversation-level owner of which message's action menu is open, so
    // only one popover can be open at a time. With the keyboard up the menu is
    // non-focusable (#284), so long-pressing several bubbles would otherwise
    // stack several popovers; deriving each bubble's open state from this one id
    // makes opening one close any other.
    var openActionMenuId by remember(chat.id) { mutableStateOf<String?>(null) }
    DismissMessageActionMenuOnScroll(listState) {
        openActionMenuId = null
    }
    // Partial text selection is independent from batch message selection. Only
    // one bubble can own the native SelectionContainer at a time.
    var textSelectionMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    var textSelectionBubbleBounds by remember(chat.id) { mutableStateOf<Rect?>(null) }

    /** Clears the single conversation-owned native text selection. */
    fun clearTextSelection() {
        textSelectionMessageId = null
        textSelectionBubbleBounds = null
    }
    // Selection is conversation-owned because the contextual top bar, back
    // handling, forwarding sheet, and rows all consume the same stable ids.
    // Each value snapshots the record/action projection so cap-trimmed rows stay
    // selected while the user scrolls deeper into history. This remains transient
    // composition state deliberately: serializing decrypted message snapshots into
    // Android saved state would extend their lifetime and privacy footprint.
    val selectedMessages =
        remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration) {
            mutableStateMapOf<String, BatchMessageSelection>()
        }
    var batchForwardSheetOpen by
        remember(chat.id, conversationAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var batchAttachmentSaveInFlight by
        remember(chat.id, conversationAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var batchInfoSelection by
        remember(chat.id, conversationAccountRef, appState.runtimeGeneration) {
            mutableStateOf<BatchMessageSelection?>(null)
        }
    var showBatchDeleteConfirm by
        remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var batchDeleteInFlight by
        remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    val batchDeleteSubmissionGuard =
        remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration) {
            BatchDeleteSubmissionGuard()
        }
    var batchDeleteRetryState by
        remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration) {
            mutableStateOf<BatchDeleteRetryState?>(null)
        }
    var initialTimelineAnchored by
        // Reveal from the first frame only when the authoritative page is already
        // loaded (the preloaded chat-list-tap path anchors at the tail immediately).
        // A direct open still awaiting its page must stay hidden until
        // reconciliation scrolls to the tail, otherwise the grown page lays out at
        // the clamped top spacer and flashes the oldest rows before jumping down.
        remember(controller, notificationOpenRequestId) {
            mutableStateOf(firstFrameSeed.anchorTailImmediately && !firstFrameSeed.awaitingAuthoritativeTimeline)
        }
    var seededTailAlignmentCommitted by
        remember(controller, notificationOpenRequestId) {
            mutableStateOf(!firstFrameSeed.anchorTailImmediately)
        }
    var seededTailAlignmentRecoveryVisible by
        remember(controller, notificationOpenRequestId) { mutableStateOf(false) }
    var seededTailAlignmentRetryGeneration by
        remember(controller, notificationOpenRequestId) { mutableLongStateOf(0L) }
    val transcriptVisibilityCommitted by
        remember(controller, notificationOpenRequestId, listState, firstFrameSeed.anchorTailImmediately) {
            derivedStateOf {
                conversationTranscriptVisibilityCommitted(
                    initialTimelineAnchored = initialTimelineAnchored,
                    anchorTailImmediately = firstFrameSeed.anchorTailImmediately,
                    seededTailAlignmentCommitted = seededTailAlignmentCommitted,
                    viewportMeasured = listState.layoutInfo.viewportSize.height > 0,
                    canScrollForward = listState.canScrollForward,
                )
            }
        }

    // First-frame completion waits for the same committed visibility predicate
    // as paint, accessibility, and performance selectors. An oversized cached
    // tail is not useful until its measured physical-end correction lands.
    LaunchedEffect(chat.id, notificationOpenRequestId, transcriptVisibilityCommitted) {
        if (notificationOpenRequestId == 0L || !transcriptVisibilityCommitted) return@LaunchedEffect
        withFrameNanos { }
        onFirstFrameCommitted()
    }

    ConversationTtsAutoReadEffects(
        appState = appState,
        controller = controller,
        chatId = chat.id,
        entryUnreadCount = entryUnreadCount,
        entryFirstUnreadMessageId = entryFirstUnreadMessageId,
        initialTimelineAnchored = initialTimelineAnchored,
    )
    val navigationState =
        rememberConversationNavigationState(
            controller = controller,
            initialFollowedLatestId = firstFrameSeed.latestTimelineId,
            initialSeedTailAwaitingAuthoritative = firstFrameSeed.awaitingAuthoritativeTimeline,
        )
    // Id of the newest row the bottom-follow has reacted to. A real append
    // gives a new last id while the previous one stays in the list; an
    // older-page load trims the newest rows, so the previous id is gone and
    // no follow fires. Keyed on id (not recordedAt) to survive same-second tails.
    // In-chat search (#292). Opening from the overflow menu swaps the top
    // bar into an inline search field; closing it restores the normal bar.
    // `searchPinnedMatchId` keeps the active match anchored to a concrete
    // message id so the N/M cursor follows that message as older pages load
    // and the match set grows. `searchJob` serializes scroll-jump coroutines
    // the same way `navigateReplyJob` does for reply navigation.
    // The durable local message position lets close-search move the bounded
    // subscription window back before the coordinator restores the exact
    // logical bookmark and viewport offset.
    // Jump-to-newest plumbing.
    //
    // Badge = incoming messages newer than the highest-index timeline row the
    // user has ever had on screen during this composition. The high-water
    // mark only INCREASES, so scrolling back up past read messages doesn't
    // resurrect the badge.
    //
    //   HWM advances when the viewport reaches a new highest-visible row.
    //   New incoming arrivals (which extend the timeline beyond HWM) bump
    //   the badge. On chat re-entry, the auto-scroll's snap to the bottom
    //   immediately advances HWM to the last timeline index, so the badge
    //   shows 0 — matching the convention that an "open chat" is read up to
    //   the visible row, not the last delivered row.
    // Edits (kind-1009) are derived state, not chat — they mutate the
    // original message's body via [editsByTarget] and must not occupy a slot
    // in the lazy list. A naive `return@items` still reserves the slot, which
    // (combined with `Arrangement.spacedBy`) leaves a visible gap. Filter
    // them out up front and base every index/scroll calculation on the
    // filtered list so what we count matches what we render.
    val renderedTimeline =
        remember(controller.timeline) {
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        }
    val hasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
    val olderHeaderCount = if (hasOlderHeader) 1 else 0
    val loadFailurePlacement = loadFailurePlacement(controller.error != null, renderedTimeline.isNotEmpty())
    val hasInlineTopError =
        loadFailurePlacement == LoadFailurePlacement.Inline &&
            controller.errorEdge == ConversationLoadFailureEdge.TOP
    val inlineTopErrorCount = if (hasInlineTopError) 1 else 0
    val leadingStructuralRowCount = controller.conversationLeadingStructuralRowCount(renderedTimeline.size)
    val conversationMedia =
        rememberSharedMediaTiles(
            controller = controller,
            appState = appState,
            myAccountId = conversationSelfAccountIdHex,
        )
    val conversationVisualPages = remember(conversationMedia.visuals) { conversationMedia.visuals.toViewerPages() }
    val renderedTimelineAnchorKeys =
        remember(renderedTimeline) {
            renderedTimeline.map { it.id to it.record.messageIdHex }
        }
    val mediaCacheRevision by appState.mediaCacheRevision.collectAsState()
    val forwardEligibilityExpiries =
        remember(renderedTimeline) {
            renderedTimeline.mapNotNull { it.record.retentionExpiresAt?.takeIf { expiry -> expiry > 0uL } }
        }
    val eligibilityNowSeconds = rememberForwardEligibilityNowSeconds(forwardEligibilityExpiries)
    val selectableMessageProjections =
        remember(
            renderedTimeline,
            controller.deletedMessageIds,
            controller.editsByTarget,
            conversationSelfAccountIdHex,
            // Moderation capability rides on these; re-snapshot when they move
            // so a promotion/demotion or roster verification is reflected.
            controller.isSelfAdmin,
            controller.canSendMessages,
            mediaCacheRevision,
            eligibilityNowSeconds,
        ) {
            renderedTimeline
                .mapNotNull { item ->
                    val record = item.record
                    val messageId = record.messageIdHex
                    if (
                        !isBatchSelectableMessage(
                            messageId = messageId,
                            userVisibleMessage = MessageProjector.isChatKind(record.kind),
                            committedMessage = item.status == MessageStatus.Received || item.status == MessageStatus.Sent,
                            projectedDeleted = item.projected?.deleted == true,
                            deletedMessageIds = controller.deletedMessageIds,
                        )
                    ) {
                        return@mapNotNull null
                    }
                    val invalidated = item.projected?.invalidationStatus != null
                    val persistedFailure =
                        item.projected?.let(::usesPersistedFailurePresentation) == true
                    val editedText =
                        controller.editsByTarget[messageId]
                            ?.latestText
                            ?.takeIf { record.kind == 9uL }
                    val mediaReferences = controller.mediaReferencesFor(item)
                    val forwardEligibility =
                        if (invalidated || persistedFailure) {
                            ForwardEligibility.Blocked(ForwardBlockedReason.Unsupported)
                        } else {
                            MessageProjector.forwardEligibility(
                                message = record,
                                mediaReferences = mediaReferences,
                                editedText = editedText,
                                cachedAttachmentIndices =
                                    mediaReferences.indices
                                        .filterTo(mutableSetOf()) { attachmentIndex ->
                                            controller.hasCachedAttachment(messageId, attachmentIndex)
                                        },
                                nowSeconds = eligibilityNowSeconds,
                            )
                        }
                    BatchMessageSelection(
                        action =
                            BatchMessageActionItem(
                                messageId = messageId,
                                senderId = record.sender,
                                senderDisplayName = record.sender,
                                copyableText =
                                    if (persistedFailure) {
                                        null
                                    } else {
                                        MessageProjector.copyableText(record, editedText)
                                    },
                                forwardableText = if (invalidated) null else MessageProjector.forwardableText(record, editedText),
                                // Same authoritative accessor the single-message
                                // surface and the mutation guard use, so bulk
                                // routing never diverges from per-message policy.
                                canDeleteForEveryone = controller.deleteCapabilityFor(record).canDeleteForEveryone,
                                hasSaveableMedia = mediaReferences.isNotEmpty(),
                                forwardPayload = (forwardEligibility as? ForwardEligibility.Eligible)?.payload,
                                forwardBlockedReason =
                                    (forwardEligibility as? ForwardEligibility.Blocked)?.reason,
                            ),
                        record = record,
                        status = item.status,
                        timelineOrder = item.timelineOrder,
                    )
                }.associateBy { it.action.messageId }
        }
    // Display names are deliberately NOT resolved here: this map spans the
    // whole loaded timeline and profileRevisionForCompose bumps on any profile
    // resolution anywhere, so an eager per-entry displayName() re-ran an O(n)
    // pass (plus the downstream invalid-ids pass and reconcile effect) on
    // every bump. Names are only shown for the selected few — resolved below.
    val selectableMessages = selectableMessageProjections
    val orderedTimelineIds = remember(renderedTimeline) { renderedTimeline.map { it.id } }
    val timelineSelectionById =
        remember(renderedTimeline, selectableMessages) {
            renderedTimeline
                .mapNotNull { item ->
                    selectableMessages[item.record.messageIdHex]?.let { item.id to it }
                }.toMap()
        }
    LaunchedEffect(controller, controller.recoveryProjectionGeneration, renderedTimeline) {
        val generation = controller.recoveryProjectionGeneration
        if (generation > 0L) {
            withFrameNanos { }
            appState.recoveryDiagnostics.recordFirstVisibleFrame(generation)
        }
    }
    val timelineIdSet = remember(orderedTimelineIds) { orderedTimelineIds.toSet() }
    val selectableTimelineIds = remember(timelineSelectionById) { timelineSelectionById.keys }
    val dragSelectionDensity = LocalDensity.current
    val dragEdgeThresholdPx = with(dragSelectionDensity) { 56.dp.toPx() }
    val dragMaxScrollStepPx = with(dragSelectionDensity) { 18.dp.toPx() }
    var transcriptWindowTop by
        remember(controller, conversationAccountRef, appState.runtimeGeneration) { mutableFloatStateOf(0f) }
    var transcriptHeightPx by
        remember(controller, conversationAccountRef, appState.runtimeGeneration) { mutableFloatStateOf(0f) }
    var dragAnchorTimelineId by
        remember(controller, conversationAccountRef, appState.runtimeGeneration) { mutableStateOf<String?>(null) }
    var dragPointerWindowY by
        remember(controller, conversationAccountRef, appState.runtimeGeneration) { mutableStateOf<Float?>(null) }

    /** Captures the logical first visible message against the latest filtered timeline. */
    fun currentScrollAnchor(): ConversationScrollAnchor {
        val liveRenderedTimeline = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val liveHasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
        return conversationScrollAnchor(
            listState = listState,
            renderedItemIds = liveRenderedTimeline.map { it.id },
            renderedMessageIds = liveRenderedTimeline.map { it.record.messageIdHex },
            hasOlderHeader = liveHasOlderHeader,
            hasInlineTopError =
                liveRenderedTimeline.isNotEmpty() &&
                    controller.error != null &&
                    controller.errorEdge == ConversationLoadFailureEdge.TOP,
        )
    }

    @Suppress("ReturnCount") // Guard clauses keep invalid live-timeline gesture state explicit.
    fun updateMessageDragSelection(pointerWindowY: Float): Boolean {
        val anchorId = dragAnchorTimelineId ?: return false
        val endpointId =
            dragSelectionEndpoint(
                listState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                    val id = visible.key as? String
                    id
                        ?.takeIf(timelineIdSet::contains)
                        ?.let {
                            DragSelectionVisibleItem(
                                key = it,
                                start = visible.offset.toFloat(),
                                end = (visible.offset + visible.size).toFloat(),
                            )
                        }
                },
                pointerY = pointerWindowY - transcriptWindowTop,
            ) ?: return false
        if (endpointId == anchorId && selectedMessages.isEmpty()) return false
        val nextTimelineIds =
            anchoredDragSelection(
                orderedIds = orderedTimelineIds,
                eligibleIds = selectableTimelineIds,
                anchorId = anchorId,
                endpointId = endpointId,
            )
        selectedMessages.clear()
        nextTimelineIds.forEach { timelineId ->
            timelineSelectionById[timelineId]?.let { selection ->
                selectedMessages[selection.action.messageId] = selection
            }
        }
        return true
    }

    fun finishMessageDrag(clearSelection: Boolean) {
        val hadActiveDrag = dragAnchorTimelineId != null
        dragAnchorTimelineId = null
        dragPointerWindowY = null
        if (clearSelection) selectedMessages.clear()
        if (hadActiveDrag) scrollCoordinator.settleReadingAt(currentScrollAnchor())
    }

    LaunchedEffect(orderedTimelineIds, selectableTimelineIds) {
        val anchorId = dragAnchorTimelineId ?: return@LaunchedEffect
        if (anchorId !in orderedTimelineIds || anchorId !in selectableTimelineIds) {
            finishMessageDrag(clearSelection = true)
        }
    }

    LaunchedEffect(dragAnchorTimelineId, listState) {
        while (dragAnchorTimelineId != null) {
            withFrameNanos { }
            val pointerWindowY = dragPointerWindowY ?: continue
            val pointerY = pointerWindowY - transcriptWindowTop
            val scrollDelta =
                dragSelectionAutoScrollDelta(
                    pointerY = pointerY,
                    viewportStart = 0f,
                    viewportEnd = transcriptHeightPx,
                    edgeThreshold = dragEdgeThresholdPx,
                    maxStep = dragMaxScrollStepPx,
                )
            if (scrollDelta != 0f) {
                listState.scrollBy(scrollDelta)
                updateMessageDragSelection(pointerWindowY)
            }
        }
    }
    val invalidVisibleMessageIds =
        remember(renderedTimeline, selectableMessages) {
            renderedTimeline
                .asSequence()
                .map { it.record.messageIdHex }
                .filter { it.isNotBlank() && it !in selectableMessages }
                .toSet()
        }
    LaunchedEffect(
        selectableMessages,
        invalidVisibleMessageIds,
        controller.deletedMessageIds,
        controller.pendingTimelineRemovedMessageIds,
    ) {
        val pendingTimelineRemovals = controller.pendingTimelineRemovedMessageIds
        val reconciled =
            reconcileBatchSelections(
                selected = selectedMessages,
                selectableVisible = selectableMessages,
                deletedMessageIds = controller.deletedMessageIds + pendingTimelineRemovals,
                invalidVisibleMessageIds = invalidVisibleMessageIds,
            )
        selectedMessages.keys
            .toList()
            .filterNot(reconciled::containsKey)
            .forEach(selectedMessages::remove)
        reconciled.forEach { (messageId, selection) ->
            if (selectedMessages[messageId] != selection) selectedMessages[messageId] = selection
        }
        controller.acknowledgeTimelineRemovals(pendingTimelineRemovals)
        if (selectedMessages.isEmpty()) {
            batchForwardSheetOpen = false
            showBatchDeleteConfirm = false
        }
    }
    val selectionMode = selectedMessages.isNotEmpty()
    LaunchedEffect(selectionMode) {
        if (selectionMode) clearTextSelection()
        if (!selectionMode) {
            batchInfoSelection = null
            batchDeleteRetryState = null
        }
    }
    val composerGate =
        conversationComposerGate(
            pendingInvite = controller.group.pendingConfirmation,
            inviteAcceptanceResolutionPending = controller.inviteAcceptanceResolutionPending,
            membersVerified = controller.membersVerified,
            isSelfMember = controller.isSelfMember,
            seededSelfMember = controller.seededSelfMember,
            seededMembershipKnown = controller.seededMembershipKnown,
            assumeMemberUntilVerified = notificationOpenRequestId != 0L,
            unrecoverable = controller.group.unrecoverable,
            disbanding = controller.group.disbanding,
            disbanded = controller.group.disbanded,
        )
    val batchSelectionUi =
        rememberConversationBatchSelectionUiState(
            selectedMessages = selectedMessages,
            chatId = chat.id,
            activeAccountRef = conversationAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
            composerGate = composerGate,
            appState = appState,
        )
    val renderedSize = renderedTimeline.size
    val nearBottom =
        rememberConversationNearBottom(
            listState = listState,
            renderedTimelineSize = renderedSize,
            hasOlderHeader = hasOlderHeader,
            hasInlineTopError = hasInlineTopError,
        )

    /** Resolves a saved logical anchor after current header and error rows. */
    fun resolveScrollAnchorIndex(anchor: ConversationScrollAnchor): Int? {
        val liveRenderedTimeline = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val timelineIndex =
            anchor.messageId
                ?.takeIf { it.isNotBlank() }
                ?.let { messageId -> liveRenderedTimeline.indexOfFirst { it.record.messageIdHex == messageId } }
                ?.takeIf { it >= 0 }
                ?: anchor.itemId
                    ?.let { itemId -> liveRenderedTimeline.indexOfFirst { it.id == itemId } }
                    ?.takeIf { it >= 0 }
                ?: return null
        return 1 +
            controller.conversationLeadingStructuralRowCount(liveRenderedTimeline.size) +
            timelineIndex
    }

    // Drag interactions are the authority for user intent. Programmatic list
    // movement never emits these, so it cannot accidentally downgrade a tail
    // follower or overwrite a history anchor.
    LaunchedEffect(listState, scrollCoordinator) {
        listState.interactionSource.interactions.collectConversationDragInteractions(
            onStarted = {
                ttsFollowHandle.suspendForDirectDrag(
                    state = appState.ttsController.state.value,
                    ownsSession = appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
                )
                scrollCoordinator.onUserGestureStarted(currentScrollAnchor())
            },
            awaitScrollSettled = {
                snapshotFlow { listState.isScrollInProgress }.filter { !it }.first()
            },
            onSettled = {
                val liveRenderedSize =
                    controller.timeline.count { !MessageProjector.isEdit(it.record) }
                val liveHasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
                scrollCoordinator.onUserGestureSettled(
                    currentScrollAnchor(),
                    isNearBottom(
                        listState = listState,
                        timelineSize = liveRenderedSize,
                        hasOlderHeader = liveHasOlderHeader,
                        hasInlineTopError =
                            liveRenderedSize > 0 &&
                                controller.error != null &&
                                controller.errorEdge == ConversationLoadFailureEdge.TOP,
                    ),
                )
            },
        )
    }
    var readAnchorMessageId by
        rememberConversationReadAnchor(
            controller = controller,
            entrySessionIdentity = entryUnreadSessionIdentity,
            renderedTimeline = renderedTimeline,
            listState = listState,
            hasOlderHeader = hasOlderHeader,
            hasInlineTopError = hasInlineTopError,
            initialTimelineAnchored = initialTimelineAnchored,
        )
    DisposableEffect(controller) {
        onDispose {
            val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
            val hasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
            val hasInlineTopError =
                rendered.isNotEmpty() &&
                    controller.error != null &&
                    controller.errorEdge == ConversationLoadFailureEdge.TOP
            val firstTimelineIndex =
                listState.firstVisibleItemIndex -
                    1 -
                    (if (hasInlineTopError) 1 else 0) -
                    (if (hasOlderHeader) 1 else 0)
            val anchor = rendered.getOrNull(firstTimelineIndex)
            onSaveScrollSnapshot(
                conversationScrollSnapshotOnLeave(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    nearBottom = scrollCoordinator.isFollowingTail,
                    anchorItemId = anchor?.id,
                    anchorMessageIdHex = anchor?.record?.messageIdHex,
                ),
            )
        }
    }
    val unreadIncomingCount by remember(controller, chat.id) {
        derivedStateOf {
            if (!initialTimelineAnchored) {
                0
            } else {
                countUnreadIncoming(controller.timeline, readAnchorMessageId)
            }
        }
    }
    LaunchedEffect(
        controller,
        initialTimelineAnchored,
        renderedTimeline,
        readAnchorMessageId,
        unreadIncomingCount,
        nearBottom,
        unreadJumpState,
    ) {
        if (!initialTimelineAnchored) return@LaunchedEffect
        unreadJumpState =
            reconcileConversationUnreadJump(
                current = unreadJumpState,
                timeline = renderedTimeline,
                readAnchorMessageId = readAnchorMessageId,
                unreadCount = unreadIncomingCount,
                nearBottom = nearBottom,
            )
    }
    // Unread messages (after the read anchor) that mention the active account,
    // oldest first — drives the in-conversation jump-to-mention chip. Mirrors
    // countUnreadIncoming's anchor logic; kind-9 only, matching the engine's
    // mention classification, reusing the #414 per-message detection.
    val selfAccountIdHex = conversationSelfAccountIdHex
    val mentionDetectionCache = remember(controller, chat.id, selfAccountIdHex) { MentionDetectionCache() }
    val unreadMentionMessageIds by remember(controller, chat.id, selfAccountIdHex, mentionDetectionCache) {
        derivedStateOf {
            // Anchor on the UI read high-water mark. It advances immediately when
            // the user visits a mention and when the visible row settles, so a
            // recreated controller cannot briefly resurrect already-read mentions.
            if (!initialTimelineAnchored || selfAccountIdHex.isNullOrBlank() || readAnchorMessageId == null) {
                emptyList()
            } else {
                unreadReceivedMentionIds(controller.timeline, readAnchorMessageId) { msg ->
                    mentionDetectionCache.getOrCompute(msg.record.messageIdHex, msg.record.contentTokens) {
                        documentMentionsAccount(
                            document = msg.record.contentTokens,
                            accountIdHex = selfAccountIdHex,
                            resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                        )
                    }
                }
            }
        }
    }
    // Reading the raw IME inset in the body would re-subscribe and recompose
    // this (very heavy) screen on every keyboard-animation frame. Capture the
    // ime WindowInsets (the @Composable read) once, then collapse to the
    // boolean edge inside derivedStateOf so only the open/close transition
    // triggers a recomposition. getBottom() reads the inset's snapshot state
    // inside the derived block. See #374.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeIsOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val compactHeightConversation by rememberConversationCompactHeight()
    // #589: composer focus is hoisted here so the resume lifecycle observer
    // below can drive it. `composerFocus` is the requester wired into the
    // composer's BasicTextField, `composerFocused` mirrors the live focus edge
    // reported by `onFocusChanged`. Keyed on chat.id so a conversation switch
    // doesn't carry the previous chat's keyboard state across — the ON_PAUSE
    // snapshot that decides restore-vs-clear lives in
    // ConversationComposerLifecycleEffect instead, scoped to the observer it
    // installed rather than to chat.id.
    val composerFocus = remember(chat.id) { FocusRequester() }
    var composerFocused by remember(chat.id) { mutableStateOf(false) }
    var composerDismissInProgress by remember(chat.id) { mutableStateOf(false) }
    val imeTransitionBookmarkState = remember(chat.id) { mutableStateOf<ConversationScrollBookmark?>(null) }
    var imeTransitionBookmark by imeTransitionBookmarkState
    val suppressNextImeOpenReanchor = remember(chat.id) { AtomicBoolean(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val exitConversation =
        rememberConversationExitHandler(
            identity = chat.id,
            imeIsOpen = imeIsOpen,
            routeToChatList = onBack,
        )

    val eventCardResolver =
        remember(controller, appState, conversationAccountRef, appState.runtimeGeneration) {
            NostrEventCardResolver(
                parentScope = scope,
                relayProvider = appState::publicEventCardRelays,
            )
        }
    DisposableEffect(eventCardResolver) {
        onDispose(eventCardResolver::close)
    }

    /** Reveals the optimistic row using controller state published before the acceptance callback. */
    fun revealSentMessage() {
        scope.launch {
            scrollCoordinator.revealSentAtLiveTail(controller)
        }
    }

    val context = LocalContext.current
    val documentSaveFallback = rememberDocumentSaveFallback()
    val mediaSender =
        rememberConversationMediaSender(
            appState = appState,
            controller = controller,
            context = context,
            onRevealSent = { revealSentMessage() },
        )
    val clipboard = LocalClipboardManager.current
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // Seeded empty and populated off the Main thread: the first access to a
    // SharedPreferences file blocks on disk, and doing that inside composition
    // stalls the conversation screen's first frame. See #147.
    val recentEmojiRecentsOwner = rememberRecentEmojiRecentsOwner(context)
    var quickReactionEmojis by remember(context) {
        mutableStateOf(RecentEmojiList.DefaultQuickChoices)
    }
    var quickReactionEmojisTouched by remember(context) { mutableStateOf(false) }
    LaunchedEffect(context) {
        val quick =
            withContext(Dispatchers.IO) {
                RecentEmojiPreferences.loadQuickReactions(context)
            }
        if (!quickReactionEmojisTouched) {
            quickReactionEmojis = quick
        }
    }
    // Selected-but-not-yet-sent image attachments. The preview sheet opens
    // when this or `pendingDocumentUris` is non-empty; the whole queue
    // ships as one kind:9 album via `controller.sendAttachments(list, caption)`.
    //
    // Persist the staging shelf across process death (issue #531): capturing
    // in landscape foregrounds the external camera app, which on low/medium
    // memory devices gets the backgrounded host process killed. On return the
    // activity is recreated and a plain `remember` would have wiped the shelf,
    // dropping the just-captured image even though `cameraOutputUri` survived.
    // The camera capture is a FileProvider URI over an app-owned cache file,
    // so it re-opens fine post-restore. Photo Picker / GET_CONTENT / document
    // URIs carry session-scoped read grants that DON'T survive process death;
    // if such a URI was staged when the process died it returns as a ghost
    // that fails to open — that degrades gracefully through the existing
    // decode-failure toast path in `sendStagedAttachments`, which is a better
    // outcome than silently losing the camera capture the user just accepted.
    // Keyed on chat.id so a conversation switch still flushes the staging
    // shelf — ConversationScreen is reused when `selectedChat` changes in
    // place, and an unkeyed state would otherwise carry URIs from chat A into
    // chat B (where a Send would attach them to the wrong recipient).
    var pendingMediaSlots by rememberSaveable(chat.id, stateSaver = PendingMediaSlotListSaver) {
        mutableStateOf<List<PendingMediaSlot>>(emptyList())
    }
    var pendingDocumentUris by rememberSaveable(chat.id, stateSaver = UriListSaver) {
        mutableStateOf<List<android.net.Uri>>(emptyList())
    }
    LaunchedEffect(chat.id, appState.inboundShareRevision, pendingMediaSlots.size, pendingDocumentUris.size) {
        val capped =
            appState.consumeInboundShareStreamsCapped(
                groupIdHex = chat.group.groupIdHex,
                existingMediaCount = pendingMediaSlots.size,
                existingDocumentCount = pendingDocumentUris.size,
                maxItems = MEDIA_PICKER_MAX_ITEMS,
            ) ?: return@LaunchedEffect
        val staged = capped.accepted
        if (staged.mediaUris.isNotEmpty()) {
            pendingMediaSlots = appendPendingMediaSlots(pendingMediaSlots, staged.mediaUris, MEDIA_PICKER_MAX_ITEMS)
        }
        if (staged.documentUris.isNotEmpty()) {
            pendingDocumentUris = (pendingDocumentUris + staged.documentUris).distinct()
        }
        if (capped.droppedCount > 0) {
            val message =
                context.resources.getQuantityString(
                    R.plurals.toast_share_attachments_dropped,
                    capped.droppedCount,
                    capped.droppedCount,
                )
            appState.presentText(AppText.Plain(message))
        }
    }
    // Survives process death while the camera app is foreground (the result
    // callback fires into a recreated activity, otherwise the capture is lost).
    var cameraOutputUri by rememberSaveable(stateSaver = NullableUriSaver) {
        mutableStateOf<android.net.Uri?>(null)
    }
    // Survives process death alongside `cameraOutputUri` so a capture
    // cancelled after a death-and-restore can still delete the empty temp
    // file instead of leaking it (issue #531).
    var cameraOutputFile by rememberSaveable(stateSaver = NullableFileSaver) {
        mutableStateOf<java.io.File?>(null)
    }

    // PickMultipleVisualMedia uses the system Photo Picker — no READ_MEDIA_IMAGES
    // permission needed (Android 13+ scopes the picker's own grant); on older
    // devices it falls back to GET_CONTENT with the same UX. The maxItems
    // cap comes from MEDIA_PICKER_MAX_ITEMS; picking a single image still works
    // (returns a one-element list).
    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(maxItems = MEDIA_PICKER_MAX_ITEMS),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Append rather than replace so a follow-up "Add more" tile-pick
            // grows the staging shelf instead of clobbering whatever the user
            // already queued. Each occurrence gets its own stable slot identity
            // so selecting the same URI twice still produces independent edits.
            pendingMediaSlots = appendPendingMediaSlots(pendingMediaSlots, uris, MEDIA_PICKER_MAX_ITEMS)
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val captured = cameraOutputUri
            if (success && captured != null) {
                // Append to whatever's already queued so an in-progress staging
                // shelf survives a camera capture.
                pendingMediaSlots =
                    appendPendingMediaSlots(pendingMediaSlots, listOf(captured), MEDIA_PICKER_MAX_ITEMS)
            } else {
                cameraOutputFile?.delete() // cancelled — don't leak the empty temp
            }
            cameraOutputUri = null
            cameraOutputFile = null
        }

    fun launchCameraCapture() {
        val file = createImageCaptureFile(context)
        if (file == null) {
            appState.present(R.string.toast_couldnt_decode_image, copyable = true)
            return
        }
        cameraOutputFile = file
        val uri = fileProviderUri(context, file)
        cameraOutputUri = uri
        cameraLauncher.launch(uri)
    }

    // TakePicture needs no permission of its own, but because CAMERA is declared
    // in the manifest (for the QR scanner) some OEMs require the runtime grant
    // before launching the capture intent — request it first if missing.
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) launchCameraCapture() }

    // Contact share (attachment sheet): the phone-row picker returns a data
    // URI whose temporary read grant covers the chosen entry's name + number
    // directly, so no READ_CONTACTS permission is requested and nothing
    // beyond that one picked row is read — never the address book.
    var pendingContactShare by remember(chat.id) { mutableStateOf<SharedContact?>(null) }
    // The picked point lives only here until the user sends or cancels; the
    // keyless OSM picker is the single confirmation surface.
    var locationPickerOpen by remember(chat.id) { mutableStateOf(false) }
    // "Share user" (npub) — the identity-native counterpart to a phone contact.
    // Reuses the recipient picker; the selection sends a `nostr:npub…` reference
    // the recipient can tap to open that profile.
    var shareUserPickerOpen by remember(chat.id) { mutableStateOf(false) }
    val shareUserSelection = remember(chat.id) { mutableStateListOf<RecipientSearch.Candidate>() }
    val contactPickerLauncher =
        rememberLauncherForActivityResult(PickContactPhoneRow()) { contactUri ->
            if (contactUri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val contact =
                    withContext(Dispatchers.IO) {
                        readSharedContact(context.contentResolver, contactUri)
                    }
                if (contact == null || contact.isEmpty) {
                    appState.present(R.string.contact_read_failed)
                } else {
                    pendingContactShare = contact
                }
            }
        }

    fun hasLocationGrant(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (locationGrantAllowsSharing(grants)) {
                locationPickerOpen = true
            } else {
                appState.present(R.string.location_permission_denied)
            }
        }

    /** Sends the canonical bare reference so receiving clients cannot infer a title from prose. */
    fun sendSharedUser(candidate: RecipientSearch.Candidate) {
        val presentationNpub = appState.npubForDisplay(candidate.accountIdHex)
        if (presentationNpub.isBlank()) return
        val body = formatUserShareText(presentationNpub)
        appState.launchMutation {
            controller.send(body) {
                revealSentMessage()
            }
        }
    }

    // Voice-message recording surface — owned per ConversationScreen so a
    // backgrounded recording is dropped on dispose. The recorder writes
    // into a per-session temp dir; the file is consumed by `sendVoiceMessage`
    // below and then removed.
    val voiceOutputDir =
        remember(context) {
            java.io.File(context.cacheDir, "voice-recordings").apply { mkdirs() }
        }
    val micPermissionDeniedMsg = stringResource(R.string.voice_message_permission_denied)
    val voiceTooShortMsg = stringResource(R.string.voice_message_too_short)
    var voiceMicPermissionRequested by remember { mutableStateOf(false) }
    val voiceMicPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (!granted) appState.present(micPermissionDeniedMsg) }

    val voiceRecordingController =
        // Re-key on every captured dependency: chat.id (basic), controller
        // (avoids dispatching through a stale ConversationController when
        // appState.runtimeGeneration changes), and voiceOutputDir (a fresh
        // File reference if context/cacheDir flips — also future-proofs an
        // account-scoped dir).
        remember(chat.id, controller, voiceOutputDir) {
            dev.ipf.whitenoise.android.audio.VoiceRecordingController(
                context = context,
                outputDirectory = voiceOutputDir,
                scope = scope,
                onPermissionRequest = {
                    val granted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    if (!granted && !voiceMicPermissionRequested) {
                        voiceMicPermissionRequested = true
                        voiceMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    granted
                },
                onRecordingComplete = { file, durationMs -> mediaSender.sendVoiceAttachment(file, durationMs) },
                onError = { throwable ->
                    presentVoiceRecordingFailure(appState, throwable, voiceTooShortMsg)
                },
                // Honor the user's media-quality ceiling for voice notes.
                // Read at record-start (the controller is not re-keyed on the
                // quality state) so a setting change applies to the next clip.
                bitrateProvider = { appState.mediaQuality.audioBitrateBps },
                microphoneCaptures = appState.microphoneCaptureCoordinator,
            )
        }
    DisposableEffect(voiceRecordingController) {
        onDispose { voiceRecordingController.release() }
    }

    // Auto-chain voice playback: when one clip ends, play the IMMEDIATE
    // next message iff it's also a voice attachment. Stops on any
    // non-voice neighbor (text, image, system) or end-of-timeline. We do
    // not skip past unrelated messages to find a later voice note — that
    // would jump the user past content they hadn't consumed.
    DisposableEffect(controller, chat.id) {
        val ownerKey = controller.group.groupIdHex
        val unregister =
            dev.ipf.whitenoise.android.audio.VoicePlaybackController.registerCompletionCallback(ownerKey) { completedKey ->
                val completedMsgId = completedKey.substringBefore('#')
                val completedIdx = controller.timeline.indexOfFirst { it.record.messageIdHex == completedMsgId }
                if (completedIdx >= 0) {
                    // Walk forward only as long as the next item is a derived-
                    // state row (edit / group system) — those are invisible to
                    // the user, so skipping them doesn't violate "immediate
                    // neighbor" semantics.
                    var nextIdx = completedIdx + 1
                    while (nextIdx < controller.timeline.size &&
                        (
                            MessageProjector.isEdit(controller.timeline[nextIdx].record) ||
                                MessageProjector.isGroupSystem(controller.timeline[nextIdx].record)
                        )
                    ) {
                        nextIdx++
                    }
                    val nextMsg = controller.timeline.getOrNull(nextIdx)
                    val refs = nextMsg?.let(controller::mediaReferencesFor)
                    val audioEntry =
                        refs?.withIndex()?.firstOrNull { (_, r) ->
                            r.mediaType.startsWith("audio/", ignoreCase = true)
                        }
                    if (nextMsg != null && audioEntry != null) {
                        val idx = audioEntry.index
                        val ref = audioEntry.value
                        scope.launch {
                            val mine = nextMsg.record.direction != "received"
                            val file =
                                runCatching {
                                    materializeVoiceAttachment(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = nextMsg.record.messageIdHex,
                                        attachmentIndex = idx,
                                        reference = ref,
                                        mine = mine,
                                    )
                                }.getOrNull() ?: return@launch
                            dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                .play(
                                    voicePlaybackKey(nextMsg.record.messageIdHex, idx, ref.sourceEpoch),
                                    file,
                                    ownerKey = ownerKey,
                                )
                        }
                    }
                }
            }
        onDispose {
            unregister()
        }
    }

    // Documents take a separate launcher because `OpenMultipleDocuments`
    // accepts any MIME — the image picker can't surface PDFs, archives, etc.
    // Picked URIs accumulate in `pendingDocumentUris` so they can ride the
    // same staging shelf as image picks; one Send dispatches both sides
    // through one kind:9 album. Non-image bytes pass through without
    // recompression, while detected images use the same metadata-safe policy
    // as Photo Picker. The send-quality audio bitrate is intentionally scoped
    // to recorded voice notes until this client grows a general audio
    // transcode path.
    val documentPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Append into the document side of the staging shelf rather than
            // sending immediately. The preview sheet renders both lists and
            // a single Send dispatches both decoders into one kind-9 album.
            val merged = (pendingDocumentUris + uris).distinct().take(MEDIA_PICKER_MAX_ITEMS)
            pendingDocumentUris = merged
        }

    /** Resolves a message id to its current list index after every leading structural row. */
    fun currentTimelineListIndex(messageId: String): Int? {
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == messageId }
                .takeIf { it >= 0 }
                ?: return null
        val leadingStructuralRowCount =
            controller.conversationLeadingStructuralRowCount(
                controller.timeline.count { !MessageProjector.isEdit(it.record) },
            )
        return 1 + leadingStructuralRowCount + timelineIndex
    }

    ConversationTtsFollowEffects(
        appState = appState,
        controller = controller,
        listState = listState,
        scrollCoordinator = scrollCoordinator,
        handle = ttsFollowHandle,
        initialTimelineAnchored = initialTimelineAnchored,
        renderedTimeline = renderedTimeline,
        timelineItemHeightsPx = navigationState.timelineItemHeightsPx,
        currentTimelineListIndex = ::currentTimelineListIndex,
        currentScrollAnchor = ::currentScrollAnchor,
        explicitRevealRequestId =
            if (ttsFocusSessionId == null) {
                0L
            } else {
                focusMessageRequestId
            },
    )

    /**
     * Centers [targetMessageId] with surrounding context through the shared latest-wins owner.
     *
     * An unmeasured target uses one animated approach followed by a non-animated exact correction,
     * avoiding a second visible bounce.
     */
    suspend fun centerTimelineItemAt(
        targetMessageId: String,
        fallbackTargetIndex: Int,
        reason: ConversationScrollReason,
        skipIfFullyVisible: Boolean = false,
    ): Boolean {
        val completed =
            scrollCoordinator.programmaticJump(
                targetMessageId = targetMessageId,
                reason = reason,
            ) {
                val targetIndex = currentTimelineListIndex(targetMessageId) ?: fallbackTargetIndex
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight <= 0) {
                    // Layout not measured yet (rare on a fresh open): fall back to the
                    // plain top-aligned jump rather than guessing an offset.
                    animateScrollToItem(targetIndex) {
                        currentTimelineListIndex(targetMessageId) ?: targetIndex
                    }
                    return@programmaticJump
                }
                if (skipIfFullyVisible && layoutInfo.isItemFullyVisible(targetIndex)) {
                    return@programmaticJump
                }
                val renderedForHeightSample = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                val firstTimelineListIndex =
                    1 + controller.conversationLeadingStructuralRowCount(renderedForHeightSample.size)
                val lastTimelineListIndex = firstTimelineListIndex + renderedForHeightSample.size - 1
                val visibleTargetHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size
                val visibleTimelineHeights =
                    layoutInfo.visibleItemsInfo
                        .filter { visibleItem ->
                            if (visibleItem.index !in firstTimelineListIndex..lastTimelineListIndex) return@filter false
                            val row = renderedForHeightSample.getOrNull(visibleItem.index - firstTimelineListIndex) ?: return@filter false
                            timelineRowKind(row.record, appState.streamingDebugEnabled) == TimelineRowKind.Bubble
                        }.map { it.size }
                val itemHeight =
                    ReplyNavigation.itemHeightForScrollPx(
                        targetMessageId = targetMessageId,
                        measuredItemHeightsByMessageId = navigationState.timelineItemHeightsPx,
                        visibleTargetHeightPx = visibleTargetHeight,
                        visibleTimelineItemHeightsPx = visibleTimelineHeights,
                    )
                val animatedOffset = ReplyNavigation.centeredScrollOffset(viewportHeight, itemHeight)
                animateScrollToItem(targetIndex, animatedOffset) {
                    currentTimelineListIndex(targetMessageId) ?: targetIndex
                }

                // Keep the measured correction in the same coordinator command. A
                // newer drag/jump cancels this whole block before it can snap back.
                withFrameNanos { }
                val resolvedTargetIndex = currentTimelineListIndex(targetMessageId) ?: targetIndex
                val postScrollLayoutInfo = listState.layoutInfo
                val measuredItemHeight =
                    postScrollLayoutInfo.visibleItemsInfo.firstOrNull { it.index == resolvedTargetIndex }?.size
                        ?: navigationState.timelineItemHeightsPx[targetMessageId]
                val measuredViewportHeight = postScrollLayoutInfo.viewportSize.height
                if (measuredViewportHeight > 0 && measuredItemHeight != null) {
                    val measuredOffset =
                        ReplyNavigation.centeredScrollOffset(measuredViewportHeight, measuredItemHeight)
                    if (resolvedTargetIndex != targetIndex || measuredOffset != animatedOffset) {
                        scrollToItem(resolvedTargetIndex, measuredOffset)
                    }
                }
            }
        if (completed) scrollCoordinator.settleReadingAt(currentScrollAnchor())
        return completed
    }

    suspend fun showTransientMessageHighlight(messageId: String) {
        navigationState.targetHighlight.highlightWhile(
            messageId = messageId,
            postSettleDwellMillis = TRANSIENT_MESSAGE_HIGHLIGHT_DWELL_MILLIS,
        ) { true }
    }

    fun saveQuickReactionEmojis(choices: List<String>) {
        quickReactionEmojisTouched = true
        scope.launch {
            quickReactionEmojis = withContext(Dispatchers.IO) { RecentEmojiPreferences.saveQuickReactions(context, choices) }
        }
    }

    fun resetQuickReactionEmojis() {
        quickReactionEmojisTouched = true
        scope.launch {
            quickReactionEmojis = withContext(Dispatchers.IO) { RecentEmojiPreferences.resetQuickReactions(context) }
        }
    }

    fun navigateToReplyTarget(item: TimelineMessage) {
        navigationState.searchJob?.cancel()
        navigationState.targetHighlight.clear()
        navigationState.navigateReplyJob?.cancel()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.navigateReplyJob =
            scope.launch {
                if (!navigationRequest.isCurrent()) return@launch
                val targetMessageId = controller.replyTargetMessageId(item)
                if (targetMessageId == null) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                navigationState.targetHighlight.highlightWhile(targetMessageId) {
                    if (!navigationRequest.isCurrent()) return@highlightWhile false
                    val available = controller.loadUntilMessageAvailable(targetMessageId)
                    if (!navigationRequest.isCurrent()) return@highlightWhile false
                    if (!available) {
                        appState.present(R.string.toast_original_message_unavailable)
                        return@highlightWhile false
                    }
                    // Resolve the target in the rendered (edit-filtered) list the
                    // LazyColumn shows — an unfiltered index is off by the edits above it.
                    val timelineIndex =
                        controller.timeline
                            .filterNot { MessageProjector.isEdit(it.record) }
                            .indexOfFirst { it.record.messageIdHex == targetMessageId }
                    if (timelineIndex < 0) {
                        appState.present(R.string.toast_original_message_unavailable)
                        return@highlightWhile false
                    }
                    if (!navigationRequest.isCurrent()) return@highlightWhile false
                    val leadingStructuralRowCount =
                        controller.conversationLeadingStructuralRowCount(
                            controller.timeline.count { !MessageProjector.isEdit(it.record) },
                        )
                    centerTimelineItemAt(
                        targetMessageId,
                        1 + leadingStructuralRowCount + timelineIndex,
                        ConversationScrollReason.Reply,
                        skipIfFullyVisible = true,
                    )
                }
            }
    }

    fun jumpToNextUnreadMention() {
        val targetMessageId = unreadMentionMessageIds.firstOrNull() ?: return
        navigationState.searchJob?.cancel()
        navigationState.targetHighlight.clear()
        navigationState.navigateReplyJob?.cancel()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.navigateReplyJob =
            scope.launch {
                if (!navigationRequest.isCurrent()) return@launch
                val available = controller.loadUntilMessageAvailable(targetMessageId)
                if (!navigationRequest.isCurrent()) return@launch
                if (!available) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val timelineIndex =
                    controller.timeline
                        .filterNot { MessageProjector.isEdit(it.record) }
                        .indexOfFirst { it.record.messageIdHex == targetMessageId }
                if (timelineIndex < 0) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                if (!navigationRequest.isCurrent()) return@launch
                val leadingStructuralRowCount =
                    controller.conversationLeadingStructuralRowCount(
                        controller.timeline.count { !MessageProjector.isEdit(it.record) },
                    )
                val centered =
                    centerTimelineItemAt(
                        targetMessageId,
                        1 + leadingStructuralRowCount + timelineIndex,
                        ConversationScrollReason.Mention,
                    )
                if (!centered) return@launch
                // Mark read up to the visited mention so the count — and the
                // chat-list @-badge — decrement in step; advance the local read
                // anchor so the chip's derived count updates immediately.
                readAnchorMessageId = targetMessageId
                controller.markReadUpTo(targetMessageId)
                showTransientMessageHighlight(targetMessageId)
            }
    }

    LaunchedEffect(controller) {
        navigationState.initialTimelineLoadStarted = true
    }
    LaunchedEffect(controller.group.pendingConfirmation, controller.group.groupIdHex) {
        if (controller.group.pendingConfirmation) {
            controller.dismissConversationNotifications()
        }
    }
    val latestTimelineItemId = renderedTimeline.lastOrNull()?.id
    val currentController by rememberUpdatedState(controller)
    val transcriptLocale = LocalConfiguration.current.locales[0]
    val tailTimelineIndex =
        conversationTimelineTailListIndex(
            timelineSize = renderedTimeline.size,
            leadingStructuralRowCount = leadingStructuralRowCount,
        ) ?: 0
    val currentTailIndex by rememberUpdatedState(newValue = tailTimelineIndex)
    val seededTailAlignmentReady =
        firstFrameSeed.anchorTailImmediately &&
            (!navigationState.seedTailAwaitingAuthoritative || controller.hasPublishedAuthoritativeTimeline)
    SeededConversationAnchorBaselineEffect(
        enabled = seededTailAlignmentReady,
        retryGeneration = seededTailAlignmentRetryGeneration,
        listState = listState,
        scrollCoordinator = scrollCoordinator,
        currentTailIndex = { currentTailIndex },
        postInitialReanchorGate = postInitialReanchorGate,
        timelineStructure =
            ConversationTimelineStructure(
                rowKeys = renderedTimelineAnchorKeys,
                olderHeaderCount = olderHeaderCount,
                inlineTopErrorCount = inlineTopErrorCount,
            ),
        onTailAlignmentCommitted = {
            seededTailAlignmentRecoveryVisible = false
            seededTailAlignmentCommitted = true
            if (navigationState.seedTailAwaitingAuthoritative) {
                navigationState.seedTailAwaitingAuthoritative = false
                navigationState.lastFollowedLatestId = renderedTimeline.lastOrNull()?.id
                initialTimelineAnchored = true
            }
        },
        onTailAlignmentExhausted = { seededTailAlignmentRecoveryVisible = true },
    )
    ConversationTailInsetReanchorEffect(
        scrollCoordinator = scrollCoordinator,
        bottomChromeHeightPx = measuredBottomChromeHeightPx,
        snackbarContentInsetPx = with(density) { snackbarContentInset.value.roundToPx() },
        bottomInputRevision = bottomInputRevision,
        hasTimeline = renderedTimeline.isNotEmpty(),
        initialTimelineAnchored = initialTimelineAnchored,
        routePresentationFrozen = freezeRoutePresentation,
        foregroundRestoreInProgress = scrollCoordinator.foregroundRestoreInProgress,
        currentTailIndex = { currentTailIndex },
    )

    // Edit events are derived state, so a raw subscription page can be non-empty
    // while offering no row for the initial anchor. Page backward before the
    // transcript is revealed; the pure coordinator stops on renderable content,
    // exhaustion, failure, no progress, cancellation, or controller replacement.
    LaunchedEffect(
        controller,
        navigationState.initialTimelineLoadStarted,
        controller.isLoading,
        controller.isLoadingOlder,
        latestTimelineItemId,
        navigationState.initialTimelineBackfillRetryGeneration,
    ) {
        val hasEstablishedInitialPosition = initialTimelineAnchored || scrollRestore != null
        val timelineCannotBackfill =
            !navigationState.initialTimelineLoadStarted ||
                controller.group.pendingConfirmation ||
                controller.timeline.isEmpty()
        if (
            hasEstablishedInitialPosition ||
            timelineCannotBackfill ||
            renderedTimeline.isNotEmpty()
        ) {
            if (renderedTimeline.isNotEmpty()) {
                navigationState.initialTimelineBackfillNoProgress = false
            }
            return@LaunchedEffect
        }
        val result =
            backfillInitialConversationTimeline(
                snapshot = controller::initialTimelineBackfillSnapshot,
                loadOlder = controller::loadOlderTimelinePage,
                isCurrent = { currentController === controller },
            )
        if (currentController !== controller) return@LaunchedEffect
        when (result) {
            ConversationInitialTimelineBackfillResult.Exhausted -> {
                navigationState.initialTimelineBackfillNoProgress = false
                initialTimelineAnchored = true
            }
            ConversationInitialTimelineBackfillResult.NoProgress -> {
                navigationState.initialTimelineBackfillNoProgress = true
            }
            ConversationInitialTimelineBackfillResult.Renderable -> {
                navigationState.initialTimelineBackfillNoProgress = false
            }
            ConversationInitialTimelineBackfillResult.Failed,
            ConversationInitialTimelineBackfillResult.NotReady,
            ConversationInitialTimelineBackfillResult.Superseded,
            -> Unit
        }
    }

    // Day label for the topmost visible message, surfaced by the sticky ribbon
    // overlay while scrolling. Hoisted into derivedStateOf and held as a State
    // (not read here) so the scroll-backed firstVisibleItemIndex read happens
    // inside the small ribbon child — not in the LazyColumn-hosting Box scope,
    // which would otherwise recompose the timeline container on every scroll
    // frame. Mirrors the nearBottom / currentHighestVisibleTimelineIndex
    // derived-state pattern above (#375).
    val stickyDayLabelState =
        remember(renderedTimeline, transcriptLocale, leadingStructuralRowCount) {
            derivedStateOf {
                val i =
                    (listState.firstVisibleItemIndex - 1 - leadingStructuralRowCount)
                        .coerceIn(0, (renderedTimeline.size - 1).coerceAtLeast(0))
                renderedTimeline
                    .getOrNull(i)
                    ?.record
                    ?.recordedAt
                    ?.let { messageDayLabel(it, transcriptLocale) }
                    .orEmpty()
            }
        }

    // In-chat search match set. Computed over the currently-loaded, rendered
    // (edit-filtered) timeline only — no relay fetch, no full-history preload.
    // Reactions / deletes / group-system / agent-stream rows carry no
    // user-typed body and are excluded by `MessageSearch.isSearchable`. As
    // older pages load, `renderedTimeline` grows and the match set expands
    // naturally. Keyed on `controller.timeline` (not just `renderedTimeline`'s
    // edges/size) so a kind-1009 edit — which changes the body returned by
    // `controller.displayedText(...)` without altering the rendered timeline's
    // first/last id or size — re-runs the derivation and keeps matches fresh.
    val searchWindowMatches =
        remember(navigationState.searchQuery, controller.timeline, renderedTimeline) {
            if (navigationState.searchQuery.isBlank()) {
                emptyList()
            } else {
                // Restrict to rows that carry a user-typed body, then run the
                // shared substring matcher over those bodies and map the hit
                // indices back to message ids (timeline order preserved).
                // Snapshot the body once per row so the displayed (post-edit)
                // text used for matching is the same text used to map hits.
                val searchable =
                    renderedTimeline.mapNotNull { item ->
                        val body = controller.displayedText(item.record)
                        if (MessageSearch.isSearchable(item.record, body)) {
                            ConversationSearchMatch(
                                messageIdHex = item.record.messageIdHex,
                                timelineAt = item.projected?.timelineAt ?: item.record.recordedAt,
                            ) to body
                        } else {
                            null
                        }
                    }
                val bodies = searchable.map { it.second }
                MessageSearch
                    .matchIndices(bodies, navigationState.searchQuery)
                    .map { searchable[it].first }
            }
        }
    // Full local-store matches: the loaded-window derivation above is instant
    // feedback while typing; the exhaustive history scan is the authority once
    // it lands, so a result cannot depend on incidental scroll history. The
    // effect restarting on each keystroke cancels a superseded scan, and the
    // debounce keeps typing from firing one scan per character.
    LaunchedEffect(navigationState.searchQuery, chat.id, controller) {
        navigationState.historySearchMatches = null
        if (navigationState.searchQuery.isBlank()) return@LaunchedEffect
        delay(HISTORY_SEARCH_DEBOUNCE_MILLIS)
        val launchedForQuery = navigationState.searchQuery
        val scan = searchConversationHistoryMatches(appState, controller.group.groupIdHex, launchedForQuery)
        // Only publish if this is still the current query. Cancellation already
        // propagates from the scan, so this only guards a scan that completed
        // in the gap before the effect restarted for a newer keystroke.
        if (navigationState.searchQuery == launchedForQuery) navigationState.historySearchMatches = scan
    }
    val effectiveSearchMatches =
        remember(searchWindowMatches, navigationState.historySearchMatches, renderedTimeline) {
            val scan = navigationState.historySearchMatches
            if (scan == null) {
                searchWindowMatches
            } else {
                MessageSearch
                    .mergeWithHistoryScan(
                        windowMatches = searchWindowMatches,
                        loadedWindowIds = renderedTimeline.mapTo(HashSet()) { it.record.messageIdHex },
                        scanMatchesOldestFirst = scan,
                    )
            }
        }
    val effectiveSearchMatchIds = effectiveSearchMatches.map { it.messageIdHex }
    // The active match ordinal, re-anchored to the pinned message id so it
    // tracks that message as the set grows. -1 when there are no matches.
    val searchActiveIndex = MessageSearch.resolveCursor(effectiveSearchMatchIds, navigationState.searchPinnedMatchId)
    // Keep the pin valid: if the resolved cursor fell back to the first match
    // (pin gone / unset) adopt that match id as the new pin so subsequent
    // steps move relative to a real anchor.
    LaunchedEffect(effectiveSearchMatchIds, searchActiveIndex) {
        if (searchActiveIndex >= 0) {
            val resolvedId = effectiveSearchMatchIds[searchActiveIndex]
            if (navigationState.searchPinnedMatchId != resolvedId) navigationState.searchPinnedMatchId = resolvedId
        }
    }

    /** Centers and highlights a loaded search row only while its navigation request remains current. */
    suspend fun centerLoadedSearchMessage(
        messageIdHex: String,
        navigationRequest: MessageTargetNavigationOwner.Request,
    ) {
        if (!navigationRequest.isCurrent()) return
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == messageIdHex }
        if (timelineIndex >= 0 && navigationRequest.isCurrent()) {
            val liveLeadingStructuralRowCount =
                controller.conversationLeadingStructuralRowCount(
                    controller.timeline.count { !MessageProjector.isEdit(it.record) },
                )
            val centered =
                centerTimelineItemAt(
                    messageIdHex,
                    1 + liveLeadingStructuralRowCount + timelineIndex,
                    ConversationScrollReason.Search,
                )
            if (centered && navigationRequest.isCurrent()) {
                showTransientMessageHighlight(messageIdHex)
            }
        }
    }

    /** Loads and centers one indexed search result under a cancellable latest-wins request. */
    fun scrollToSearchMatch(match: ConversationSearchMatch) {
        val previousSearchJob = navigationState.searchJob
        previousSearchJob?.cancel()
        navigationState.navigateReplyJob?.cancel()
        navigationState.targetHighlight.clear()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.searchJob =
            scope.launch {
                previousSearchJob?.join()
                if (!navigationRequest.isCurrent()) return@launch
                val available = controller.loadSearchResultMessageAvailable(match)
                if (!available || !navigationRequest.isCurrent()) return@launch
                centerLoadedSearchMessage(match.messageIdHex, navigationRequest)
            }
    }

    // Group-details search can jump to a known id without exhaustive-search
    // timestamp metadata, so retain the bounded reply-navigation path.
    fun scrollToSearchMatch(messageIdHex: String) {
        val previousSearchJob = navigationState.searchJob
        previousSearchJob?.cancel()
        navigationState.navigateReplyJob?.cancel()
        navigationState.targetHighlight.clear()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.searchJob =
            scope.launch {
                previousSearchJob?.join()
                if (!navigationRequest.isCurrent()) return@launch
                val available = controller.loadUntilMessageAvailable(messageIdHex)
                if (!available || !navigationRequest.isCurrent()) return@launch
                centerLoadedSearchMessage(messageIdHex, navigationRequest)
            }
    }

    // Step the cursor (next = forward/newer, previous = backward/older) with
    // wrap-around, pin the new match, and jump+highlight it.
    fun navigateToSearchMatch(forward: Boolean) {
        if (effectiveSearchMatchIds.isEmpty()) return
        val next = MessageSearch.step(searchActiveIndex, effectiveSearchMatchIds.size, forward)
        if (next < 0) return
        val target = effectiveSearchMatches[next]
        navigationState.searchPinnedMatchId = target.messageIdHex
        scrollToSearchMatch(target)
    }

    fun closeSearch() {
        navigationState.searchOpen = false
        navigationState.searchQuery = ""
        navigationState.searchPinnedMatchId = null
        val previousSearchJob = navigationState.searchJob
        previousSearchJob?.cancel()
        navigationState.navigateReplyJob?.cancel()
        navigationState.targetNavigation.cancel()
        val expectedRestoreIntent = scrollCoordinator.intentToken
        navigationState.targetHighlight.clear()
        // A deep search jump can evict the original viewport from the capped
        // window. Page back to its durable local message before asking the
        // coordinator to restore the logical anchor and exact offset.
        navigationState.preSearchScrollAnchor?.let { anchor ->
            navigationState.searchJob =
                scope.launch {
                    previousSearchJob?.join()
                    val match = anchor.match
                    if (match != null && controller.loadSearchResultMessageAvailable(match)) {
                        withFrameNanos { }
                    }
                    scrollCoordinator.restoreBookmark(
                        anchor.bookmark,
                        expectedIntent = expectedRestoreIntent,
                        resolveAnchorIndex = { saved ->
                            resolveScrollAnchorIndex(saved)
                                ?: saved.listIndex.coerceIn(
                                    0,
                                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                )
                        },
                    )
                }
        }
        navigationState.preSearchScrollAnchor = null
    }

    // Back exits partial text selection, then batch selection, then search,
    // then dismisses the composer before leaving the conversation.
    BackHandler {
        when (
            conversationBackAction(
                textSelectionActive = textSelectionMessageId != null,
                messageSelectionActive = selectionMode,
                searchOpen = navigationState.searchOpen,
                composerFocused = composerFocused,
                imeIsOpen = imeIsOpen,
                composerDismissInProgress = composerDismissInProgress,
            )
        ) {
            ConversationBackAction.CLEAR_TEXT_SELECTION -> clearTextSelection()
            ConversationBackAction.CLEAR_MESSAGE_SELECTION -> {
                openActionMenuId = null
                selectedMessages.clear()
            }
            ConversationBackAction.CLOSE_SEARCH -> closeSearch()
            ConversationBackAction.DISMISS_COMPOSER -> {
                composerDismissInProgress = true
                keyboardController?.hide()
            }
            ConversationBackAction.NAVIGATE_UP -> exitConversation()
        }
    }

    // Explicit Back must let the IME finish releasing its inset before focus
    // is cleared. Clearing focus while the closing animation still owns a
    // non-zero inset can detach the text input before the final zero-inset
    // dispatch, leaving the Scaffold measured against the old keyboard height.
    // A voice/dictation handoff never sets composerDismissInProgress, so its
    // temporary inset collapse continues to preserve focus and selection.
    LaunchedEffect(imeIsOpen, composerDismissInProgress) {
        if (!imeIsOpen && composerDismissInProgress) {
            focusManager.clearFocus(force = true)
            composerDismissInProgress = false
        }
    }

    // Auto-focus the field on open; clear transient highlight on close.
    LaunchedEffect(navigationState.searchOpen) {
        if (navigationState.searchOpen) {
            navigationState.searchFocusRequester.requestFocus()
        }
    }
    // Jump to the first match as soon as one exists for the current query, so
    // typing immediately scrolls to (and highlights) the newest match without
    // requiring the user to tap an arrow first.
    LaunchedEffect(effectiveSearchMatches.firstOrNull(), navigationState.searchOpen) {
        if (navigationState.searchOpen && effectiveSearchMatches.isNotEmpty()) {
            val first = effectiveSearchMatches[searchActiveIndex.coerceAtLeast(0)]
            scrollToSearchMatch(first)
        }
    }

    // Extend history a few rows before the reader reaches the top, while a
    // keyed message is still the anchor. Compose's keyed prepend then holds
    // those messages at the same offset in the same measure pass — the new
    // page lands above the fold and the reader scrolls up into it with no jump
    // or blink (no post-hoc scroll, which is what caused the flip).
    LaunchedEffect(listState, controller) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIndex ->
                if (!initialTimelineAnchored || !controller.hasMoreBefore || controller.isLoadingOlder) {
                    return@collect
                }
                if (firstIndex <= leadingStructuralRowCount + OLDER_PAGE_PREFETCH_ROWS) {
                    controller.loadOlder()
                }
            }
    }
    // Loading the authoritative unread boundary can shift a capped subscription
    // window away from the newest edge. Page forward again as the reader reaches
    // that edge so chronological scrolling never ends at a stale window tail.
    LaunchedEffect(listState, controller) {
        snapshotFlow {
            if (
                !initialTimelineAnchored ||
                !controller.hasMoreAfterTimeline ||
                controller.isLoadingOlder
            ) {
                false
            } else {
                val liveRenderedSize = controller.timeline.count { !MessageProjector.isEdit(it.record) }
                val liveLeadingStructuralRowCount =
                    controller.conversationLeadingStructuralRowCount(liveRenderedSize)
                val liveNewestEdgeIndex =
                    conversationTimelineTailListIndex(liveRenderedSize, liveLeadingStructuralRowCount)
                        // An edit-only page has no message-backed tail yet; retain
                        // forward paging from its last structural header/spacer.
                        ?: liveLeadingStructuralRowCount
                val lastVisibleIndex =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                // The removed bottom sentinel sat one slot after the real tail.
                // Keep the established inclusive N-row prefetch window.
                lastVisibleIndex >= liveNewestEdgeIndex - NEWER_PAGE_PREFETCH_ROWS + 1
            }
        }.distinctUntilChanged()
            .filter { it }
            .collect { controller.loadNewerTimelinePage() }
    }
    var entryUnreadDividerRetired by remember(entryUnreadSessionIdentity) { mutableStateOf(false) }
    LaunchedEffect(controller, entryFirstUnreadMessageId, controller.timeline) {
        if (hasSentMessageAfterUnreadBoundary(controller.timeline, entryFirstUnreadMessageId)) {
            entryUnreadDividerRetired = true
        }
    }
    var unreadDivergenceLogged by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, initialTimelineAnchored, controller.timeline.size) {
        if (!initialTimelineAnchored || unreadDivergenceLogged || controller.timeline.isEmpty()) return@LaunchedEffect
        unreadCountDivergenceReport(
            projectionUnread = projectedEntryUnreadCount,
            timeline = controller.timeline,
            readAnchorMessageId = chat.projection?.lastReadMessageIdHex,
        )?.let { report ->
            logUnreadCountDivergence("DMConversation", report)
        }
        unreadDivergenceLogged = true
    }
    ConversationImeReanchorEffect(
        controller = controller,
        scrollCoordinator = scrollCoordinator,
        imeIsOpen = imeIsOpen,
        initialTimelineAnchored = initialTimelineAnchored,
        imeTransitionBookmark = imeTransitionBookmarkState,
        suppressNextImeOpenReanchor = suppressNextImeOpenReanchor,
        currentScrollAnchor = { currentScrollAnchor() },
        resolveScrollAnchorIndex = ::resolveScrollAnchorIndex,
        currentTailIndex = { currentTailIndex },
    )
    // #589/#1888: app-switch resume handling. Android/Compose can restore the
    //   BasicTextField focus and IME visibility on its own, popping a keyboard
    //   the user never asked for. We snapshot the composer focus on ON_PAUSE
    //   and, on ON_RESUME, gate restoration through the pure
    //   `shouldRestoreComposerFocusOnResume` predicate: restore focus only if
    //   it was held on pause (or an edit/reply session is active); otherwise
    //   actively clear focus and hide the keyboard so it does not pop. Scroll,
    //   inset, bottom-chrome, and timeline state are captured in the coordinator
    //   at the same pause edge. Resume commits from the actual IME target/inset
    //   settle signal: an unchanged presentation performs no list write, while a
    //   real geometry or structure delta gets one correction instead of a frame chase.
    //
    // Keyed on controller so chat and same-group account/runtime switches both
    // rebind the observer; resolved through the existing Context.lifecycleOwner()
    // idiom (no new Local import).
    val resumeLifecycleOwner = context.lifecycleOwner()
    val currentScrollAnchorResolver by
        rememberUpdatedState(newValue = { anchor: ConversationScrollAnchor -> resolveScrollAnchorIndex(anchor) })
    val currentInitialTimelineAnchored by rememberUpdatedState(newValue = initialTimelineAnchored)
    val currentImeIsOpen by rememberUpdatedState(newValue = imeIsOpen)
    ConversationForegroundRestoreEffects(
        controller = controller,
        scrollCoordinator = scrollCoordinator,
        lifecycleOwner = resumeLifecycleOwner,
        listState = listState,
        bottomChromeHeightObserver = bottomChromeHeightObserver,
        composerFocused = composerFocused,
        searchOpen = navigationState.searchOpen,
        hasActiveEditOrReplySession =
            controller.editingMessageId != null ||
                controller.replyingTo != null,
        composerFocus = composerFocus,
        initialTimelineAnchored = initialTimelineAnchored,
        currentScrollAnchor = { currentScrollAnchor() },
        resolveScrollAnchorIndex = { anchor -> resolveScrollAnchorIndex(anchor) },
        currentTailIndex = { currentTailIndex },
    )
    LaunchedEffect(listState, scrollCoordinator, postInitialReanchorGate) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { viewportHeight ->
            val viewportChanged = postInitialReanchorGate.onViewportHeight(viewportHeight)
            if (!viewportChanged || !currentInitialTimelineAnchored || currentImeIsOpen) {
                return@collect
            }
            if (scrollCoordinator.foregroundRestoreInProgress) {
                return@collect
            }
            when (scrollCoordinator.mode) {
                ConversationScrollMode.FollowingTail ->
                    scrollCoordinator.programmaticJump(
                        targetMessageId = null,
                        reason = ConversationScrollReason.ViewportChange,
                        resultingMode = ConversationScrollMode.FollowingTail,
                    ) {
                        scrollToTail(currentTailIndex)
                    }
                is ConversationScrollMode.ReadingHistory ->
                    scrollCoordinator.reanchorReadingHistory(currentScrollAnchorResolver)
                else -> Unit
            }
        }
    }

    // Re-apply a saved scroll position once the timeline materializes (#1107).
    // Seeding rememberLazyListState alone is not enough: the list can clamp
    // while the window is still empty, and the first-open anchor would snap to
    // bottom before the reader's position is restored.
    LaunchedEffect(controller, scrollRestore) {
        val restore = scrollRestore ?: return@LaunchedEffect
        snapshotFlow { controller.initialTimelineSeedActive }
            .filter { active -> !active }
            .first()
        restore.anchorMessageIdHex
            ?.takeIf { it.isNotBlank() }
            ?.let { controller.loadUntilMessageAvailable(it) }
        val targetIndex =
            snapshotFlow {
                val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                if (rendered.isEmpty()) {
                    null
                } else {
                    val liveOlderHeaderCount =
                        if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                    val liveInlineTopErrorCount =
                        if (
                            controller.error != null &&
                            controller.errorEdge == ConversationLoadFailureEdge.TOP
                        ) {
                            1
                        } else {
                            0
                        }
                    val liveLeadingStructuralRowCount = liveOlderHeaderCount + liveInlineTopErrorCount
                    val liveTailTimelineIndex =
                        conversationTimelineTailListIndex(rendered.size, liveLeadingStructuralRowCount)
                            ?: return@snapshotFlow null
                    conversationScrollRestoreListIndex(
                        snapshot = restore,
                        renderedItemIds = rendered.map { it.id },
                        renderedMessageIds = rendered.map { it.record.messageIdHex },
                        olderHeaderCount = liveOlderHeaderCount,
                        inlineTopErrorCount = liveInlineTopErrorCount,
                    ).coerceAtMost(liveTailTimelineIndex)
                }
            }.filterNotNull()
                .first()
        val resultingMode =
            ConversationScrollMode.ReadingHistory(
                restore.anchorMessageIdHex,
                restore.firstVisibleItemScrollOffset,
            )
        while (
            !scrollCoordinator.commitInitialAnchor(
                targetMessageId = restore.anchorMessageIdHex,
                reason = ConversationScrollReason.SavedRestore,
                resultingMode = resultingMode,
                targetIndex = targetIndex,
                pixelOffset = restore.firstVisibleItemScrollOffset,
                captureLayout = {
                    val layoutInfo = listState.layoutInfo
                    ConversationInitialAnchorLayout(
                        viewportHeight = layoutInfo.viewportSize.height,
                        targetItemSize = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size,
                    )
                },
            )
        ) {
            // Keep the loading surface visible. Each attempt yields through its
            // frame window, and cancellation still follows the LaunchedEffect.
            withFrameNanos { }
        }
        val restoredRendered =
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val restoredOlderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        val restoredInlineTopErrorCount =
            if (
                restoredRendered.isNotEmpty() &&
                controller.error != null &&
                controller.errorEdge == ConversationLoadFailureEdge.TOP
            ) {
                1
            } else {
                0
            }
        val restoredItem =
            restoredRendered.getOrNull(
                targetIndex - 1 - restoredOlderHeaderCount - restoredInlineTopErrorCount,
            )
        scrollCoordinator.settleReadingAt(
            ConversationScrollAnchor(
                listIndex = targetIndex,
                pixelOffset = restore.firstVisibleItemScrollOffset,
                itemId = restoredItem?.id ?: restore.anchorItemId,
                messageId = restoredItem?.record?.messageIdHex ?: restore.anchorMessageIdHex,
            ),
        )
        postInitialReanchorGate.commit(
            structure =
                ConversationTimelineStructure(
                    rowKeys = restoredRendered.map { it.id to it.record.messageIdHex },
                    olderHeaderCount = restoredOlderHeaderCount,
                    inlineTopErrorCount = restoredInlineTopErrorCount,
                ),
            viewportHeight = listState.layoutInfo.viewportSize.height,
        )
        initialTimelineAnchored = true
        navigationState.lastFollowedLatestId = restoredRendered.lastOrNull()?.id
    }
    LaunchedEffect(
        controller,
        renderedTimeline.isNotEmpty(),
        notificationOpenRequestId,
        entryProjectionAvailable,
        controller.initialTimelineSeedActive,
        navigationState.seedTailAwaitingAuthoritative,
    ) {
        if (navigationState.seedTailAwaitingAuthoritative || controller.initialTimelineSeedActive) {
            return@LaunchedEffect
        }
        if (
            !shouldCommitConversationInitialAnchor(
                hasRenderedTimeline = renderedTimeline.isNotEmpty(),
                projectionAvailable = entryProjectionAvailable,
                initialTimelineAnchored = initialTimelineAnchored,
                hasScrollRestore = scrollRestore != null,
            )
        ) {
            return@LaunchedEffect
        }

        // The chat-list projection carries the durable first-unread id. Page it
        // into the bounded timeline before revealing or positioning the list;
        // count-from-tail is only a compatibility fallback for older projections.
        val unreadId =
            resolveConversationEntryUnreadMessageId(
                snapshot = entryUnreadSnapshot,
                timeline = { controller.timeline },
                loadUntilMessageAvailable = controller::loadConversationEntryUnreadMessageAvailable,
            )
        val anchoredTimeline = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        if (anchoredTimeline.isEmpty()) return@LaunchedEffect
        val anchoredOlderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        val anchoredInlineTopErrorCount =
            if (
                controller.error != null &&
                controller.errorEdge == ConversationLoadFailureEdge.TOP
            ) {
                1
            } else {
                0
            }
        val anchoredLeadingStructuralRowCount = anchoredOlderHeaderCount + anchoredInlineTopErrorCount
        val anchoredTailTimelineIndex =
            requireNotNull(
                conversationTimelineTailListIndex(
                    anchoredTimeline.size,
                    anchoredLeadingStructuralRowCount,
                ),
            )
        val renderedUnreadIndex =
            unreadId?.let { id -> anchoredTimeline.indexOfFirst { it.record.messageIdHex == id } } ?: -1
        val targetIndex =
            if (renderedUnreadIndex >= 0) {
                1 + anchoredLeadingStructuralRowCount + renderedUnreadIndex
            } else {
                anchoredTailTimelineIndex
            }
        val resultingMode =
            if (renderedUnreadIndex >= 0) {
                ConversationScrollMode.ReadingHistory(unreadId, 0)
            } else {
                ConversationScrollMode.FollowingTail
            }
        if (hasSentMessageAfterUnreadBoundary(anchoredTimeline, unreadId)) {
            entryUnreadDividerRetired = true
        }
        val captureInitialLayout = {
            val layoutInfo = listState.layoutInfo
            ConversationInitialAnchorLayout(
                viewportHeight = layoutInfo.viewportSize.height,
                targetItemSize =
                    layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == targetIndex }
                        ?.size,
            )
        }

        /** Commits the chosen unread or tail owner only after its target and viewport are stable. */
        suspend fun commitInitialPosition(): Boolean =
            if (resultingMode is ConversationScrollMode.FollowingTail) {
                scrollCoordinator.commitInitialTailAnchor(
                    targetIndex = targetIndex,
                    captureLayout = captureInitialLayout,
                )
            } else {
                scrollCoordinator.commitInitialAnchor(
                    targetMessageId = unreadId,
                    reason = ConversationScrollReason.InitialAnchor,
                    resultingMode = resultingMode,
                    targetIndex = targetIndex,
                    captureLayout = captureInitialLayout,
                )
            }
        while (!commitInitialPosition()) {
            // Do not reveal until the target and viewport are stable.
            withFrameNanos { }
        }
        if (resultingMode is ConversationScrollMode.ReadingHistory) {
            val unreadItem = anchoredTimeline.getOrNull(renderedUnreadIndex)
            scrollCoordinator.settleReadingAt(
                ConversationScrollAnchor(
                    listIndex = targetIndex,
                    pixelOffset = 0,
                    itemId = unreadItem?.id,
                    messageId = unreadId,
                ),
            )
        }
        postInitialReanchorGate.commit(
            structure =
                ConversationTimelineStructure(
                    rowKeys = anchoredTimeline.map { it.id to it.record.messageIdHex },
                    olderHeaderCount = anchoredOlderHeaderCount,
                    inlineTopErrorCount = anchoredInlineTopErrorCount,
                ),
            viewportHeight = listState.layoutInfo.viewportSize.height,
        )
        initialTimelineAnchored = true
        navigationState.lastFollowedLatestId = anchoredTimeline.lastOrNull()?.id
    }
    LaunchedEffect(controller, latestTimelineItemId, initialTimelineAnchored) {
        if (!initialTimelineAnchored || renderedTimeline.isEmpty()) return@LaunchedEffect
        val latestId = renderedTimeline.lastOrNull()?.id
        val previousId = navigationState.lastFollowedLatestId
        // A genuine append: the last id changed and the row we last followed is
        // still present. An older-page trim drops it and is therefore excluded.
        val isAppend =
            previousId != null &&
                latestId != null &&
                latestId != previousId &&
                renderedTimeline.any { it.id == previousId }
        navigationState.lastFollowedLatestId = latestId ?: previousId
        if (isAppend) {
            scrollCoordinator.followTailIfAllowed(
                resolveTailIndex = { currentTailIndex },
                reason = ConversationScrollReason.NewMessage,
            )
        }
    }

    // Re-resolve the durable history anchor only when the list structure or
    // header changes. Same-row projection and media hydration must not restart
    // anchoring after the conversation is already visible.
    LaunchedEffect(
        controller,
        renderedTimelineAnchorKeys,
        olderHeaderCount,
        inlineTopErrorCount,
        initialTimelineAnchored,
        postInitialReanchorGate,
    ) {
        val structureChanged =
            postInitialReanchorGate.onStructure(
                ConversationTimelineStructure(
                    rowKeys = renderedTimelineAnchorKeys,
                    olderHeaderCount = olderHeaderCount,
                    inlineTopErrorCount = inlineTopErrorCount,
                ),
            )
        if (initialTimelineAnchored && structureChanged) {
            scrollCoordinator.reanchorReadingHistory(::resolveScrollAnchorIndex)
        }
    }

    // Reacting to the last message grows its bubble height (a reaction chip) but
    // doesn't change any timeline id, so the append-follow above never sees it.
    // Settle against the row's final measured height instead of assuming the
    // chip is complete after one frame. The coordinator owns cancellation and
    // refuses this correction while the user is reading history.
    LaunchedEffect(
        controller,
        renderedTimeline
            .lastOrNull()
            ?.record
            ?.messageIdHex
            ?.let { controller.reactions[it] },
    ) {
        if (initialTimelineAnchored && renderedTimeline.isNotEmpty()) {
            val lastMessageId = renderedTimeline.last().record.messageIdHex
            scrollCoordinator.settleTailAfterLayoutChange(
                resolveTailIndex = { currentTailIndex },
                captureLayout = {
                    val layoutInfo = listState.layoutInfo
                    val tailInfo =
                        layoutInfo.visibleItemsInfo.firstOrNull { visible ->
                            visible.index == currentTailIndex
                        }
                    ConversationTailLayout(
                        lastRowHeightPx = navigationState.timelineItemHeightsPx[lastMessageId],
                        tailOffsetPx = tailInfo?.offset,
                        tailSizePx = tailInfo?.size,
                        viewportEndOffsetPx = layoutInfo.viewportEndOffset,
                    )
                },
            )
        }
    }

    LaunchedEffect(routeTransitionInProgress) {
        if (routeTransitionInProgress) {
            routePresentationFrozen = true
            return@LaunchedEffect
        }
        if (!routePresentationFrozen) return@LaunchedEffect
        // Keep the terminal frame and the first post-settle frame identical.
        withFrameNanos { }
        routePresentationFrozen = false
    }

    // Scroll-to-message for a chat-list message-body search hit (issue #290).
    // Waits for the first-open anchor to settle, then pages the local timeline
    // back until the matched message is materialized and scrolls to it with a
    // brief highlight — the same affordance the reply-jump uses. Fires once
    // per (chat.id, focusMessageId); a missing message (e.g. it was deleted
    // between the search and the tap) just toasts and leaves the user at the
    // normal anchor. Local-only: loadUntilMessageAvailable paginates the
    // already-persisted store, never a relay fetch.
    LaunchedEffect(controller, focusMessageId, focusMessageRequestId, ttsFocusSessionId) {
        /** Reads the latest route- and account-scoped focus target for this effect generation. */
        fun latestFocusMessageId(): String? {
            val sessionId = ttsFocusSessionId ?: return focusMessageId
            return appState
                .currentTtsConversationDestination()
                ?.takeIf {
                    it.sessionId == sessionId &&
                        it.accountRef == conversationAccountRef &&
                        it.groupIdHex.equals(controller.group.groupIdHex, ignoreCase = true)
                }?.passage
                ?.messageIdHex
        }

        var focus = latestFocusMessageId() ?: return@LaunchedEffect
        // Let the initial unread/newest anchor run first so our scroll isn't
        // immediately overwritten by it.
        snapshotFlow { initialTimelineAnchored }.filter { it }.first()
        var target = controller.loadScrollNavigationTarget(focus)
        val latestFocus = latestFocusMessageId()
        if (ttsFocusSessionId != null && latestFocus == null) return@LaunchedEffect
        if (latestFocus != null && latestFocus != focus) {
            focus = latestFocus
            target = controller.loadScrollNavigationTarget(focus)
        }
        if (ttsFocusSessionId != null && latestFocusMessageId() != focus) return@LaunchedEffect
        if (target == null) {
            appState.present(R.string.toast_original_message_unavailable)
            return@LaunchedEffect
        }
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == target }
        if (timelineIndex < 0) {
            appState.present(R.string.toast_original_message_unavailable)
            return@LaunchedEffect
        }
        val leadingStructuralRowCount =
            controller.conversationLeadingStructuralRowCount(
                controller.timeline.count { !MessageProjector.isEdit(it.record) },
            )
        // Center the match so prior + subsequent context is visible (#595).
        val centered =
            centerTimelineItemAt(
                target,
                1 + leadingStructuralRowCount + timelineIndex,
                ConversationScrollReason.FocusMessage,
            )
        if (!centered) return@LaunchedEffect
        showTransientMessageHighlight(target)
    }

    // Scroll-driven read pointer advance. Watches the shared read anchor
    // (`readAnchorMessageId`) so the FFI only sees IDs that strictly advance
    // the pointer — scroll-up cannot regress the count. Settle-gated
    // (`!isScrollInProgress`) avoids per-frame FFI hops while scrolling.
    LaunchedEffect(listState, controller) {
        snapshotFlow {
            if (!initialTimelineAnchored || listState.isScrollInProgress) {
                null
            } else {
                readAnchorMessageId
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { messageId ->
                if (messageId.isNotBlank()) {
                    controller.markReadUpTo(messageId)
                }
            }
    }

    // Own prepared files above the details early return so staged media
    // survives the round trip; its UI is rendered only on this screen.
    val mediaDraftState =
        rememberConversationMediaDraftState(
            appState = appState,
            controller = controller,
            chatId = chat.id,
            mediaSlots = pendingMediaSlots,
        )

    if (showDetails) {
        GroupDetailsScreen(
            appState = appState,
            controller = controller,
            onBack = {
                showDetails = false
                openTransferOnDetails = false
                openAddMemberOnDetails = false
            },
            onLeft = exitConversation,
            onJumpToMessage = { messageId ->
                showDetails = false
                openTransferOnDetails = false
                scrollToSearchMatch(messageId)
            },
            autoOpenTransferAdmin = openTransferOnDetails,
            autoOpenAddMember = openAddMemberOnDetails,
            onAutoOpenAddMemberConsumed = { openAddMemberOnDetails = false },
            onOpenSearch = {
                showDetails = false
                navigationState.searchOpen = true
            },
            onOpenConversation = { item, created ->
                showDetails = false
                onOpenConversation(item, created)
            },
            onGroupCreateSubmitted = onGroupCreateSubmitted,
            onGroupCreateCompletedOpen = onGroupCreateCompletedOpen,
            onGroupCreateFlowSuperseded = onGroupCreateFlowSuperseded,
        )
        return
    }

    var createOpenConversationTiming by remember(chat.id) {
        mutableStateOf(ChatCreateOpenConversationTimingState())
    }
    LaunchedEffect(chat.id) {
        if (!appState.hasActiveChatCreateOpenTiming()) return@LaunchedEffect
        withFrameNanos { }
        val stage =
            chatCreateOpenConversationTimingStage(
                createOpenConversationTiming,
                ChatCreateOpenConversationTimingEvent.ConversationFrameCommitted,
            )
        if (stage != null) {
            appState.markChatCreateOpenStage(stage)
            createOpenConversationTiming =
                reduceChatCreateOpenConversationTiming(
                    createOpenConversationTiming,
                    ChatCreateOpenConversationTimingEvent.ConversationFrameCommitted,
                )
        }
    }
    LaunchedEffect(chat.id, composerGate, createOpenConversationTiming.frameReadyMarked) {
        if (!appState.hasActiveChatCreateOpenTiming()) return@LaunchedEffect
        if (composerGate != ComposerGate.COMPOSER) return@LaunchedEffect
        val stage =
            chatCreateOpenConversationTimingStage(
                createOpenConversationTiming,
                ChatCreateOpenConversationTimingEvent.ComposerReady,
            )
        if (stage != null) {
            appState.completeChatCreateOpenTiming(stage)
            createOpenConversationTiming =
                reduceChatCreateOpenConversationTiming(
                    createOpenConversationTiming,
                    ChatCreateOpenConversationTimingEvent.ComposerReady,
                )
        }
    }
    val mentionPicker =
        rememberConversationMentionPickerState(
            controller = controller,
            appState = appState,
            requestProfiles = composerGate == ComposerGate.COMPOSER,
        )

    // #1206: one composer text state shared by the main composer and the
    // long-message reader's composer, so in-progress text never drifts between
    // them. Created at screen scope so both the bottom-bar composer and the
    // per-message reader can receive the same instance.
    val draftAccountRef = controller.boundAccountRef
    LaunchedEffect(draftAccountRef, controller.group.groupIdHex) {
        appState.loadDraft(draftAccountRef, controller.group.groupIdHex)
    }
    val restoredDraftSnapshot = appState.draftSnapshotFor(draftAccountRef, controller.group.groupIdHex)
    val composerShareRevision =
        rememberComposerShareRevision(
            externalRevision = appState.inboundShareRevision,
            editingMessageId = controller.editingMessageId,
        )
    val composerDictationRevision =
        draftAccountRef?.let { accountRef ->
            appState.conversationDictation.completionRevision(
                accountRef = accountRef,
                groupIdHex = controller.group.groupIdHex,
            )
        } ?: 0
    // Capture the revision for this navigation entry. A later accepted
    // transcript must rehydrate text/selection without being mistaken for a
    // restored draft and reopening the IME. Re-entering the conversation gets
    // a fresh baseline, so genuine draft restoration still focuses once.
    val composerDictationRevisionOnEntry =
        rememberComposerDictationRevisionOnEntry(
            groupIdHex = controller.group.groupIdHex,
            currentRevision = composerDictationRevision,
        )
    val composerTextState =
        rememberComposerTextState(
            draftKey = controller.group.groupIdHex,
            initialDraft = restoredDraftSnapshot?.textFieldValue ?: TextFieldValue(""),
            externalRevision = composerShareRevision to composerDictationRevision,
        )
    val composerAutoFocusConsumed = remember(chat.id) { mutableStateOf(false) }

    // Hoisted from ComposerBar so a tap on the transcript can dismiss the
    // attachment sheet — the composer itself stays interactive while it's open.
    val composerAttachmentSheet = rememberComposerAttachmentSheetState()

    fun startBatchDelete(
        attempts: List<BatchDeleteAttempt>,
        priorRetryState: BatchDeleteRetryState?,
    ) {
        if (attempts.isEmpty() || !batchDeleteSubmissionGuard.tryStart()) return
        batchDeleteInFlight = true
        appState.launchMutation {
            val completedOutcomes = mutableListOf<BatchDeleteOperationOutcome>()
            try {
                val result =
                    executeBatchDelete(
                        attempts = attempts,
                        // The controller revalidates current moderation
                        // capability on every initial attempt and retry.
                        deleteForEveryone = { record ->
                            controller.deleteMessageResult(record, presentFailure = false)
                        },
                        hideLocally = controller::hideMessageForMeResult,
                        onOutcome = completedOutcomes::add,
                    )
                val retryState =
                    priorRetryState?.afterRetry(result)
                        ?: BatchDeleteRetryState.from(result)
                selectedMessages.clear()
                retryState.failedAttempts.forEach { attempt ->
                    val selection = attempt.selection
                    selectedMessages[selection.action.messageId] = selection
                }
                if (retryState.failures.isEmpty()) {
                    batchDeleteRetryState = null
                    controller.boundAccountRef?.let { accountRef ->
                        appState.presentConversationTransient(
                            accountRef = accountRef,
                            groupIdHex = controller.group.groupIdHex,
                            titleRes = R.string.batch_delete_complete,
                        )
                    }
                } else {
                    batchDeleteRetryState = retryState
                }
            } catch (cancellation: CancellationException) {
                // Keep unresolved work selected, but do not retry operations
                // that committed before lifecycle/user cancellation arrived.
                val completedResult = BatchDeleteResult(completedOutcomes)
                completedOutcomes
                    .filter(BatchDeleteOperationOutcome::succeeded)
                    .forEach { selectedMessages.remove(it.attempt.selection.action.messageId) }
                val updatedRetryState =
                    priorRetryState?.afterRetry(completedResult)
                        ?: BatchDeleteRetryState.from(completedResult)
                batchDeleteRetryState = updatedRetryState.takeIf { it.failures.isNotEmpty() }
                throw cancellation
            } finally {
                batchDeleteSubmissionGuard.finish()
                batchDeleteInFlight = false
                showBatchDeleteConfirm = false
            }
        }
    }

    val openDetailsDescription = stringResource(R.string.details)
    LaunchedEffect(batchSelectionUi.forwardPayloads.isEmpty()) {
        batchForwardSheetOpen =
            batchForwardSheetOpenForPayloads(
                currentlyOpen = batchForwardSheetOpen,
                forwardPayloads = batchSelectionUi.forwardPayloads,
            )
    }
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .dismissTextSelectionOnOutsideTap(
                    active = textSelectionMessageId != null,
                    selectedBoundsInWindow = textSelectionBubbleBounds,
                    onDismiss = ::clearTextSelection,
                ),
        // The transcript consumes IME insets; the composer bottom bar is the sole
        // owner of keyboard padding so the reply-preview chip and input row move
        // as one cluster (#895, #1109).
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            ConversationTopBar(
                selectionMode = selectionMode,
                selectedCount = batchSelectionUi.actionItems.size,
                onCloseSelection = {
                    if (!batchDeleteInFlight) {
                        batchDeleteRetryState = null
                        selectedMessages.clear()
                    }
                },
                searchOpen = navigationState.searchOpen,
                searchQuery = navigationState.searchQuery,
                onSearchQueryChange = {
                    navigationState.searchJob?.cancel()
                    navigationState.searchJob = null
                    navigationState.navigateReplyJob?.cancel()
                    navigationState.targetNavigation.cancel()
                    navigationState.targetHighlight.clear()
                    navigationState.searchQuery = it
                    navigationState.searchPinnedMatchId = null
                },
                onClearSearch = {
                    navigationState.searchJob?.cancel()
                    navigationState.searchJob = null
                    navigationState.navigateReplyJob?.cancel()
                    navigationState.targetNavigation.cancel()
                    navigationState.targetHighlight.clear()
                    navigationState.searchQuery = ""
                    navigationState.searchPinnedMatchId = null
                },
                onCloseSearch = ::closeSearch,
                onSearchAction = { navigateToSearchMatch(forward = true) },
                searchFocusRequester = navigationState.searchFocusRequester,
                appState = appState,
                controller = controller,
                groupTitleCopy = groupTitleCopy,
                openedAsDmHint = openedAsDmHint,
                firstFrameAvatar = chat.firstFrameAvatar,
                freezeRoutePresentation = freezeRoutePresentation,
                openDetailsDescription = openDetailsDescription,
                onOpenDetails = { showDetails = true },
                onBack = exitConversation,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onOpenSearch = {
                    menuOpen = false
                    val bookmark = scrollCoordinator.bookmark(currentScrollAnchor())
                    val anchorMessage =
                        bookmark.anchor.messageId?.let { messageId ->
                            renderedTimeline.firstOrNull { it.record.messageIdHex == messageId }
                        }
                    navigationState.preSearchScrollAnchor =
                        ConversationSearchScrollAnchor(
                            bookmark = bookmark,
                            match =
                                anchorMessage?.let {
                                    ConversationSearchMatch(
                                        messageIdHex = it.record.messageIdHex,
                                        timelineAt = it.projected?.timelineAt ?: it.record.recordedAt,
                                    )
                                },
                        )
                    navigationState.searchOpen = true
                },
                onToggleArchived = {
                    menuOpen = false
                    appState.launchMutation { controller.setArchived(!controller.presentedArchived) }
                },
                onRequestLeave = {
                    menuOpen = false
                    appState.launchMutation {
                        when (val leaveAction = controller.leaveAction()) {
                            LeaveAction.SoleAdminMustTransfer -> showTransferAdminFirst = true
                            LeaveAction.SoleMemberDeletesGroup,
                            LeaveAction.Standard,
                            -> pendingTopBarLeaveAction = leaveAction
                        }
                    }
                },
                onTtsTransportBodyClick = onTtsTransportBodyClick,
                compactHeight = compactHeightConversation,
            )
        },
        bottomBar = {
            ConversationBottomBar(
                compactHeight = compactHeightConversation,
                selectionMode = selectionMode,
                selectionActionAvailability =
                    batchSelectionUi.actionAvailability.let { availability ->
                        if (batchDeleteInFlight) {
                            BatchSelectionActionAvailability(
                                canCopy = false,
                                canForward = false,
                                canSave = false,
                                canReply = false,
                                canInfo = false,
                                canDelete = false,
                            )
                        } else {
                            availability.copy(
                                canSave = availability.canSave && !batchAttachmentSaveInFlight,
                                canDelete = availability.canDelete && batchDeleteRetryState == null,
                            )
                        }
                    },
                selectionForwardBlockedReason = batchSelectionUi.forwardBlockedReason,
                onCopySelection = {
                    if (batchSelectionUi.copyText.isNotBlank()) {
                        clipboard.setText(AnnotatedString(batchSelectionUi.copyText))
                        selectedMessages.clear()
                    }
                },
                onForwardSelection = {
                    if (batchSelectionUi.forwardPayloads.isNotEmpty()) batchForwardSheetOpen = true
                },
                onSaveSelection = {
                    if (batchSelectionUi.actionAvailability.canSave && !batchAttachmentSaveInFlight) {
                        batchAttachmentSaveInFlight = true
                        val selections = orderedBatchSelections(selectedMessages.values)
                        selectedMessages.clear()
                        appState.launchMutation {
                            try {
                                val summary =
                                    aggregateMessageAttachmentSaveSummaries(
                                        selections.map { selection ->
                                            val record = selection.record
                                            val mediaReferences = controller.mediaReferencesFor(record)
                                            saveMessageMediaAttachments(
                                                context = context,
                                                controller = controller,
                                                messageIdHex = record.messageIdHex,
                                                mediaReferences = mediaReferences,
                                                mine = controller.isMessageMine(record),
                                                documentSaveFallback = documentSaveFallback,
                                            )
                                        },
                                    )
                                appState.presentAttachmentSaveOutcome(
                                    context = context,
                                    summary = summary,
                                    conversation =
                                        controller.boundAccountRef?.let { accountRef ->
                                            ConversationNoticeDestination(accountRef, controller.group.groupIdHex)
                                        },
                                )
                            } finally {
                                batchAttachmentSaveInFlight = false
                            }
                        }
                    }
                },
                onReplySelection = {
                    batchSelectionUi.selections.singleOrNull()?.let { selection ->
                        controller.replyingTo = selection.record
                        selectedMessages.clear()
                    }
                },
                onInfoSelection = {
                    batchInfoSelection = batchSelectionUi.selections.singleOrNull()
                },
                onDeleteSelection = { showBatchDeleteConfirm = true },
                batchDeleteRetryState = batchDeleteRetryState,
                batchDeleteInFlight = batchDeleteInFlight,
                onRetryBatchDelete = {
                    batchDeleteRetryState?.let { retryState ->
                        startBatchDelete(retryState.failedAttempts, retryState)
                    }
                },
                onDismissBatchDeleteFailure = {
                    if (!batchDeleteInFlight) {
                        batchDeleteRetryState = null
                        selectedMessages.clear()
                    }
                },
                onCopyBatchDeleteReport = {
                    batchDeleteRetryState?.let { retryState ->
                        clipboard.setText(AnnotatedString(batchDeleteDiagnosticReport(retryState)))
                    }
                },
                searchOpen = navigationState.searchOpen,
                searchMatchCount = effectiveSearchMatchIds.size,
                searchActiveIndex = searchActiveIndex,
                hasSearchQuery = navigationState.searchQuery.isNotBlank(),
                onPreviousSearchMatch = { navigateToSearchMatch(forward = false) },
                onNextSearchMatch = { navigateToSearchMatch(forward = true) },
                hasError =
                    loadFailurePlacement == LoadFailurePlacement.FullScreen ||
                        navigationState.initialTimelineBackfillNoProgress,
                composerGate = composerGate,
                controller = controller,
                appState = appState,
                messageTextCopy = messageTextCopy,
                onBack = exitConversation,
                initialDraft = restoredDraftSnapshot?.textFieldValue ?: TextFieldValue(""),
                onDraftChange = { appState.setDraft(draftAccountRef, controller.group.groupIdHex, it) },
                composerTextState = composerTextState,
                composerAttachmentSheet = composerAttachmentSheet,
                onAfterSend = {
                    revealSentMessage()
                },
                onPickFromGallery = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                onPickRecentMedia = { uri ->
                    pendingMediaSlots =
                        appendPendingMediaSlots(pendingMediaSlots, listOf(uri), MEDIA_PICKER_MAX_ITEMS)
                },
                onCaptureFromCamera = {
                    val granted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        launchCameraCapture()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onPickDocument = { documentPickerLauncher.launch(arrayOf("*/*")) },
                onShareLocation = {
                    if (hasLocationGrant(Manifest.permission.ACCESS_FINE_LOCATION) ||
                        hasLocationGrant(Manifest.permission.ACCESS_COARSE_LOCATION)
                    ) {
                        locationPickerOpen = true
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                onShareUser = { shareUserPickerOpen = true },
                onShareContact = { contactPickerLauncher.launch(Unit) },
                onPasteImageUris = { uris ->
                    val openSlots = (MEDIA_PICKER_MAX_ITEMS - pendingMediaSlots.size).coerceAtLeast(0)
                    val pasteCandidates = uris.take(openSlots)
                    val localUris =
                        pasteCandidates.mapNotNull { uri ->
                            materializeReceiveContentImageUri(context, uri)
                        }
                    if (localUris.size < pasteCandidates.size) {
                        appState.present(R.string.toast_couldnt_decode_image, copyable = true)
                    }
                    if (localUris.isNotEmpty()) {
                        pendingMediaSlots =
                            appendPendingMediaSlots(pendingMediaSlots, localUris, MEDIA_PICKER_MAX_ITEMS)
                    }
                },
                voiceRecordingController = voiceRecordingController,
                mentionCandidates = mentionPicker.candidates,
                mentionPickerEnabled = mentionPicker.enabled,
                autoFocusOnEnter = justCreated && !freezeRoutePresentation,
                autoFocusOnDraftRestore =
                    shouldAutoFocusComposerOnDraftRestore(
                        snapshot = restoredDraftSnapshot,
                        dictationRevisionOnEntry = composerDictationRevisionOnEntry,
                        currentDictationRevision = composerDictationRevision,
                    ) &&
                        !freezeRoutePresentation,
                autoFocusConsumedState = composerAutoFocusConsumed,
                composerFocus = composerFocus,
                onComposerFocusChanged = { focused ->
                    if (focused) composerDismissInProgress = false
                    if (focused && !composerFocused && !imeIsOpen) {
                        imeTransitionBookmark = scrollCoordinator.bookmark(currentScrollAnchor())
                    } else if (!focused && !imeIsOpen) {
                        imeTransitionBookmark = null
                    }
                    composerFocused = focused
                },
                onComposerPreImeBack = {
                    if (composerDismissInProgress) {
                        exitConversation()
                    } else {
                        composerDismissInProgress = true
                        keyboardController?.hide()
                    }
                },
                onBottomInputChanged = { bottomInputRevision++ },
                onKeyboardRestoreFromCustomInput = { suppressNextImeOpenReanchor.set(true) },
                onKeyboardRestoreFromCustomInputFailed = { suppressNextImeOpenReanchor.set(false) },
                recentEmojis = recentEmojiRecentsOwner.recents,
                onEmojiUsed = { recentEmojiRecentsOwner.onEmojiUsed(it) },
                onBottomChromeMeasured = { heightPx, chromeBottomPx ->
                    bottomChromeHeightObserver.onMeasured(heightPx)
                    if (measuredBottomChromeHeightPx != heightPx) {
                        measuredBottomChromeHeightPx = heightPx
                    }
                    snackbarBottomInset.value =
                        with(density) { (heightPx - chromeBottomPx).coerceAtLeast(0).toDp() }
                },
            )
        },
    ) { padding ->
        ConversationTransientNoticeLayout(
            notice = appState.transientNotice,
            accountRef = conversationAccountRef,
            groupIdHex = controller.group.groupIdHex,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // The composer bottomBar owns IME padding; consume here so the
                    // transcript does not count the keyboard a second time (#895).
                    .consumeWindowInsets(WindowInsets.ime),
        ) {
            when {
                navigationState.initialTimelineBackfillNoProgress ->
                    ConversationLoadErrorContent(
                        error = InitialTimelineBackfillNoProgressError,
                        onRetry = {
                            navigationState.initialTimelineBackfillNoProgress = false
                            navigationState.initialTimelineBackfillRetryGeneration += 1L
                        },
                    )
                loadFailurePlacement == LoadFailurePlacement.FullScreen ->
                    ConversationLoadErrorContent(
                        error = requireNotNull(controller.error),
                        onRetry = {
                            scope.launch {
                                controller.retryLoadFailure()
                                navigationState.initialTimelineBackfillRetryGeneration += 1L
                            }
                        },
                    )
                controller.group.pendingConfirmation && renderedTimeline.isEmpty() ->
                    InvitePreviewPlaceholder(
                        inviterName = controller.inviteAccount?.let { appState.chatMemberTitle(it) },
                    )
                renderedTimeline.isEmpty() && controller.isLoading ->
                    ConversationInitialLoadingOverlay(visible = true)
                renderedTimeline.isEmpty() &&
                    !controller.hasMoreBefore &&
                    !controller.hasMoreAfterTimeline &&
                    !controller.isLoadingOlder &&
                    !controller.isLoading &&
                    navigationState.initialTimelineLoadStarted -> {
                    if (
                        canInviteFromEmptyGroup(
                            isSelfMember = controller.isSelfMember,
                            isSelfAdmin = controller.isSelfAdmin,
                            membersLoaded = controller.membersLoaded,
                            memberCount = controller.memberCount,
                        )
                    ) {
                        EmptyGroupConversation(
                            onAddMembers = {
                                openAddMemberOnDetails = true
                                showDetails = true
                            },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.no_messages_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                                    // Paint, TalkBack exposure, and first-useful-frame
                                    // reporting share one predicate. An oversized cached
                                    // final row therefore cannot become observable at its
                                    // start before the physical-end correction lands.
                                    .drawWithContent {
                                        if (transcriptVisibilityCommitted) drawContent()
                                    }.graphicsLayer {
                                        alpha = if (transcriptVisibilityCommitted) 1f else 0f
                                    }.semantics {
                                        if (!transcriptVisibilityCommitted) hideFromAccessibility()
                                    }.performanceTestTag(
                                        PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE,
                                        enabled = transcriptVisibilityCommitted && renderedTimeline.isNotEmpty(),
                                    ).onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInWindow()
                                        transcriptWindowTop = position.y
                                        transcriptHeightPx = coordinates.size.height.toFloat()
                                        ttsFollowHandle.sentenceLayouts.updateViewportBounds(
                                            Rect(
                                                left = position.x,
                                                top = position.y,
                                                right = position.x + coordinates.size.width,
                                                bottom = position.y + coordinates.size.height,
                                            ),
                                        )
                                    },
                            verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
                            // Content padding owns the final composer interval
                            // and temporary notice clearance. Keeping spacing
                            // out of a lazy sentinel leaves the real last row as
                            // the stable tail anchor.
                            contentPadding = conversationTimelineContentPadding(snackbarContentInset.value),
                        ) {
                            item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
                            conversationLoadErrorItem(
                                key = "conversation-load-error-top",
                                error = controller.error,
                                placement = loadFailurePlacement,
                                errorEdge = controller.errorEdge,
                                targetEdge = ConversationLoadFailureEdge.TOP,
                                onRetry = { scope.launch { controller.retryLoadFailure() } },
                            )
                            if (controller.hasMoreBefore || controller.isLoadingOlder) {
                                item(key = "older-messages-loading") {
                                    Box(
                                        Modifier.fillMaxWidth().height(40.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (controller.isLoadingOlder) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else {
                                            IconButton(onClick = { scope.launch { controller.loadOlder() } }) {
                                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                                            }
                                        }
                                    }
                                }
                            }
                            itemsIndexed(
                                renderedTimeline,
                                key = { _, item -> item.id },
                                // Pool layouts by category so Compose can reuse
                                // structurally similar rows across scroll.
                                contentType = { _, item ->
                                    when {
                                        MessageProjector.isGroupSystem(item.record) -> "groupSystem"
                                        MessageProjector.isAgentOperation(item.record) -> "agentOperation"
                                        else -> "message"
                                    }
                                },
                            ) { index, item ->
                                val messageId = item.record.messageIdHex
                                TimelineRow(
                                    item = item,
                                    older = renderedTimeline.getOrNull(index - 1),
                                    newer = renderedTimeline.getOrNull(index + 1),
                                    transcriptLocale = transcriptLocale,
                                    entryUnreadCount = entryUnreadCount,
                                    entryUnreadDividerRetired = entryUnreadDividerRetired,
                                    entryFirstUnreadMessageId = entryFirstUnreadMessageId,
                                    onMeasured = { id, height ->
                                        if (navigationState.timelineItemHeightsPx[id] != height) {
                                            navigationState.timelineItemHeightsPx[id] = height
                                        }
                                    },
                                    appState = appState,
                                    controller = controller,
                                    conversationVisualPages = conversationVisualPages,
                                    eventCardResolver = eventCardResolver,
                                    documentSaveFallback = documentSaveFallback,
                                    composerTextState = composerTextState,
                                    highlighted = messageId == navigationState.targetHighlight.highlightedMessageId,
                                    selectionMode = selectionMode,
                                    textSelectionMode = textSelectionMessageId == messageId,
                                    onTextSelectionModeChange = { enabled ->
                                        if (enabled) {
                                            openActionMenuId = null
                                            textSelectionMessageId = messageId
                                            textSelectionBubbleBounds = null
                                        } else if (textSelectionMessageId == messageId) {
                                            clearTextSelection()
                                        }
                                    },
                                    onTextSelectionBoundsChange = { bounds ->
                                        if (textSelectionMessageId == messageId) textSelectionBubbleBounds = bounds
                                    },
                                    batchSelectable =
                                        messageId in selectableMessages &&
                                            batchDeleteRetryState == null &&
                                            !batchDeleteInFlight,
                                    selected = selectedMessages.containsKey(messageId),
                                    onToggleSelection = {
                                        if (batchDeleteRetryState == null && !batchDeleteInFlight) {
                                            if (selectedMessages.containsKey(messageId)) {
                                                selectedMessages.remove(messageId)
                                            } else {
                                                selectableMessages[messageId]?.let { selectedMessages[messageId] = it }
                                            }
                                        }
                                    },
                                    rangeDragActive = dragAnchorTimelineId == item.id,
                                    onDragSelectionStart = { pointerWindowY ->
                                        openActionMenuId = null
                                        clearTextSelection()
                                        ttsFollowHandle.suspendForDirectDrag(
                                            state = appState.ttsController.state.value,
                                            ownsSession =
                                                appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
                                        )
                                        scrollCoordinator.onUserGestureStarted(currentScrollAnchor())
                                        dragAnchorTimelineId = item.id
                                        dragPointerWindowY = pointerWindowY
                                    },
                                    onDragSelection = { pointerWindowY ->
                                        dragPointerWindowY = pointerWindowY
                                        updateMessageDragSelection(pointerWindowY)
                                    },
                                    onDragSelectionEnd = { finishMessageDrag(clearSelection = false) },
                                    onDragSelectionCancel = { finishMessageDrag(clearSelection = true) },
                                    quickReactionEmojis = quickReactionEmojis,
                                    recentEmojis = recentEmojiRecentsOwner.recents,
                                    onEmojiUsed = { recentEmojiRecentsOwner.onEmojiUsed(it) },
                                    isActionMenuOpen = openActionMenuId == messageId,
                                    onActionMenuOpenChange = { open ->
                                        if (open) clearTextSelection()
                                        if (open) {
                                            openActionMenuId = messageId
                                        } else if (openActionMenuId == messageId) {
                                            openActionMenuId = null
                                        }
                                    },
                                    onQuickReactionsSave = { saveQuickReactionEmojis(it) },
                                    onQuickReactionsReset = { resetQuickReactionEmojis() },
                                    onReplyPreviewClick = { navigateToReplyTarget(it) },
                                    composerGate = composerGate,
                                    onBack = exitConversation,
                                    mentionCandidates = mentionPicker.candidates,
                                    mentionPickerEnabled = mentionPicker.enabled,
                                    collapseLongMessages = collapseLongMessages,
                                    ttsQuickTransportViewportLock = ttsQuickTransportViewportLock,
                                    ttsSentenceLayoutSink = ttsFollowHandle.sentenceLayouts,
                                    onTtsSentenceSeek = { state ->
                                        ttsFollowHandle.onSentenceSeek(
                                            state = state,
                                            ownsSession =
                                                appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
                                        )
                                    },
                                )
                            }
                            conversationLoadErrorItem(
                                key = "conversation-load-error-bottom",
                                error = controller.error,
                                placement = loadFailurePlacement,
                                errorEdge = controller.errorEdge,
                                targetEdge = ConversationLoadFailureEdge.BOTTOM,
                                onRetry = { scope.launch { controller.retryLoadFailure() } },
                            )
                        }
                        ConversationInitialLoadingOverlay(
                            visible = !transcriptVisibilityCommitted && !seededTailAlignmentRecoveryVisible,
                            graceMillis = CONVERSATION_ANCHORED_LOADING_GRACE_MILLIS,
                        )
                        ConversationSeededTailAlignmentRecovery(
                            visible = seededTailAlignmentRecoveryVisible,
                            onRetry = {
                                seededTailAlignmentRecoveryVisible = false
                                seededTailAlignmentRetryGeneration++
                            },
                        )
                        // Day of the topmost visible message, shown only while
                        // scrolling — the inline separators carry it at rest.
                        // Confined to its own child so the scroll-backed reads
                        // (label + isScrollInProgress) recompose only the ribbon,
                        // not this LazyColumn-hosting Box scope (#375).
                        if (initialTimelineAnchored) {
                            StickyDayRibbon(
                                listState = listState,
                                labelState = stickyDayLabelState,
                            )
                        }
                        if (initialTimelineAnchored && !selectionMode) {
                            Column(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (ttsFollowHandle.showResumeAction) {
                                    TtsResumeFollowButton(
                                        onClick = ttsFollowHandle::resumeFollow,
                                    )
                                }
                                // Jump-to-mention chip: tap visits the oldest unread
                                // mention and marks it read, so the count steps down.
                                val mentionCount = unreadMentionMessageIds.size
                                if (mentionCount > 0) {
                                    val jumpToMentionLabel = stringResource(R.string.conversation_jump_to_mention)
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        shadowElevation = 2.dp,
                                        modifier =
                                            Modifier
                                                .height(34.dp)
                                                .semantics { contentDescription = jumpToMentionLabel }
                                                .clickable { jumpToNextUnreadMention() },
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        ) {
                                            Text("@", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                if (mentionCount > 99) "99+" else mentionCount.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                                if (!nearBottom) {
                                    ConversationJumpToNewestButton(
                                        unreadIncomingCount = unreadIncomingCount,
                                        onClick = {
                                            scope.launch {
                                                val pendingMessageId = unreadJumpState.pendingMessageId
                                                val outcome =
                                                    scrollCoordinator.jumpToUnreadOrNewest(
                                                        pendingUnreadMessageId = pendingMessageId,
                                                        resolveUnreadIndex = {
                                                            pendingMessageId?.let(::currentTimelineListIndex)
                                                        },
                                                        isUnreadTopAligned = {
                                                            val targetIndex =
                                                                pendingMessageId?.let(::currentTimelineListIndex)
                                                            targetIndex != null &&
                                                                isConversationItemTopAligned(listState, targetIndex)
                                                        },
                                                        prepareTail = {
                                                            loadConversationTimelineToNewest(
                                                                hasMoreAfter = { controller.hasMoreAfterTimeline },
                                                                loadNewer = controller::loadNewerTimelinePage,
                                                            )
                                                        },
                                                        resolveTailIndex = { currentTailIndex },
                                                    )
                                                when (outcome) {
                                                    ConversationJumpToNewestOutcome.UnreadStart -> {
                                                        scrollCoordinator.settleReadingAt(currentScrollAnchor())
                                                        unreadJumpState = unreadJumpState.suppressCurrentStack()
                                                    }
                                                    ConversationJumpToNewestOutcome.Tail -> {
                                                        unreadJumpState = unreadJumpState.suppressCurrentStack()
                                                    }
                                                    ConversationJumpToNewestOutcome.Cancelled -> Unit
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
            }
            if (composerAttachmentSheet.isOpen) {
                // Transparent scrim over the transcript only — the composer
                // stays reachable, so the keyboard and emoji toggles can still
                // swap the sheet away directly. Carries a dismiss semantics
                // action + label so a screen reader announces (and can trigger)
                // this otherwise-invisible touch layer.
                val dismissLabel = stringResource(R.string.close)
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(composerAttachmentSheet) {
                            detectTapGestures { composerAttachmentSheet.dismiss() }
                        }.semantics {
                            contentDescription = dismissLabel
                            onClick(label = dismissLabel) {
                                composerAttachmentSheet.dismiss()
                                true
                            }
                        },
                )
            }
        }
    }

    if (batchForwardSheetOpen && batchSelectionUi.forwardPayloads.isNotEmpty()) {
        ForwardMessageSheet(
            appState = appState,
            payloads = batchSelectionUi.forwardPayloads,
            sourceAccountRef = controller.boundAccountRef,
            originGroupIdHex = controller.group.groupIdHex,
            onDismiss = {
                batchForwardSheetOpen = false
                selectedMessages.clear()
            },
        )
    }

    RestoredForwardRequestHost(appState = appState, controller = controller)

    batchInfoSelection?.let { infoSelection ->
        val infoRecord = infoSelection.record
        MessageInfoSheet(
            record = infoRecord,
            status = infoSelection.status,
            mine = controller.isMessageMine(infoRecord),
            senderDisplayName = appState.displayName(infoRecord.sender),
            senderNpub = appState.npubForDisplay(infoRecord.sender),
            onDismissRequest = { batchInfoSelection = null },
            onCopy = { value -> clipboard.setText(AnnotatedString(value)) },
        )
    }

    if (showBatchDeleteConfirm && batchSelectionUi.actionItems.isNotEmpty()) {
        BatchMessageDeleteDialog(
            selectedCount = batchSelectionUi.actionItems.size,
            breakdown = batchSelectionUi.deleteBreakdown,
            deleteInFlight = batchDeleteInFlight,
            onDeleteForEveryone = {
                startBatchDelete(
                    attempts = batchDeleteAttempts(batchSelectionUi.selections, BatchDeleteScope.EVERYONE),
                    priorRetryState = null,
                )
            },
            onDeleteForMe = {
                startBatchDelete(
                    attempts = batchDeleteAttempts(batchSelectionUi.selections, BatchDeleteScope.LOCAL_ONLY),
                    priorRetryState = null,
                )
            },
            onDismissRequest = { if (!batchDeleteInFlight) showBatchDeleteConfirm = false },
        )
    }

    pendingTopBarLeaveAction?.let { leaveAction ->
        val soleMember = leaveAction == LeaveAction.SoleMemberDeletesGroup
        val topBarGroupName = controller.title(groupTitleCopy)
        ConfirmDialog(
            title =
                if (soleMember) {
                    stringResource(R.string.confirm_leave_sole_member_title, topBarGroupName)
                } else {
                    stringResource(R.string.confirm_leave_title)
                },
            message =
                if (soleMember) {
                    stringResource(R.string.confirm_leave_sole_member_message)
                } else {
                    stringResource(R.string.confirm_leave_message)
                },
            confirmLabel = stringResource(R.string.leave),
            onConfirm = {
                pendingTopBarLeaveAction = null
                appState.launchMutation {
                    if (controller.leaveGroup()) exitConversation()
                }
            },
            onDismiss = { pendingTopBarLeaveAction = null },
            destructive = soleMember,
        )
    }

    if (showTransferAdminFirst) {
        ConfirmDialog(
            title = stringResource(R.string.sole_admin_leave_blocked_title),
            message = stringResource(R.string.sole_admin_leave_blocked_message),
            confirmLabel = stringResource(R.string.transfer_admin),
            onConfirm = {
                showTransferAdminFirst = false
                openTransferOnDetails = true
                showDetails = true
            },
            onDismiss = { showTransferAdminFirst = false },
        )
    }

    pendingContactShare?.let { contact ->
        ContactPreviewScreen(
            contact = contact,
            onDismiss = { pendingContactShare = null },
            onSend = { selected ->
                pendingContactShare = null
                mediaSender.sendSharedContact(selected)
            },
        )
    }

    if (shareUserPickerOpen) {
        val activeHex = conversationSelfAccountIdHex
        ContactPickerScreen(
            appState = appState,
            title = stringResource(R.string.share_user_title),
            selected = shareUserSelection,
            onBack = {
                shareUserPickerOpen = false
                shareUserSelection.clear()
            },
            onConfirm = {
                val picked = shareUserSelection.toList()
                shareUserPickerOpen = false
                shareUserSelection.clear()
                picked.forEach { sendSharedUser(it) }
            },
            confirmIcon = Icons.AutoMirrored.Filled.Send,
            confirmLabel = stringResource(R.string.send),
            autoSelectResolvedIdentifier = true,
            excludeAccountIdHexes = setOfNotNull(activeHex),
        )
    }

    if (locationPickerOpen) {
        LocationPickerScreen(
            hasFineGrant = hasLocationGrant(Manifest.permission.ACCESS_FINE_LOCATION),
            onDismiss = { locationPickerOpen = false },
            onPick = { location ->
                locationPickerOpen = false
                appState.launchMutation {
                    controller.send(formatLocationShareText(location)) { revealSentMessage() }
                }
            },
        )
    }

    ConversationMediaDraftContent(
        state = mediaDraftState,
        chatId = chat.id,
        mediaSlots = pendingMediaSlots,
        documentUris = pendingDocumentUris,
        onMediaSlotsChange = { pendingMediaSlots = it },
        onDocumentUrisChange = { pendingDocumentUris = it },
        mediaSender = mediaSender,
        chatTitle = controller.title(groupTitleCopy),
        composerText = { composerTextState.valueState.value.text },
        onCaptionAccepted = { seededCaption ->
            if (composerTextState.valueState.value.text == seededCaption) {
                composerTextState.valueState.value = TextFieldValue("")
            }
        },
        onAddPhotos = {
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        },
        onAddDocuments = { documentPickerLauncher.launch(arrayOf("*/*")) },
        onAfterSend = { revealSentMessage() },
    )
}
