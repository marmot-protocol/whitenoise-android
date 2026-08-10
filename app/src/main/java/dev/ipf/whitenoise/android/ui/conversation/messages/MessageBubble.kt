package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReplySwipeGesture
import dev.ipf.whitenoise.android.core.TimelineInvalidationPresentation
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.retentionIndicatorVisible
import dev.ipf.whitenoise.android.core.timelineInvalidationPresentation
import dev.ipf.whitenoise.android.core.usesPersistedFailurePresentation
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.MarkdownLinkTextLayout
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.longPressOrVerticalDrag
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.common.rememberedClockTime
import dev.ipf.whitenoise.android.ui.conversation.InvitePreviewActionBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.DisbandedGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerPurpose
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.FrozenGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentBytes
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVideoAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.saveAttachmentToMediaStore
import dev.ipf.whitenoise.android.ui.conversation.media.saveVideoToGallery
import dev.ipf.whitenoise.android.ui.conversation.reactions.CustomizeReactionsDialog
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionDetailsSheet
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionSummaryChip
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.conversation.replies.isOwnReplySender
import dev.ipf.whitenoise.android.ui.conversation.replies.senderTitleForReply
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedContactFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedLocationFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedUserFromText
import dev.ipf.whitenoise.android.ui.documentMentionsAccount
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt
import dev.ipf.whitenoise.android.ui.theme.amoledDirectionalAccentColor
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun messageBubbleBorder(
    highlighted: Boolean,
    mine: Boolean,
    customArgb: Long? = null,
    persistedFailure: Boolean = false,
): BorderStroke? {
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        persistedFailure -> null
        amoledAccent != null && customArgb != null -> BorderStroke(2.dp, colorFromArgb(customArgb))
        highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        amoledAccent != null -> BorderStroke(2.dp, customArgb?.let(::colorFromArgb) ?: amoledAccent)
        else -> null
    }
}

internal fun replyPreviewAccentArgb(
    insideBubble: Boolean,
    customBubbleColorActive: Boolean,
    presentation: BubblePresentation,
): Long? =
    if (customBubbleColorActive) {
        presentation.borderOverrideArgb ?: presentation.contentArgb.takeIf { insideBubble }
    } else {
        null
    }

@Composable
internal fun messageBubblePresentation(
    deleted: Boolean,
    mine: Boolean,
    customArgb: Long? = null,
    persistedFailure: Boolean = false,
): BubblePresentation {
    val colorScheme = MaterialTheme.colorScheme
    return resolveBubblePresentationArgb(
        deleted = deleted,
        amoled = isAmoledSurfaceTheme(),
        mine = mine,
        customArgb = customArgb,
        tokens =
            BubblePresentationTokens(
                errorBackgroundArgb = colorScheme.errorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                errorContentArgb = colorScheme.onErrorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceBackgroundArgb = colorScheme.surfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceContentArgb = colorScheme.onSurfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
                mineBackgroundArgb = colorScheme.primaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
                mineContentArgb = colorScheme.onPrimaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
                mentionAccentArgb = colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
            ),
        persistedFailure = persistedFailure,
    )
}

@Composable
internal fun messageBubbleFillColor(
    deleted: Boolean,
    mine: Boolean,
    persistedFailure: Boolean = false,
): Color = colorFromArgb(messageBubblePresentation(deleted, mine, persistedFailure = persistedFailure).backgroundArgb)

@Composable
internal fun messageBubbleTimestampColor(
    mine: Boolean,
    deleted: Boolean,
    persistedFailure: Boolean = false,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        persistedFailure -> colorScheme.onErrorContainer
        amoledAccent != null -> amoledAccent
        mine && !deleted -> colorScheme.onPrimaryContainer
        else -> colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun rememberMessageMediaReferences(
    tags: List<MessageTagFfi>,
    messageIdHex: String,
    sourceEpoch: ULong?,
    projectedMedia: List<MediaAttachmentReferenceFfi>?,
): List<MediaAttachmentReferenceFfi> =
    remember(tags, messageIdHex, sourceEpoch, projectedMedia) {
        projectedMedia ?: MediaReferenceSupport.parseAllImetaTags(tags, sourceEpoch ?: 0uL)
    }

internal fun messageBubbleLongPressPositionInWindow(
    rowCoordinates: LayoutCoordinates,
    localPosition: Offset,
): Offset = rowCoordinates.localToWindow(localPosition)

internal fun ColumnScope.messageBubbleBodyModifier(
    hasReplyPreview: Boolean,
    hasMedia: Boolean,
): Modifier =
    Modifier
        .align(Alignment.Start)
        // A text-only reply fills the quote-widened bubble. A media caption
        // fills the media card so its time/status cluster reaches the same
        // trailing edge as an ordinary full-width bubble footer.
        .then(if (hasMedia || hasReplyPreview) Modifier.fillMaxWidth() else Modifier)

internal enum class MessageAttachmentSaveOutcome {
    Complete,
    Partial,
    Failed,
    ;

    companion object {
        fun from(
            savedCount: Int,
            totalCount: Int,
        ): MessageAttachmentSaveOutcome =
            when {
                savedCount == totalCount -> Complete
                savedCount > 0 -> Partial
                else -> Failed
            }
    }
}

private fun WhiteNoiseAppState.presentAttachmentSaveOutcome(
    context: android.content.Context,
    savedCount: Int,
    totalCount: Int,
) {
    when (MessageAttachmentSaveOutcome.from(savedCount, totalCount)) {
        MessageAttachmentSaveOutcome.Complete -> present(R.string.shared_media_saved)
        MessageAttachmentSaveOutcome.Partial ->
            present(
                title = context.getString(R.string.shared_media_saved),
                detail = context.getString(R.string.conversation_search_match_count, savedCount, totalCount),
            )
        MessageAttachmentSaveOutcome.Failed -> present(R.string.shared_media_save_failed, copyable = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    // #1206: shared composer text state so the full-screen reader's composer
    // stays in sync with the main composer instead of holding a divergent field.
    composerTextState: ComposerTextState,
    highlighted: Boolean,
    selectionMode: Boolean,
    textSelectionMode: Boolean,
    onTextSelectionModeChange: (Boolean) -> Unit,
    onTextSelectionBoundsChange: (Rect?) -> Unit,
    batchSelectable: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    rangeDragActive: Boolean,
    onDragSelectionStart: (Float) -> Unit,
    onDragSelection: (Float) -> Boolean,
    onDragSelectionEnd: () -> Unit,
    onDragSelectionCancel: () -> Unit,
    quickReactionEmojis: List<String>,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    isActionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
    onQuickReactionsSave: (List<String>) -> Unit,
    onQuickReactionsReset: () -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    composerGate: ComposerGate,
    groupDisbanded: Boolean = false,
    inviteMutationInFlight: Boolean,
    onJoinInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    mentionCandidates: List<MentionComposer.Candidate>,
    mentionPickerEnabled: Boolean,
    showSenderName: Boolean = false,
    showSenderAvatar: Boolean = false,
    collapseLongMessages: Boolean = true,
    readOnly: Boolean = false,
) {
    val record = item.record
    val mine = controller.isMessageMine(record)
    val deleted = item.projected?.deleted == true || MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    // The same capability model the controller re-validates on the mutation
    // path; the UI only decides what to OFFER from it, never what to permit.
    val deleteCapability =
        if (readOnly) {
            MessageDeleteCapability(canDeleteForMe = false, canDeleteForEveryone = false)
        } else {
            controller.deleteCapabilityFor(record, alreadyDeleted = deleted)
        }
    // Convergence reasons and local publish failures keep their content and
    // add a warning; only unknown reasons still take the error-styled
    // tombstone. Explicit deletion always wins.
    val invalidated = !deleted && item.projected?.invalidationStatus != null
    val invalidationPresentation =
        if (deleted) {
            TimelineInvalidationPresentation.None
        } else {
            timelineInvalidationPresentation(item.projected?.invalidationStatus)
        }
    val persistedFailure =
        !deleted && item.projected?.let(::usesPersistedFailurePresentation) == true
    val bubbleTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    val bubbleSide = if (mine) BubbleSide.Mine else BubbleSide.Other
    val customBubbleArgb =
        appState.effectiveBubbleColorArgb(
            theme = bubbleTheme,
            side = bubbleSide,
            groupIdHex = controller.group.groupIdHex,
        )
    val colorScheme = MaterialTheme.colorScheme
    val customBubbleColorActive = customBubbleArgb != null && !deleted && !persistedFailure
    val bubblePresentation =
        messageBubblePresentation(
            deleted = deleted,
            mine = mine,
            customArgb = customBubbleArgb,
            persistedFailure = persistedFailure,
        )
    val bubbleContentColor = colorFromArgb(bubblePresentation.contentArgb)
    // #414: "you were mentioned" treatment. A received (not mine), live
    // message whose markdown body @-mentions the current
    // account gets a left-edge accent line so a self-mention is spottable while
    // scrolling. Keyed on the body tokens + account so a late account switch /
    // profile load re-evaluates. The resolver is the FFI bech32→hex encoding;
    // the detection walk itself is the pure documentMentionsAccount.
    val selfAccountIdHex = appState.activeAccount?.accountIdHex
    val mentionedSelf =
        !mine &&
            !deleted &&
            !persistedFailure &&
            remember(record.contentTokens, selfAccountIdHex) {
                documentMentionsAccount(
                    document = record.contentTokens,
                    accountIdHex = selfAccountIdHex,
                    resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                )
            }
    val mentionedYouLabel = stringResource(R.string.mentioned_you)
    val context = LocalContext.current
    // Freeze both the touch point and the selected message's window bounds when
    // the menu opens. The point seeds partial text selection; the bounds keep
    // the action surface visually attached to the bubble like Signal/Telegram.
    var longPressWindowPosition by remember(record.messageIdHex) { mutableStateOf<Offset?>(null) }
    var longPressWindowY by remember { mutableStateOf<Float?>(null) }
    var actionMenuAnchorBounds by remember(record.messageIdHex) { mutableStateOf<IntRect?>(null) }
    val rowCoordinates = remember(record.messageIdHex) { arrayOfNulls<LayoutCoordinates>(1) }
    val messageBoundsInWindow = remember(record.messageIdHex) { arrayOfNulls<IntRect>(1) }
    var swipeDrag by remember(record.messageIdHex) { mutableStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(targetValue = swipeDrag, label = "replySwipeOffset")
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val replySwipeThresholdPx = with(density) { 64.dp.toPx() }
    val maxSwipeOffsetPx = with(density) { 72.dp.toPx() }
    val messageTextCopy = rememberMessageTextCopy()
    val messageTextSelectionState = rememberSelectionState()
    val selectableTextLayouts =
        remember(record.messageIdHex) { mutableStateMapOf<Any, SelectableTextLayout>() }
    val markdownLinkLayouts =
        remember(record.messageIdHex) { mutableStateMapOf<Any, MarkdownLinkTextLayout>() }
    val markdownLinkLayoutReporter =
        remember(record.messageIdHex) {
            {
                key: Any,
                text: AnnotatedString,
                layoutResult: TextLayoutResult?,
                coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
                ->
                if (layoutResult != null && coordinates != null) {
                    markdownLinkLayouts[key] = MarkdownLinkTextLayout(text, layoutResult, coordinates)
                } else {
                    markdownLinkLayouts.remove(key)
                }
                Unit
            }
        }
    var textSelectionSeeded by remember(record.messageIdHex) { mutableStateOf(false) }
    val plainTextLayoutKey = remember(record.messageIdHex) { Any() }
    val plainTextLayoutTracker = remember(record.messageIdHex) { SelectableTextLayoutTracker() }
    val textSelectionClipboard =
        rememberExitOnCopyClipboard { onTextSelectionModeChange(false) }
    val selectableTextLayoutReporter =
        remember(textSelectionMode, record.messageIdHex) {
            { key: Any, layoutResult: TextLayoutResult?, coordinates: androidx.compose.ui.layout.LayoutCoordinates? ->
                if (textSelectionMode && layoutResult != null && coordinates != null) {
                    selectableTextLayouts[key] = SelectableTextLayout(key, layoutResult, coordinates)
                } else {
                    selectableTextLayouts.remove(key)
                }
                Unit
            }
        }
    val selectableTextLayoutSnapshot = selectableTextLayouts.values.toList()
    LaunchedEffect(textSelectionMode, selectableTextLayoutSnapshot, longPressWindowPosition) {
        if (!textSelectionMode || textSelectionSeeded || selectableTextLayoutSnapshot.isEmpty()) return@LaunchedEffect
        // Let every Markdown Text leaf report in this frame before calculating
        // the global selection offset across the SelectionContainer.
        withFrameNanos { }
        textSelectionSeedRange(selectableTextLayouts.values, longPressWindowPosition)?.let { range ->
            messageTextSelectionState.select(range)
            textSelectionSeeded = true
        }
    }
    LaunchedEffect(textSelectionMode) {
        if (!textSelectionMode) {
            selectableTextLayouts.clear()
            textSelectionSeeded = false
            onTextSelectionBoundsChange(null)
        }
    }

    fun reportPlainTextLayoutIfReady() {
        if (!textSelectionMode) return
        val layoutResult = plainTextLayoutTracker.layoutResult ?: return
        val coordinates = plainTextLayoutTracker.coordinates ?: return
        selectableTextLayouts[plainTextLayoutKey] =
            SelectableTextLayout(plainTextLayoutKey, layoutResult, coordinates)
    }

    val plainTextSelectionModifier =
        if (textSelectionMode) {
            Modifier.onGloballyPositioned { coordinates ->
                plainTextLayoutTracker.coordinates = coordinates
                reportPlainTextLayoutIfReady()
            }
        } else {
            Modifier
        }
    val textSelectionBoundsModifier =
        if (textSelectionMode) {
            Modifier.onGloballyPositioned { onTextSelectionBoundsChange(it.boundsInWindow()) }
        } else {
            Modifier
        }
    val deletedBodyText = stringResource(R.string.message_deleted)
    val invalidatedBodyText = stringResource(R.string.message_invalidated)
    val messageActionsLabel = stringResource(R.string.message_actions)
    val invalidationWarning =
        remember(item.projected, messageTextCopy, deleted) {
            if (deleted) {
                null
            } else {
                item.projected?.let { TimelineProjector.invalidationWarning(it, messageTextCopy) }
            }
        }
    // Cached like the media references below: displayBody sanitizes/allocates
    // per call, and recomputing it for every visible bubble on every timeline
    // recomposition adds up. See #131.
    // Kind-1009 edits replace the body of an existing kind-9 chat. When an
    // edit is present for this message's id, prefer the latest edited text
    // over the original projection. Keyed on editState so a fresh edit
    // recomposes the bubble in place.
    val editState = controller.editsByTarget[record.messageIdHex]
    val displayedBody =
        remember(item, deleted, messageTextCopy, deletedBodyText, invalidatedBodyText, editState) {
            when {
                // Check `deleted` first so the optimistic tombstone (from
                // controller.deletedMessageIds) renders immediately on tap.
                deleted -> deletedBodyText
                persistedFailure -> invalidatedBodyText
                // Edit overlay wins over both projected and raw plaintext.
                // We don't go through MessageProjector here — the edit
                // payload is plain text by spec; markdown re-parse will
                // happen below if record.contentTokens is populated, but
                // for kind-9 edits the body is the latest version verbatim.
                editState != null && record.kind == 9uL -> editState.latestText
                item.projected != null ->
                    TimelineProjector.displayBody(
                        item.projected,
                        messageTextCopy.copy(
                            deleted = deletedBodyText,
                            invalidated = invalidatedBodyText,
                        ),
                    )
                else -> MessageProjector.displayBody(record, messageTextCopy)
            }
        }
    // Issue #390 v1 forwards text only. Forward must be hidden for any record
    // whose displayed body is a synthetic surrogate (media filename/placeholder,
    // "Reacted …", delete/system summaries, agent-stream copy) — forwarding
    // `displayedBody` there would send misleading text into other groups. The
    // raw text to forward is the edit-aware verbatim body, never the display
    // fallback; `forwardBody` is null exactly when the message is not a
    // forwardable text record, which also drives the menu gate below.
    val forwardBody: String? =
        remember(record, editState, deleted, invalidated) {
            if (deleted || invalidated) {
                null
            } else {
                MessageProjector.forwardableText(
                    record,
                    editedText = editState?.latestText?.takeIf { record.kind == 9uL },
                )
            }
        }
    val reserveSenderAvatarSlot =
        GroupProjector.shouldShowTranscriptSenderAvatar(
            isDm = controller.isDm,
            mine = mine,
        )
    // Match the timestamp to the bubble's visual cue. AMOLED uses the same
    // directional accent as the border; other themes keep their paired M3
    // on-color tokens.
    val timestampColor = messageBubbleTimestampColor(mine, deleted, persistedFailure)
    var emojiPickerOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    // A long body clips to a few lines with an inline Read More; opening it
    // routes through a full-screen view rather than expanding in place, so the
    // only state to track is whether that view is showing. Resets on re-entry
    // by keying on the message id (#325).
    var expandedFullView by remember(record.messageIdHex) { mutableStateOf(false) }
    var infoSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var forwardSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var editHistoryOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var reactionSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var customizeReactionsOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var restoreReactionPickerExpanded by remember(record.messageIdHex) { mutableStateOf(false) }
    var deleteDialogOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var deleteForEveryoneInFlight by remember(record.messageIdHex) { mutableStateOf(false) }
    var attachmentSaveInFlight by remember(record.messageIdHex) { mutableStateOf(false) }
    // A deleted message is inert: tear down any open action/reaction surface if
    // the message is deleted out from under it (optimistic or remote delete).
    LaunchedEffect(deleted) {
        if (deleted) {
            onActionMenuOpenChange(false)
            onTextSelectionModeChange(false)
            emojiPickerOpen = false
            reactionSheetOpen = false
            deleteDialogOpen = false
        }
    }
    LaunchedEffect(deleteCapability.canDeleteAtAll) {
        if (!deleteCapability.canDeleteAtAll) deleteDialogOpen = false
    }
    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            onActionMenuOpenChange(false)
            onTextSelectionModeChange(false)
            forwardSheetOpen = false
            infoSheetOpen = false
            deleteDialogOpen = false
        }
    }

    fun beginReply() {
        if (readOnly) return
        controller.replyingTo = record
        onActionMenuOpenChange(false)
    }

    fun openInfoSheet() {
        onActionMenuOpenChange(false)
        infoSheetOpen = true
    }

    fun requestDelete() {
        if (!deleteCapability.canDeleteAtAll) return
        onActionMenuOpenChange(false)
        deleteDialogOpen = true
    }

    fun performDeleteForMe() {
        deleteDialogOpen = false
        controller.hideMessageForMe(record.messageIdHex)
    }

    fun performDeleteForEveryone() {
        // The in-flight flag is the repeated-tap guard on top of the
        // controller's own idempotency (a second deleteMessage on an already
        // tombstoned id is a no-op).
        if (deleteForEveryoneInFlight) return
        deleteForEveryoneInFlight = true
        // launchMutation so the MLS commit + Nostr publish survive navigating
        // away from the conversation. The dialog stays open with its options
        // disabled until the outcome is known, and stays open on failure so
        // the error toast never explains a surface that silently vanished;
        // the optimistic tombstone rollback restores the bubble either way.
        appState.launchMutation {
            try {
                val removed = controller.deleteMessage(record)
                if (removed) deleteDialogOpen = false
            } finally {
                // Cancellation must not leave the flag stuck true, which would
                // disable delete-for-everyone for this bubble's remember scope.
                deleteForEveryoneInFlight = false
            }
        }
    }

    fun reactWithEmoji(emoji: String) {
        // Chokepoint guard: never react to a deleted message, whatever path
        // (menu, emoji picker) called in — even if that surface was open when
        // the delete landed.
        if (deleted || readOnly) return
        // Route via launchMutation: same survives-navigation rationale as delete/send.
        appState.launchMutation { controller.toggleReaction(emoji, record) }
    }

    fun copyMessageText() {
        clipboard.setText(AnnotatedString(displayedBody))
        onActionMenuOpenChange(false)
    }

    suspend fun ttsEntry(entryRecord: AppMessageRecordFfi) =
        projectTtsSpeakableEntry(
            message = entryRecord,
            editedText = controller.editsByTarget[entryRecord.messageIdHex]?.latestText,
            senderDisplayName = appState.displayName(entryRecord.sender),
            parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
            mentionDisplayName = appState::mentionDisplayName,
            isGroupMember =
                if (controller.membersLoaded) {
                    { bech32 -> appState.isRosterMember(bech32, controller.members) }
                } else {
                    null
                },
        )

    // Speak aloud reads from this message onward: catch-up listening is the
    // point of the action, and Stop on the transport bar is one tap. The
    // session takes auto-read ownership so messages arriving while it speaks
    // continue the read. Falls back to just this bubble when its record has
    // left the loaded timeline.
    fun speakFromHere() {
        appState.launchMutation {
            val entries =
                ttsSpeakFromHereCandidates(
                    timeline = controller.timeline,
                    selected = record,
                ).mapNotNull { entryRecord -> ttsEntry(entryRecord) }
            if (entries.isNotEmpty()) {
                appState.speakAloudAutoRead(
                    controller.group.groupIdHex,
                    entries,
                    java.util.Locale.getDefault(),
                )
            } else {
                appState.present(R.string.tts_bar_error)
            }
        }
    }

    fun copyMarkdownLink(url: String) {
        clipboard.setText(AnnotatedString(url))
        onActionMenuOpenChange(false)
    }

    fun beginTextSelection() {
        selectableTextLayouts.clear()
        textSelectionSeeded = false
        onActionMenuOpenChange(false)
        onTextSelectionModeChange(true)
    }

    fun beginForward() {
        // Defensive: the menu only renders Forward when forwardBody != null, but
        // gate here too so a stale tap can never open the picker for a non-text
        // record (issue #390 is text-only).
        if (forwardBody == null) return
        onActionMenuOpenChange(false)
        forwardSheetOpen = true
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val selectionGutterWidth = if (selectionMode) messageBubbleSelectionGutterWidth else 0.dp
        val senderAvatarSlotWidth = if (reserveSenderAvatarSlot) MessageBubbleSenderAvatarSlotWidth else 0.dp
        val bubbleColumnMaxWidth =
            messageBubbleColumnMaxWidth(
                containerWidth = maxWidth,
                selectionGutterWidth = selectionGutterWidth,
                senderAvatarSlotWidth = senderAvatarSlotWidth,
            )
        val longPressBlockedBySelection = selectionMode && !rangeDragActive
        val replySwipeUnavailable = deleted || readOnly || textSelectionMode

        Row(
            // Both reply-swipe and long-press hitboxes cover the ENTIRE row,
            // not just the bubble: a swipe-right or long-press starting on the
            // surrounding whitespace (avatar gutter, empty space next to the
            // bubble) triggers the same action as one starting on the bubble.
            // See #204. The visual slide stays on the Surface below via
            // `.offset`; only gesture detection lives on the row. Nested
            // handlers (avatar, sender name, reaction chips) are children and
            // still win for their own SHORT taps. Long-press is detected with a
            // raw pointerInput (below) rather than combinedClickable so it wins
            // over inner media `clickable` children for the long-press while
            // leaving their single-tap behavior intact (#262). The detector
            // raises no ripple, matching the previous full-row behavior.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .messageBubbleSelectionRow(
                        selectionMode = selectionMode,
                        selected = selected,
                    ).then(
                        // A deleted or selection-mode message has no actionable
                        // reply gesture; taps are owned by the selection row. Keep
                        // the originating row's detector mounted while its range
                        // drag is active so recomposition cannot break ownership
                        // of the pointer that is already down.
                        if (replySwipeUnavailable || longPressBlockedBySelection) {
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex, replySwipeThresholdPx, maxSwipeOffsetPx) {
                                var gesture = ReplySwipeGesture()
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        gesture = ReplySwipeGesture()
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        gesture =
                                            gesture.dragBy(
                                                deltaX = dragAmount,
                                                deltaY = change.position.y - change.previousPosition.y,
                                            )
                                        val next = gesture.visualOffset(maxSwipeOffsetPx)
                                        if (next != swipeDrag || dragAmount > 0f) change.consume()
                                        swipeDrag = next
                                    },
                                    onDragEnd = {
                                        if (gesture.shouldTriggerReply(threshold = replySwipeThresholdPx)) {
                                            beginReply()
                                        }
                                        gesture = ReplySwipeGesture()
                                        swipeDrag = 0f
                                    },
                                    onDragCancel = {
                                        gesture = ReplySwipeGesture()
                                        swipeDrag = 0f
                                    },
                                )
                            }
                        },
                    ).then(
                        // Long-press lives in a raw pointerInput, not
                        // combinedClickable, so it WINS over inner media
                        // children (image/video/file/voice) that install their
                        // own tap `clickable`. Those children sit deeper in the
                        // hit-test tree and would otherwise swallow the press
                        // before a row-level combinedClickable saw the
                        // long-press — which is why long-press did nothing on a
                        // media bubble while it worked on a text bubble (#262).
                        // A quick tap still reaches the child's viewer/player.
                        // Once held, release opens actions while a vertical drag
                        // switches to anchored batch selection. Horizontal motion
                        // remains available to swipe-to-reply above.
                        if (deleted || longPressBlockedBySelection || textSelectionMode) {
                            // A deleted message has no actions menu; batch selection
                            // and text selection route the row through their own UI.
                            Modifier
                        } else {
                            Modifier.longPressOrVerticalDrag(
                                onLongPressStart = {
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                },
                                onLongPressRelease = { position ->
                                    val windowPosition =
                                        rowCoordinates[0]?.let {
                                            messageBubbleLongPressPositionInWindow(it, position)
                                        } ?: return@longPressOrVerticalDrag
                                    val linkDestination =
                                        markdownLinkDestinationAt(markdownLinkLayouts.values, windowPosition)
                                    if (linkDestination != null) {
                                        copyMarkdownLink(linkDestination)
                                    } else {
                                        // Capture the press in window space before
                                        // opening so both the popover and text
                                        // selection seed at the finger (#326, #1370).
                                        longPressWindowPosition = windowPosition
                                        longPressWindowY = windowPosition.y
                                        actionMenuAnchorBounds = messageBoundsInWindow[0]
                                        onActionMenuOpenChange(true)
                                    }
                                },
                                onDragStart = { position ->
                                    rowCoordinates[0]
                                        ?.let { messageBubbleLongPressPositionInWindow(it, position).y }
                                        ?.let(onDragSelectionStart)
                                },
                                onDrag = { position ->
                                    rowCoordinates[0]
                                        ?.let { messageBubbleLongPressPositionInWindow(it, position).y }
                                        ?.let(onDragSelection)
                                        ?: false
                                },
                                onDragEnd = onDragSelectionEnd,
                                onGestureCancel = onDragSelectionCancel,
                            )
                        },
                    ).then(
                        // The raw pointerInput above only fires on a physical
                        // pointer long-press, so it leaves accessibility services
                        // (TalkBack, Switch Access) and keyboard/semantic callers
                        // without a way to reach the actions menu — a regression
                        // from the old combinedClickable, which exposed an
                        // onLongClick semantic action for the whole row (#262).
                        // Re-publish that action via Modifier.semantics so the
                        // reply/copy/delete/reaction entry point stays reachable
                        // without a hold gesture. Guarded by `!deleted` and
                        // disabled while batch/text selection owns the row.
                        if (deleted || selectionMode || textSelectionMode) {
                            Modifier
                        } else {
                            Modifier.semantics {
                                onLongClick(label = messageActionsLabel) {
                                    // Accessibility entry has no touch point;
                                    // anchor to the bubble top and seed the first word.
                                    longPressWindowPosition = null
                                    longPressWindowY = null
                                    actionMenuAnchorBounds = messageBoundsInWindow[0]
                                    onActionMenuOpenChange(true)
                                    true
                                }
                            }
                        },
                    )
                    // Keep the row transform itself: clipped bounds are not the
                    // row's local origin when a message is partially off-screen.
                    .onGloballyPositioned { rowCoordinates[0] = it },
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                MessageBubbleSelectionGutter(
                    batchSelectable = batchSelectable,
                    selected = selected,
                )
                // Arrangement.End would otherwise move the leading gutter next
                // to an outgoing bubble. Consume the middle space so the gutter
                // stays at the row's leading edge for both message directions.
                if (mine) Spacer(Modifier.weight(1f))
            }
            if (reserveSenderAvatarSlot) {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .align(Alignment.Bottom),
                ) {
                    if (showSenderAvatar) {
                        Box(
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable(enabled = !textSelectionMode) {
                                        appState.presentProfile(appState.npub(record.sender))
                                    },
                        ) {
                            Avatar(
                                title = appState.displayName(record.sender),
                                seed = record.sender,
                                size = 32.dp,
                                pictureUrl = appState.avatarUrl(record.sender),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier =
                    Modifier
                        .widthIn(max = bubbleColumnMaxWidth)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            messageBoundsInWindow[0] =
                                IntRect(
                                    left = bounds.left.roundToInt(),
                                    top = bounds.top.roundToInt(),
                                    right = bounds.right.roundToInt(),
                                    bottom = bounds.bottom.roundToInt(),
                                )
                        },
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                // Resolved before the content column so its presence can pick
                // the column's width strategy (#428).
                //
                // Projected items: the preview is a pure function of
                // item.projected, so caching keyed on the item is always
                // correct (a reprojection replaces the instance). The
                // optimistic fallback instead resolves the target from
                // controller.messageById, which can gain the target after
                // this bubble composes — resolve those live. Display names
                // resolve outside the cache either way so a late profile
                // load still updates them. See #131.
                val replyPreview =
                    if (item.projected != null) {
                        remember(item, messageTextCopy) {
                            controller.replyPreview(item, messageTextCopy)
                        }
                    } else {
                        controller.replyPreview(item, messageTextCopy)
                    }
                val mediaReferences =
                    rememberMessageMediaReferences(
                        tags = record.tags,
                        messageIdHex = record.messageIdHex,
                        sourceEpoch = record.sourceEpoch,
                        projectedMedia = item.projected?.media,
                    )
                val pendingAttachmentsForRecord = controller.pendingAttachmentsList(record.messageIdHex)
                val bubbleMedia = rememberBubbleMedia(mediaReferences, pendingAttachmentsForRecord)
                val imageAttachments = bubbleMedia.images
                val videoAttachments = bubbleMedia.videos
                val fileAttachments = bubbleMedia.files
                val visualAttachments = bubbleMedia.visuals
                val pendingAudio = bubbleMedia.pendingAudio
                val pendingVisualRefs = bubbleMedia.pendingVisuals
                val anyConfirmedMedia = bubbleMedia.hasConfirmedMedia

                fun saveAttachments() {
                    if (mediaReferences.isEmpty() || attachmentSaveInFlight) return
                    onActionMenuOpenChange(false)
                    attachmentSaveInFlight = true
                    appState.launchMutation {
                        try {
                            var savedCount = 0
                            mediaReferences.forEachIndexed { attachmentIndex, reference ->
                                val saved =
                                    runCatching {
                                        if (MediaReferenceSupport.isVideoMedia(reference)) {
                                            val file =
                                                materializeVideoAttachment(
                                                    context = context,
                                                    controller = controller,
                                                    messageIdHex = record.messageIdHex,
                                                    attachmentIndex = attachmentIndex,
                                                    reference = reference,
                                                    mine = mine,
                                                )
                                            withContext(Dispatchers.IO) {
                                                saveVideoToGallery(
                                                    context = context,
                                                    source = file,
                                                    fileName = reference.fileName,
                                                    mediaType = reference.mediaType,
                                                )
                                            }
                                        } else {
                                            val bytes =
                                                attachmentBytes(
                                                    controller = controller,
                                                    messageIdHex = record.messageIdHex,
                                                    attachmentIndex = attachmentIndex,
                                                    reference = reference,
                                                    mine = mine,
                                                )
                                            withContext(Dispatchers.IO) {
                                                saveAttachmentToMediaStore(
                                                    context = context,
                                                    bytes = bytes,
                                                    fileName = reference.fileName,
                                                    mediaType = reference.mediaType,
                                                )
                                            }
                                        }
                                    }.onFailure {
                                        if (it is kotlinx.coroutines.CancellationException) throw it
                                    }.getOrDefault(false)
                                if (saved) savedCount += 1
                            }
                            appState.presentAttachmentSaveOutcome(context, savedCount, mediaReferences.size)
                        } finally {
                            attachmentSaveInFlight = false
                        }
                    }
                }
                val mediaPendingName =
                    remember(record.tags) {
                        record.tags
                            .firstOrNull { it.values.firstOrNull() == "_media_pending" }
                            ?.values
                            ?.getOrNull(1)
                    }
                val mediaCaption =
                    MessageProjector.mediaCaption(
                        message = record,
                        body = editState?.latestText ?: record.plaintext,
                    )
                // An uncaptioned single image/video carries the footer
                // overlaid on its bottom-right; a caption (if any) takes
                // it instead via the text path below.
                val footerOnVisualMedia =
                    !deleted &&
                        invalidationWarning == null &&
                        visualAttachments.size == 1 &&
                        mediaCaption == null
                // Share-message recognition (app-side rich rendering). A contact
                // ships as a text/vcard attachment with a name/phone caption, so
                // its card draws from the caption without fetching the blob; a
                // location ships as a plain maps-link text with no attachment.
                // Both stay readable on any other client (a .vcf file / a link).
                val vcardAttachment =
                    remember(fileAttachments) {
                        fileAttachments.firstOrNull { (_, ref) ->
                            ref.mediaType.equals(VCARD_MIME_TYPE, ignoreCase = true) ||
                                ref.fileName.endsWith(".vcf", ignoreCase = true)
                        }
                    }
                val shareBodyText = editState?.latestText ?: record.plaintext
                val canRenderSharedContent = !deleted && !persistedFailure
                val canRenderStructuredShare =
                    canRenderSharedContent &&
                        !anyConfirmedMedia &&
                        record.kind == 9uL
                val sharedContact =
                    remember(vcardAttachment, shareBodyText, canRenderSharedContent) {
                        if (vcardAttachment != null && canRenderSharedContent) {
                            parseSharedContactFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val sharedLocation =
                    remember(shareBodyText, canRenderStructuredShare) {
                        if (canRenderStructuredShare) {
                            parseSharedLocationFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val sharedUser =
                    remember(shareBodyText, canRenderStructuredShare) {
                        if (canRenderStructuredShare) {
                            parseSharedUserFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val footerOnPendingVisual =
                    !deleted &&
                        invalidationWarning == null &&
                        !anyConfirmedMedia &&
                        pendingVisualRefs.size == 1 &&
                        mediaCaption == null
                val showPendingPlaceholder =
                    !deleted &&
                        !anyConfirmedMedia &&
                        pendingAudio.isEmpty() &&
                        pendingVisualRefs.isEmpty() &&
                        mediaPendingName != null
                // `hasMedia` decides whether this row renders a media card with
                // an optional integrated caption, or stays a text-only bubble.
                // Deleted and persisted-failure tombstones stay text bubbles;
                // convergence-invalidated messages retain local media.
                val hasMedia =
                    !deleted &&
                        !persistedFailure &&
                        (
                            anyConfirmedMedia ||
                                pendingAudio.isNotEmpty() ||
                                pendingVisualRefs.isNotEmpty() ||
                                showPendingPlaceholder ||
                                sharedLocation != null ||
                                sharedUser != null
                        )
                // Detached media keeps its own rounded Surface. When a caption
                // is attached, each child delegates its corners and border to
                // the shared frame so the result is one continuous message card.
                // Download gating, footer overlays, viewers, and retry behavior
                // remain owned by the media children.
                // Long-press on any media tile opens the action menu (not the
                // viewer); anchored to the bubble top like the accessibility
                // long-click path. Hoisted so every media call site shares one
                // definition.
                val onMediaLongPress =
                    remember(textSelectionMode, selectionMode, onActionMenuOpenChange) {
                        {
                            if (!selectionMode && !textSelectionMode) {
                                longPressWindowPosition = null
                                longPressWindowY = null
                                actionMenuAnchorBounds = messageBoundsInWindow[0]
                                onActionMenuOpenChange(true)
                            }
                        }
                    }
                // Body text policy:
                // - Pending and confirmed media render the user-authored
                //   caption throughout the upload/reconciliation lifecycle.
                //   Synthetic pending placeholders and confirmed filename
                //   fallbacks stay hidden because the attachment is already
                //   visible in the same bubble.
                // - Non-media: render displayedBody (covers reactions,
                //   deletions, agent streams, plain text).
                val bodyTextToRender: String? =
                    when {
                        // Deleted and persisted failure tombstones show only
                        // their copy, never an inline image/caption.
                        deleted || persistedFailure -> displayedBody
                        // The contact card / location bubble / user card carry
                        // the body, so the raw caption/link/npub text is hidden.
                        sharedContact != null || sharedLocation != null || sharedUser != null -> null
                        mediaPendingName != null && !anyConfirmedMedia -> mediaCaption
                        anyConfirmedMedia -> mediaCaption
                        else -> displayedBody
                    }
                val bodyOrWarningInsideBubble =
                    shouldFrameMessageBubbleSupplement(bodyTextToRender, invalidationWarning)
                // Captions/plain bodies sit on the resolved bubble background and therefore use
                // its paired WCAG-safe content color. Footer-only media rows are
                // outside the bubble and retain the page's surface foreground.
                val timestampColor =
                    if (bodyOrWarningInsideBubble) bubbleContentColor else colorScheme.onSurfaceVariant
                LaunchedEffect(textSelectionMode, bodyTextToRender) {
                    if (textSelectionMode && bodyTextToRender.isNullOrBlank()) {
                        onTextSelectionModeChange(false)
                    }
                }
                val editedLabel =
                    if (deleted || persistedFailure) {
                        null
                    } else if (editState != null && record.kind == 9uL) {
                        if (editState.count > 1) {
                            stringResource(R.string.edited_count, editState.count)
                        } else {
                            stringResource(R.string.edited)
                        }
                    } else {
                        null
                    }
                // A long body collapses to MESSAGE_COLLAPSE_LINE_LIMIT lines
                // with Read More in the bottom footer row opening the full-screen view;
                // tombstones, edit/info copy, and groups with the local collapse
                // setting disabled never collapse (#325, #1180).
                val collapsible =
                    collapseLongMessages && !deleted && !persistedFailure && !textSelectionMode
                // The sender-name label (group chats only). Rendered above the
                // shared media card when media is present, or as the first child
                // of the text-only bubble otherwise.
                val senderNameLabel: @Composable (insideBubble: Boolean) -> Unit = { insideBubble ->
                    if (showSenderName) {
                        Text(
                            appState.displayName(record.sender),
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (insideBubble && customBubbleColorActive) {
                                    bubbleContentColor
                                } else {
                                    colorScheme.onSurfaceVariant
                                },
                            modifier =
                                Modifier.combinedClickable(
                                    enabled = !textSelectionMode,
                                    onClick = { appState.presentProfile(appState.npub(record.sender)) },
                                    onLongClick = {
                                        if (!deleted && !selectionMode && !textSelectionMode) {
                                            longPressWindowPosition = null
                                            longPressWindowY = null
                                            actionMenuAnchorBounds = messageBoundsInWindow[0]
                                            onActionMenuOpenChange(true)
                                        }
                                    },
                                ),
                        )
                    }
                }
                // The reply quote card. Self-contained (own translucent Surface),
                // so it renders correctly whether inside the text bubble or
                // standalone above the media card.
                val replyPreviewCard: @Composable (insideBubble: Boolean) -> Unit = { insideBubble ->
                    replyPreview?.let { preview ->
                        val useCustomFillColors = insideBubble && customBubbleColorActive
                        val replyAccentArgb =
                            replyPreviewAccentArgb(
                                insideBubble = insideBubble,
                                customBubbleColorActive = customBubbleColorActive,
                                presentation = bubblePresentation,
                            )
                        ReplyPreviewCard(
                            senderTitle = senderTitleForReply(preview.sender, appState),
                            isOwn = isOwnReplySender(preview.sender, appState),
                            body = preview.body,
                            mediaKind = preview.mediaKind,
                            warning = preview.warning,
                            onClick = {
                                if (!textSelectionMode) onReplyPreviewClick(item)
                            },
                            onDismiss = null,
                            // Fill the content width: in the text bubble the
                            // column is sized to its widest child (IntrinsicSize.Max
                            // below) so the quote matches the bubble instead of
                            // hugging its own text; above a media card it lines up
                            // with the media width. A short quote + short reply
                            // still keeps a narrow bubble because the widest child
                            // is then small.
                            fillWidth = true,
                            mentionDisplayName =
                                remember(appState, appState.profileRevisionForCompose) {
                                    { bech32: String -> appState.mentionDisplayName(bech32) }
                                },
                            containerColor = if (useCustomFillColors) Color.Transparent else null,
                            contentColor = if (useCustomFillColors) bubbleContentColor else null,
                            accentColor = replyAccentArgb?.let(::colorFromArgb),
                        )
                    }
                }
                val onPlainTextLayout: (TextLayoutResult) -> Unit = {
                    plainTextLayoutTracker.layoutResult = it
                    reportPlainTextLayoutIfReady()
                }
                val selectionWrapper: @Composable (@Composable () -> Unit) -> Unit = { content ->
                    if (textSelectionMode) {
                        CompositionLocalProvider(LocalClipboard provides textSelectionClipboard) {
                            SelectionContainer(state = messageTextSelectionState) { content() }
                        }
                    } else {
                        content()
                    }
                }
                val onEditedClick: (() -> Unit)? =
                    if (editState != null && !textSelectionMode) {
                        { editHistoryOpen = true }
                    } else {
                        null
                    }
                if (hasMedia) {
                    // Signal and Telegram treat media plus caption as one message
                    // surface. The media owns no internal corners or border when
                    // a caption is present; the shared frame owns the continuous
                    // outer shape, color, border, and single footer.
                    Column(
                        modifier = Modifier.offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) },
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        senderNameLabel(false)
                        replyPreviewCard(false)
                        if (bodyOrWarningInsideBubble) {
                            MediaCaptionFrame(
                                presentation = bubblePresentation,
                                highlighted = highlighted,
                                mine = mine,
                                mentionedSelf = mentionedSelf,
                                mentionedYouLabel = mentionedYouLabel,
                                alignEnd = mine,
                                contentModifier = textSelectionBoundsModifier,
                                media = {
                                    BubbleMediaBlocks(
                                        item = item,
                                        record = record,
                                        controller = controller,
                                        appState = appState,
                                        bubbleMedia = bubbleMedia,
                                        sharedLocation = sharedLocation,
                                        sharedContact = sharedContact,
                                        sharedUser = sharedUser,
                                        deleted = deleted,
                                        mine = mine,
                                        footerOnVisualMedia = footerOnVisualMedia,
                                        footerOnPendingVisual = footerOnPendingVisual,
                                        showPendingPlaceholder = showPendingPlaceholder,
                                        onMediaLongPress = onMediaLongPress,
                                        attachedToCaption = true,
                                    )
                                },
                            ) {
                                BubbleBodyFooterAndRetry(
                                    item = item,
                                    record = record,
                                    controller = controller,
                                    appState = appState,
                                    bodyText = bodyTextToRender,
                                    deleted = deleted,
                                    persistedFailure = persistedFailure,
                                    textSelectionMode = textSelectionMode,
                                    customBubbleColorActive = customBubbleColorActive,
                                    selectableTextLayoutReporter = selectableTextLayoutReporter,
                                    markdownLinkLayoutReporter = markdownLinkLayoutReporter,
                                    onCopyMarkdownLink = ::copyMarkdownLink,
                                    plainTextSelectionModifier = plainTextSelectionModifier,
                                    onPlainTextLayout = onPlainTextLayout,
                                    selectionWrapper = selectionWrapper,
                                    collapsible = collapsible,
                                    replyPreviewPresent = replyPreview != null,
                                    hasMedia = hasMedia,
                                    bubbleContentColor = bubbleContentColor,
                                    timestampColor = timestampColor,
                                    showStatus = shouldShowMessageStatus(mine, deleted, invalidationPresentation),
                                    showRetention = !deleted && retentionIndicatorVisible(record.retentionSeconds),
                                    editedLabel = editedLabel,
                                    onEditedClick = onEditedClick,
                                    footerOnVisualMedia = footerOnVisualMedia,
                                    footerOnPendingVisual = footerOnPendingVisual,
                                    invalidationWarning = invalidationWarning,
                                    mine = mine,
                                    onExpand = { expandedFullView = true },
                                )
                            }
                        } else {
                            MediaSupplementEnvelope(
                                alignEnd = mine,
                                media = {
                                    BubbleMediaBlocks(
                                        item = item,
                                        record = record,
                                        controller = controller,
                                        appState = appState,
                                        bubbleMedia = bubbleMedia,
                                        sharedLocation = sharedLocation,
                                        sharedContact = sharedContact,
                                        sharedUser = sharedUser,
                                        deleted = deleted,
                                        mine = mine,
                                        footerOnVisualMedia = footerOnVisualMedia,
                                        footerOnPendingVisual = footerOnPendingVisual,
                                        showPendingPlaceholder = showPendingPlaceholder,
                                        onMediaLongPress = onMediaLongPress,
                                        attachedToCaption = false,
                                    )
                                },
                            ) {
                                // No caption: the footer (time/status) for audio,
                                // file, or multi-visual media still needs a home —
                                // and so does the failed-send retry row.
                                BubbleBodyFooterAndRetry(
                                    item = item,
                                    record = record,
                                    controller = controller,
                                    appState = appState,
                                    bodyText = bodyTextToRender,
                                    deleted = deleted,
                                    persistedFailure = persistedFailure,
                                    textSelectionMode = textSelectionMode,
                                    customBubbleColorActive = customBubbleColorActive,
                                    selectableTextLayoutReporter = selectableTextLayoutReporter,
                                    markdownLinkLayoutReporter = markdownLinkLayoutReporter,
                                    onCopyMarkdownLink = ::copyMarkdownLink,
                                    plainTextSelectionModifier = plainTextSelectionModifier,
                                    onPlainTextLayout = onPlainTextLayout,
                                    selectionWrapper = selectionWrapper,
                                    collapsible = collapsible,
                                    replyPreviewPresent = replyPreview != null,
                                    hasMedia = hasMedia,
                                    bubbleContentColor = bubbleContentColor,
                                    timestampColor = timestampColor,
                                    showStatus = shouldShowMessageStatus(mine, deleted, invalidationPresentation),
                                    showRetention = !deleted && retentionIndicatorVisible(record.retentionSeconds),
                                    editedLabel = editedLabel,
                                    onEditedClick = onEditedClick,
                                    footerOnVisualMedia = footerOnVisualMedia,
                                    footerOnPendingVisual = footerOnPendingVisual,
                                    invalidationWarning = invalidationWarning,
                                    mine = mine,
                                    onExpand = { expandedFullView = true },
                                )
                            }
                        }
                    }
                } else {
                    MessageBubbleFrame(
                        // Swipe-to-reply and long-press now live on the parent Row
                        // (see #204) so the whole message row is the hitbox. The
                        // Surface keeps only the visual slide driven by swipeDrag.
                        modifier =
                            Modifier
                                .offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) }
                                .then(textSelectionBoundsModifier),
                        presentation = bubblePresentation,
                        highlighted = highlighted,
                        mine = mine,
                        mentionedSelf = mentionedSelf,
                        mentionedYouLabel = mentionedYouLabel,
                        // With a reply quote present, size the column to its
                        // widest child so the inner quote can fill the bubble
                        // width instead of hugging its own (possibly short)
                        // text and leaving a gap on the right (#428). Non-reply
                        // bubbles keep the wrap-content path untouched, so only
                        // reply-bubble measurement changes.
                        contentModifier = if (replyPreview != null) Modifier.width(IntrinsicSize.Max) else Modifier,
                    ) {
                        senderNameLabel(true)
                        replyPreviewCard(true)
                        BubbleBodyFooterAndRetry(
                            item = item,
                            record = record,
                            controller = controller,
                            appState = appState,
                            bodyText = bodyTextToRender,
                            deleted = deleted,
                            persistedFailure = persistedFailure,
                            textSelectionMode = textSelectionMode,
                            customBubbleColorActive = customBubbleColorActive,
                            selectableTextLayoutReporter = selectableTextLayoutReporter,
                            markdownLinkLayoutReporter = markdownLinkLayoutReporter,
                            onCopyMarkdownLink = ::copyMarkdownLink,
                            plainTextSelectionModifier = plainTextSelectionModifier,
                            onPlainTextLayout = onPlainTextLayout,
                            selectionWrapper = selectionWrapper,
                            collapsible = collapsible,
                            replyPreviewPresent = replyPreview != null,
                            hasMedia = hasMedia,
                            bubbleContentColor = bubbleContentColor,
                            timestampColor = timestampColor,
                            showStatus = shouldShowMessageStatus(mine, deleted, invalidationPresentation),
                            showRetention = !deleted && retentionIndicatorVisible(record.retentionSeconds),
                            editedLabel = editedLabel,
                            onEditedClick = onEditedClick,
                            footerOnVisualMedia = footerOnVisualMedia,
                            footerOnPendingVisual = footerOnPendingVisual,
                            invalidationWarning = invalidationWarning,
                            mine = mine,
                            onExpand = { expandedFullView = true },
                        )
                    }
                }
                MessageActionMenu(
                    // Never render the menu for a deleted message or while batch
                    // or partial text selection owns the row interaction.
                    expanded = isActionMenuOpen && !deleted && !selectionMode && !textSelectionMode,
                    anchorBoundsInWindow = actionMenuAnchorBounds,
                    anchorWindowYPx = longPressWindowY,
                    alignEnd = mine,
                    canReply = !readOnly,
                    canReact = !readOnly,
                    canDelete = deleteCapability.canDeleteAtAll,
                    canEdit = !readOnly && mine && record.kind == 9uL && record.messageIdHex.isNotBlank() && !deleted,
                    canForward = !readOnly && forwardBody != null,
                    canSelect = !readOnly && batchSelectable,
                    // Whole-message Copy keeps using its actual clipboard payload,
                    // including card-style bubbles whose body is rendered by the
                    // card rather than the text renderer. Partial selection is only
                    // available when this bubble has selectable rendered text.
                    canCopyText = displayedBody.isNotBlank(),
                    // Speak aloud uses the same edit-aware user-authored text as TTS
                    // projection, not the display fallback (filenames, placeholders,
                    // reactions, system copy).
                    canSpeak =
                        messageBubbleCanSpeak(
                            record = record,
                            editedText = editState?.latestText,
                            deleted = deleted,
                            invalidated = invalidated,
                            ttsHasUsableEngine = appState.ttsHasUsableEngine,
                        ),
                    canSelectText = !bodyTextToRender.isNullOrBlank(),
                    canSave = mediaReferences.isNotEmpty() && !attachmentSaveInFlight,
                    quickReactionEmojis = quickReactionEmojis,
                    onDismissRequest = { onActionMenuOpenChange(false) },
                    onReact = { emoji ->
                        onActionMenuOpenChange(false)
                        onEmojiUsed(emoji)
                        reactWithEmoji(emoji)
                    },
                    onOpenEmojiPicker = {
                        onActionMenuOpenChange(false)
                        emojiPickerOpen = true
                    },
                    onReply = ::beginReply,
                    onEdit = {
                        onActionMenuOpenChange(false)
                        // Cancel any reply-in-progress: reply and
                        // edit modes are mutually exclusive in the
                        // composer banner.
                        controller.replyingTo = null
                        controller.editingMessageId = record.messageIdHex
                    },
                    onCopyText = ::copyMessageText,
                    onSpeak = {
                        onActionMenuOpenChange(false)
                        speakFromHere()
                    },
                    onSave = ::saveAttachments,
                    onSelectText = ::beginTextSelection,
                    onForward = ::beginForward,
                    onSelect = {
                        onActionMenuOpenChange(false)
                        onToggleSelection()
                    },
                    onInfo = ::openInfoSheet,
                    onDelete = ::requestDelete,
                )
                if (expandedFullView) {
                    val groupIdHex = controller.group.groupIdHex
                    val editingRecord =
                        controller.editingMessageId?.let { id ->
                            controller.timeline.firstOrNull { it.record.messageIdHex == id }?.record
                        }
                    val canUseExpandedComposer = !readOnly && composerGate == ComposerGate.COMPOSER
                    MessageFullScreenView(
                        senderDisplayName = appState.displayName(record.sender),
                        senderSeed = record.sender,
                        senderAvatarUrl = appState.avatarUrl(record.sender),
                        body = displayedBody,
                        timeText = rememberedClockTime(record.recordedAt),
                        showStatus = shouldShowMessageStatus(mine, deleted, invalidationPresentation),
                        status = item.status,
                        canReply = canUseExpandedComposer,
                        canReact = canUseExpandedComposer,
                        canDelete = deleteCapability.canDeleteAtAll,
                        onReply = {
                            if (canUseExpandedComposer) {
                                beginReply()
                            }
                        },
                        onReact = {
                            if (canUseExpandedComposer) {
                                expandedFullView = false
                                emojiPickerOpen = true
                            }
                        },
                        onCopy = ::copyMessageText,
                        onDelete = {
                            // Close the full view before opening the shared
                            // delete surface (same handoff the reaction picker
                            // uses) so the sheet never renders behind the
                            // full-screen dialog window.
                            expandedFullView = false
                            requestDelete()
                        },
                        onDismiss = { expandedFullView = false },
                        bottomBar = {
                            when (composerGate) {
                                ComposerGate.PENDING ->
                                    Spacer(
                                        Modifier
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .imePadding()
                                            .height(64.dp),
                                    )
                                ComposerGate.NOTICE -> RemovedMemberComposerNotice()
                                ComposerGate.FROZEN -> FrozenGroupComposerNotice()
                                ComposerGate.DISBANDED -> DisbandedGroupComposerNotice(disbanded = groupDisbanded)
                                ComposerGate.INVITE ->
                                    InvitePreviewActionBar(
                                        mutationInFlight = inviteMutationInFlight,
                                        onJoin = onJoinInvite,
                                        onDecline = onDeclineInvite,
                                    )
                                ComposerGate.COMPOSER ->
                                    if (!readOnly) {
                                        ComposerBar(
                                            replyingTo = controller.replyingTo,
                                            replyingToMedia =
                                                controller.replyingTo
                                                    ?.let(controller::mediaReferencesFor)
                                                    .orEmpty(),
                                            replyingToDisplay =
                                                controller.replyingTo
                                                    ?.let { controller.replyTargetPreview(it, messageTextCopy) },
                                            messageTextCopy = messageTextCopy,
                                            onCancelReply = { controller.replyingTo = null },
                                            onSend = { text, onAccepted -> appState.launchMutation { controller.send(text, onAccepted) } },
                                            initialDraft =
                                                appState
                                                    .draftSnapshotFor(groupIdHex)
                                                    ?.textFieldValue
                                                    ?: TextFieldValue(""),
                                            onDraftChange = { appState.setDraft(groupIdHex, it) },
                                            draftKey = groupIdHex,
                                            textState = composerTextState,
                                            editingMessageId = controller.editingMessageId,
                                            editingInitialText = editingRecord?.let { controller.displayedText(it) },
                                            onCancelEdit = { controller.editingMessageId = null },
                                            appState = appState,
                                            mentionCandidates = mentionCandidates,
                                            mentionPickerEnabled = mentionPickerEnabled,
                                            enterKeyBehavior = appState.enterKeyBehavior,
                                            recentEmojis = recentEmojis,
                                            onEmojiUsed = onEmojiUsed,
                                        )
                                    }
                            }
                        },
                    )
                }
                if (emojiPickerOpen && !readOnly) {
                    EmojiPickerSheet(
                        restoreExpanded = restoreReactionPickerExpanded,
                        purpose = EmojiPickerPurpose.USE,
                        recentEmojis = recentEmojis,
                        onEmojiUsed = onEmojiUsed,
                        messageReactionEmojis =
                            item.projected
                                ?.reactions
                                ?.byEmoji
                                .orEmpty()
                                .map { it.emoji },
                        onDismissRequest = {
                            restoreReactionPickerExpanded = false
                            emojiPickerOpen = false
                        },
                        onEmojiPicked = { emoji ->
                            restoreReactionPickerExpanded = false
                            emojiPickerOpen = false
                            reactWithEmoji(emoji)
                        },
                        onCustomizeReactions = { wasExpanded ->
                            restoreReactionPickerExpanded = wasExpanded
                            customizeReactionsOpen = true
                        },
                    )
                }
                if (customizeReactionsOpen) {
                    fun closeCustomizeToReactionSheet() {
                        customizeReactionsOpen = false
                    }
                    CustomizeReactionsDialog(
                        quickReactionEmojis = quickReactionEmojis,
                        recentEmojis = recentEmojis,
                        onDismiss = ::closeCustomizeToReactionSheet,
                        onSave = { choices ->
                            onQuickReactionsSave(choices)
                            closeCustomizeToReactionSheet()
                        },
                        onReset = onQuickReactionsReset,
                    )
                }
                if (editHistoryOpen && editState != null) {
                    EditHistorySheet(
                        original = record.plaintext,
                        originalTimestamp = record.recordedAt,
                        editState = editState,
                        onDismissRequest = { editHistoryOpen = false },
                    )
                }
                if (infoSheetOpen) {
                    MessageInfoSheet(
                        item = item,
                        mine = mine,
                        senderDisplayName = appState.displayName(record.sender),
                        senderNpub = appState.npub(record.sender),
                        onDismissRequest = { infoSheetOpen = false },
                        onCopy = { value ->
                            clipboard.setText(AnnotatedString(value))
                        },
                    )
                }
                if (forwardSheetOpen && forwardBody != null) {
                    ForwardMessageSheet(
                        appState = appState,
                        body = forwardBody,
                        originGroupIdHex = record.groupIdHex,
                        onDismiss = { forwardSheetOpen = false },
                        onForward = { targetGroupIds ->
                            appState.forwardText(targetGroupIds, forwardBody)
                        },
                    )
                }
                if (deleteDialogOpen) {
                    MessageDeleteDialog(
                        capability = deleteCapability,
                        mine = mine,
                        senderDisplayName = appState.displayName(record.sender),
                        deleteInFlight = deleteForEveryoneInFlight,
                        onDeleteForEveryone = ::performDeleteForEveryone,
                        onDeleteForMe = ::performDeleteForMe,
                        onDismissRequest = { deleteDialogOpen = false },
                    )
                }
                val tallies = controller.reactions[record.messageIdHex].orEmpty()
                // Hide reaction tallies on a deleted message — nothing to show.
                if (tallies.isNotEmpty() && !deleted) {
                    val reactionChipPadding =
                        if (mine) {
                            PaddingValues(end = 10.dp)
                        } else {
                            PaddingValues(start = 10.dp)
                        }
                    // Keep the chip tucked onto the bubble's lower edge without
                    // covering the final text line or outgoing status cluster.
                    Box(
                        modifier =
                            Modifier
                                .align(if (mine) Alignment.End else Alignment.Start)
                                .padding(reactionChipPadding)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val overlap = 6.dp.roundToPx()
                                    val height = (placeable.height - overlap).coerceAtLeast(0)
                                    layout(placeable.width, height) {
                                        placeable.place(0, -overlap)
                                    }
                                },
                    ) {
                        ReactionSummaryChip(
                            tallies = tallies,
                            onClick = { reactionSheetOpen = true },
                        )
                    }
                }
                if (reactionSheetOpen) {
                    val participants =
                        remember(record.messageIdHex, item.projected?.reactions, tallies) {
                            controller.reactionParticipantsFor(record.messageIdHex)
                        }
                    // Close when the participant list drains, without re-firing for every list update.
                    LaunchedEffect(participants.isEmpty()) {
                        if (participants.isEmpty()) reactionSheetOpen = false
                    }
                    if (participants.isNotEmpty()) {
                        ReactionDetailsSheet(
                            participants = participants,
                            appState = appState,
                            onRemoveOwnReaction =
                                if (readOnly) {
                                    null
                                } else {
                                    { emoji -> appState.launchMutation { controller.toggleReaction(emoji, record) } }
                                },
                            onDismissRequest = { reactionSheetOpen = false },
                        )
                    }
                }
            }
        }
        if (selectionMode) {
            // Keep the visual tint and indicator in layout, but retain the
            // full-row input layer so nested avatar/media clickables cannot
            // bypass selection mode.
            MessageBubbleSelectionTapTarget(
                selected = selected,
                batchSelectable = batchSelectable,
                contentDescription =
                    messageBubbleSelectionContentDescription(
                        senderDisplayName = appState.displayName(record.sender),
                        messageSummary = displayedBody,
                    ),
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

// A body longer than this many rendered lines collapses to a Read More that
// opens the full-screen view rather than spilling down the transcript (#325).
internal const val MESSAGE_COLLAPSE_LINE_LIMIT = 52

private val MessageBubbleOppositeGutter = 48.dp
private val MessageBubbleSenderAvatarSlotWidth = 40.dp

internal fun messageBubbleColumnMaxWidth(
    containerWidth: Dp,
    selectionGutterWidth: Dp,
    senderAvatarSlotWidth: Dp,
): Dp =
    (containerWidth - MessageBubbleOppositeGutter - selectionGutterWidth - senderAvatarSlotWidth)
        .coerceAtLeast(0.dp)
