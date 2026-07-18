package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReplySwipe
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.MarkdownLinkTextLayout
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.common.rememberedClockTime
import dev.ipf.whitenoise.android.ui.conversation.InvitePreviewActionBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaImageBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPendingPlaceholder
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVideoBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVisualGridBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVoiceBubble
import dev.ipf.whitenoise.android.ui.conversation.reactions.CustomizeReactionsDialog
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionDetailsSheet
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionSummaryChip
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.conversation.replies.isOwnReplySender
import dev.ipf.whitenoise.android.ui.conversation.replies.senderTitleForReply
import dev.ipf.whitenoise.android.ui.conversation.share.ContactMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.LocationMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.UserMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.formatCoordinate
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedContactFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedLocationFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedUserFromText
import dev.ipf.whitenoise.android.ui.documentMentionsAccount
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt
import dev.ipf.whitenoise.android.ui.theme.amoledDirectionalAccentColor
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun messageBubbleBorder(
    highlighted: Boolean,
    mine: Boolean,
    invalidated: Boolean = false,
): BorderStroke? {
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        invalidated -> null
        amoledAccent != null -> BorderStroke(2.dp, amoledAccent)
        else -> null
    }
}

@Composable
internal fun messageBubblePresentation(
    invalidated: Boolean,
    deleted: Boolean,
    mine: Boolean,
    customArgb: Long? = null,
): BubblePresentation {
    val colorScheme = MaterialTheme.colorScheme
    return resolveBubblePresentationArgb(
        invalidated = invalidated,
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
    )
}

@Composable
internal fun messageBubbleFillColor(
    invalidated: Boolean,
    deleted: Boolean,
    mine: Boolean,
): Color = colorFromArgb(messageBubblePresentation(invalidated, deleted, mine).backgroundArgb)

@Composable
internal fun messageBubbleTimestampColor(
    invalidated: Boolean,
    mine: Boolean,
    deleted: Boolean,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        invalidated -> colorScheme.onErrorContainer
        amoledAccent != null -> amoledAccent
        mine && !deleted -> colorScheme.onPrimaryContainer
        else -> colorScheme.onSurfaceVariant
    }
}

/** Shared frame for caption and plain-text bubbles so both render paths use
 * the same background/content pairing and semantic mention accent. */
@Composable
internal fun MessageBubbleFrame(
    presentation: BubblePresentation,
    highlighted: Boolean,
    mine: Boolean,
    invalidated: Boolean,
    mentionedSelf: Boolean,
    mentionedYouLabel: String,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val mentionRailModifier =
        if (mentionedSelf) {
            Modifier
                .semantics { contentDescription = mentionedYouLabel }
                .drawBehind {
                    val railWidth = 3.dp.toPx()
                    val inset = 4.dp.toPx()
                    val radius =
                        androidx.compose.ui.geometry
                            .CornerRadius(railWidth / 2f, railWidth / 2f)
                    drawRoundRect(
                        color = colorFromArgb(presentation.mentionAccentArgb),
                        topLeft =
                            androidx.compose.ui.geometry
                                .Offset(inset, inset),
                        size =
                            androidx.compose.ui.geometry.Size(
                                railWidth,
                                (size.height - inset * 2).coerceAtLeast(railWidth),
                            ),
                        cornerRadius = radius,
                    )
                }
        } else {
            Modifier
        }
    Surface(
        modifier = modifier,
        color = colorFromArgb(presentation.backgroundArgb),
        contentColor = colorFromArgb(presentation.contentArgb),
        shape = RoundedCornerShape(18.dp),
        border = messageBubbleBorder(highlighted, mine, invalidated),
        tonalElevation = if (mine) 1.dp else 0.dp,
    ) {
        Column(
            modifier =
                mentionRailModifier
                    .then(contentModifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
internal fun rememberMessageMediaReferences(
    tags: List<MessageTagFfi>,
    messageIdHex: String,
    perMessageMediaReferences: List<MediaAttachmentReferenceFfi>?,
): List<MediaAttachmentReferenceFfi> =
    remember(tags, messageIdHex, perMessageMediaReferences) {
        perMessageMediaReferences ?: MediaReferenceParser.parseAllImetaTags(tags)
    }

internal fun messageBubbleLongPressPositionInWindow(
    rowCoordinates: LayoutCoordinates,
    localPosition: Offset,
): Offset = rowCoordinates.localToWindow(localPosition)

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
    quickReactionEmojis: List<String>,
    isActionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
    onReactionEmojiPicked: (String) -> Unit,
    onQuickReactionsSave: (List<String>) -> Unit,
    onQuickReactionsReset: () -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    composerGate: ComposerGate,
    inviteMutationInFlight: Boolean,
    onJoinInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    mentionCandidates: List<MentionComposer.Candidate>,
    mentionPickerEnabled: Boolean,
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
    // Convergence dropped this message onto a losing branch: it never reached
    // the group. The record survives as a tombstone, so flag it (an explicit
    // delete takes precedence over an invalidation tombstone).
    val invalidated = !deleted && item.projected?.invalidationStatus != null
    val bubbleTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    val bubbleSide = if (mine) BubbleSide.Mine else BubbleSide.Other
    val customBubbleArgb =
        appState.effectiveBubbleColorArgb(
            theme = bubbleTheme,
            side = bubbleSide,
            groupIdHex = controller.group.groupIdHex,
        )
    val colorScheme = MaterialTheme.colorScheme
    val customBubbleColorActive = customBubbleArgb != null && !deleted && !invalidated
    val bubblePresentation =
        messageBubblePresentation(
            invalidated = invalidated,
            deleted = deleted,
            mine = mine,
            customArgb = customBubbleArgb,
        )
    val bubbleContentColor = colorFromArgb(bubblePresentation.contentArgb)
    // #414: "you were mentioned" treatment. A received (not mine), live (not
    // deleted/invalidated) message whose markdown body @-mentions the current
    // account gets a left-edge accent line so a self-mention is spottable while
    // scrolling. Keyed on the body tokens + account so a late account switch /
    // profile load re-evaluates. The resolver is the FFI bech32→hex encoding;
    // the detection walk itself is the pure documentMentionsAccount.
    val selfAccountIdHex = appState.activeAccount?.accountIdHex
    val mentionedSelf =
        !mine &&
            !deleted &&
            !invalidated &&
            remember(record.contentTokens, selfAccountIdHex) {
                documentMentionsAccount(
                    document = record.contentTokens,
                    accountIdHex = selfAccountIdHex,
                    resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                )
            }
    val mentionedYouLabel = stringResource(R.string.mentioned_you)
    val scope = rememberCoroutineScope()
    // Window-space position of the long-press touch. The y component anchors
    // the action popover; the full point seeds partial text selection (#1370).
    var longPressWindowPosition by remember(record.messageIdHex) { mutableStateOf<Offset?>(null) }
    var longPressWindowY by remember { mutableStateOf<Float?>(null) }
    val rowCoordinates = remember(record.messageIdHex) { arrayOfNulls<LayoutCoordinates>(1) }
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
    val messageActionsLabel = stringResource(R.string.message_actions)
    val invalidatedBodyText = stringResource(R.string.message_invalidated)
    // Cached like the media references below: displayBody sanitizes/allocates
    // per call, and recomputing it for every visible bubble on every timeline
    // recomposition adds up. See #131.
    // Kind-1009 edits replace the body of an existing kind-9 chat. When an
    // edit is present for this message's id, prefer the latest edited text
    // over the original projection. Keyed on editState so a fresh edit
    // recomposes the bubble in place.
    val editState = controller.editsByTarget[record.messageIdHex]
    val displayedBody =
        remember(item, deleted, invalidated, messageTextCopy, deletedBodyText, invalidatedBodyText, editState) {
            when {
                // Check `deleted` first so the optimistic tombstone (from
                // controller.deletedMessageIds) renders immediately on tap.
                deleted -> deletedBodyText
                invalidated -> invalidatedBodyText
                // Edit overlay wins over both projected and raw plaintext.
                // We don't go through MessageProjector here — the edit
                // payload is plain text by spec; markdown re-parse will
                // happen below if record.contentTokens is populated, but
                // for kind-9 edits the body is the latest version verbatim.
                editState != null && record.kind == 9uL -> editState.latestText
                item.projected != null ->
                    TimelineProjector.displayBody(
                        item.projected,
                        messageTextCopy.copy(deleted = deletedBodyText),
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
    val showSenderAvatar =
        GroupProjector.shouldShowTranscriptSenderAvatar(
            memberCount = controller.members.size,
            mine = mine,
        )
    // Match the timestamp to the bubble's visual cue. AMOLED uses the same
    // directional accent as the border; other themes keep their paired M3
    // on-color tokens.
    val timestampColor = messageBubbleTimestampColor(invalidated, mine, deleted)
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
        onReactionEmojiPicked(emoji)
        // Route via launchMutation: same survives-navigation rationale as delete/send.
        appState.launchMutation { controller.toggleReaction(emoji, record) }
    }

    fun copyMessageText() {
        clipboard.setText(AnnotatedString(displayedBody))
        appState.present(R.string.copied)
        onActionMenuOpenChange(false)
    }

    fun copyMarkdownLink(url: String) {
        clipboard.setText(AnnotatedString(url))
        appState.present(R.string.copied)
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
        val messageGroupMaxWidth = (maxWidth * 0.95f - selectionGutterWidth).coerceAtLeast(0.dp)
        val senderAvatarWidth = if (showSenderAvatar) 40.dp else 0.dp
        val bubbleColumnMaxWidth = (messageGroupMaxWidth - senderAvatarWidth).coerceAtLeast(120.dp)

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
                        // reply gesture; taps are owned by the selection row.
                        if (deleted || readOnly || selectionMode || textSelectionMode) {
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex, replySwipeThresholdPx, maxSwipeOffsetPx) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, dragAmount ->
                                        val next = ReplySwipe.visualOffset(swipeDrag + dragAmount, maxSwipeOffsetPx)
                                        if (next != swipeDrag || dragAmount > 0f) change.consume()
                                        swipeDrag = next
                                    },
                                    onDragEnd = {
                                        if (ReplySwipe.shouldTriggerReply(swipeDrag, totalY = 0f, threshold = replySwipeThresholdPx)) {
                                            beginReply()
                                        }
                                        swipeDrag = 0f
                                    },
                                    onDragCancel = { swipeDrag = 0f },
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
                        // awaitLongPressOrCancellation observes the down WITHOUT
                        // consuming it (so a quick tap still reaches the child's
                        // viewer/player) and only fires once the press is held
                        // past the long-press timeout, at which point it wins
                        // the gesture and opens the actions menu. It self-cancels
                        // on movement beyond touch slop, so swipe-to-reply above
                        // is unaffected.
                        if (deleted || selectionMode || textSelectionMode) {
                            // A deleted message has no actions menu; batch selection
                            // and text selection route the row through their own UI.
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress != null) {
                                        longPress.consume()
                                        val windowPosition =
                                            rowCoordinates[0]?.let {
                                                messageBubbleLongPressPositionInWindow(it, longPress.position)
                                            } ?: return@awaitEachGesture
                                        val linkDestination =
                                            markdownLinkDestinationAt(markdownLinkLayouts.values, windowPosition)
                                        haptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                        )
                                        if (linkDestination != null) {
                                            copyMarkdownLink(linkDestination)
                                        } else {
                                            // Capture the press in window space before
                                            // opening so both the popover and text
                                            // selection seed at the finger (#326, #1370).
                                            longPressWindowPosition = windowPosition
                                            longPressWindowY = windowPosition.y
                                            onActionMenuOpenChange(true)
                                        }
                                    }
                                }
                            }
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
            if (showSenderAvatar) {
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .clickable(enabled = !textSelectionMode) { appState.presentProfile(appState.npub(record.sender)) },
                ) {
                    Avatar(
                        title = appState.displayName(record.sender),
                        seed = record.sender,
                        size = 32.dp,
                        pictureUrl = appState.avatarUrl(record.sender),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.widthIn(max = bubbleColumnMaxWidth),
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
                // Prefer the controller's listMedia cache — it carries
                // the receive-side `sourceEpoch`, which the imeta-tag
                // parser can't recover (no epoch field in the wire
                // format). Fall back to the imeta parser for optimistic
                // bridge records that haven't been projected yet.
                val perMessageMediaReferences = controller.mediaReferences[record.messageIdHex]
                val mediaReferences =
                    rememberMessageMediaReferences(
                        tags = record.tags,
                        messageIdHex = record.messageIdHex,
                        perMessageMediaReferences = perMessageMediaReferences,
                    )
                // Split media into image refs (rendered as a bubble or
                // 2-col grid) and file refs (a list of pills). Mixed
                // albums render both: images on top, file pills below.
                // `IndexedValue` preserves the real protocol-level
                // attachmentIndex from the full `mediaReferences`
                // list so per-tile cache lookups never collide across
                // image and file subsets.
                val imageAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) -> MediaReferenceParser.isImageMedia(ref) }
                            .toList()
                    }
                val audioAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) -> MediaReferenceParser.isAudioMedia(ref) }
                            .toList()
                    }
                val videoAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) -> MediaReferenceParser.isVideoMedia(ref) }
                            .toList()
                    }
                val fileAttachments =
                    remember(mediaReferences) {
                        mediaReferences
                            .withIndex()
                            .filter { (_, ref) ->
                                !MediaReferenceParser.isImageMedia(ref) &&
                                    !MediaReferenceParser.isAudioMedia(ref) &&
                                    !MediaReferenceParser.isVideoMedia(ref)
                            }.toList()
                    }
                val mediaPendingName =
                    remember(record.tags) {
                        record.tags
                            .firstOrNull { it.values.firstOrNull() == "_media_pending" }
                            ?.values
                            ?.getOrNull(1)
                    }
                // Visual attachments (image + video) ride one bubble:
                // a singleton routes to its dedicated bubble, a multi
                // goes to MediaVisualGridBubble which mixes image
                // and video tiles in pick order.
                val visualAttachments =
                    remember(imageAttachments, videoAttachments) {
                        (imageAttachments + videoAttachments).sortedBy { it.index }
                    }
                // An uncaptioned single image/video carries the footer
                // overlaid on its bottom-right; a caption (if any) takes
                // it instead via the text path below.
                val footerOnVisualMedia =
                    !deleted &&
                        !invalidated &&
                        visualAttachments.size == 1 &&
                        (editState?.latestText ?: record.plaintext).isBlank()
                val anyConfirmedMedia =
                    imageAttachments.isNotEmpty() ||
                        audioAttachments.isNotEmpty() ||
                        videoAttachments.isNotEmpty() ||
                        fileAttachments.isNotEmpty()
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
                val sharedContact =
                    remember(vcardAttachment, shareBodyText, deleted, invalidated) {
                        if (vcardAttachment != null && !deleted && !invalidated) {
                            parseSharedContactFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val sharedLocation =
                    remember(shareBodyText, deleted, invalidated, anyConfirmedMedia, record.kind) {
                        if (!deleted && !invalidated && !anyConfirmedMedia && record.kind == 9uL) {
                            parseSharedLocationFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val sharedUser =
                    remember(shareBodyText, deleted, invalidated, anyConfirmedMedia, record.kind) {
                        if (!deleted && !invalidated && !anyConfirmedMedia && record.kind == 9uL) {
                            parseSharedUserFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val pendingAttachmentsForRecord =
                    remember(record.messageIdHex, controller.pendingAttachmentsList(record.messageIdHex)) {
                        controller.pendingAttachmentsList(record.messageIdHex)
                    }
                val pendingAudio =
                    remember(pendingAttachmentsForRecord) {
                        pendingAttachmentsForRecord
                            .withIndex()
                            .filter { (_, p) -> p.mediaType.startsWith("audio/", ignoreCase = true) }
                            .toList()
                    }
                val pendingVideo =
                    remember(pendingAttachmentsForRecord) {
                        pendingAttachmentsForRecord
                            .withIndex()
                            .filter { (_, p) -> p.mediaType.startsWith("video/", ignoreCase = true) }
                            .toList()
                    }
                val pendingImage =
                    remember(pendingAttachmentsForRecord) {
                        pendingAttachmentsForRecord
                            .withIndex()
                            .filter { (_, p) -> p.mediaType.startsWith("image/", ignoreCase = true) }
                            .toList()
                    }
                val pendingVisuals =
                    remember(pendingImage, pendingVideo) {
                        (pendingImage + pendingVideo).sortedBy { it.index }
                    }
                // Synthesize references for each pending visual so
                // the existing single-bubble + grid bubble can render
                // them. mine=true threads the bytes through the
                // pendingAttachmentsList fallback in the auto-download
                // path.
                val pendingVisualRefs =
                    remember(record.messageIdHex, pendingVisuals) {
                        pendingVisuals.map { (index, pending) ->
                            IndexedValue(
                                index,
                                MediaAttachmentReferenceFfi(
                                    locators = emptyList(),
                                    ciphertextSha256 = "",
                                    plaintextSha256 = "",
                                    nonceHex = "",
                                    fileName = pending.fileName,
                                    mediaType = pending.mediaType,
                                    version = "encrypted-media-v1",
                                    sourceEpoch = 0u,
                                    dim = pending.dim,
                                    thumbhash = pending.thumbhash,
                                ),
                            )
                        }
                    }
                val footerOnPendingVisual =
                    !deleted && !invalidated && !anyConfirmedMedia && pendingVisualRefs.size == 1
                val showPendingPlaceholder =
                    !deleted &&
                        !invalidated &&
                        !anyConfirmedMedia &&
                        pendingAudio.isEmpty() &&
                        pendingVisualRefs.isEmpty() &&
                        mediaPendingName != null
                // #527: media (images/video, audio, files) renders on its OWN,
                // outside the colored message bubble. `hasMedia` decides whether
                // this row splits into standalone media + an optional caption
                // bubble, or stays a single text bubble. Deleted/invalidated
                // tombstones never pull media out — they always render as the
                // single tombstone bubble.
                val hasMedia =
                    !deleted &&
                        !invalidated &&
                        (
                            anyConfirmedMedia ||
                                pendingAudio.isNotEmpty() ||
                                pendingVisualRefs.isNotEmpty() ||
                                showPendingPlaceholder ||
                                sharedLocation != null ||
                                sharedUser != null
                        )
                // The media-rendering blocks. Each child keeps its own rounded
                // media Surface, so calling this directly in the row Column (not
                // inside the colored bubble Surface) gives every attachment its
                // own object (#527). Behavior — download gating, single-visual
                // footer overlay, tap-to-open viewers, upload/failed/retry — is
                // unchanged from the in-bubble version.
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
                                onActionMenuOpenChange(true)
                            }
                        }
                    }
                val mediaBlocks: @Composable ColumnScope.() -> Unit = {
                    if (sharedLocation != null) {
                        val shareContext = LocalContext.current
                        LocationMessageBubble(
                            location = sharedLocation,
                            onOpen = {
                                runCatching {
                                    shareContext.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                "https://maps.google.com/maps?q=" +
                                                    "${formatCoordinate(sharedLocation.latitude)}," +
                                                    formatCoordinate(sharedLocation.longitude),
                                            ),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }
                    if (sharedContact != null) {
                        ContactMessageBubble(contact = sharedContact)
                    }
                    if (sharedUser != null) {
                        UserMessageBubble(
                            user = sharedUser,
                            onOpen = { appState.presentProfile(sharedUser.npub) },
                        )
                    }
                    if (!deleted && !invalidated && visualAttachments.isNotEmpty()) {
                        if (visualAttachments.size == 1) {
                            val entry = visualAttachments.first()
                            Box {
                                if (MediaReferenceParser.isVideoMedia(entry.value)) {
                                    MediaVideoBubble(
                                        messageIdHex = record.messageIdHex,
                                        attachmentIndex = entry.index,
                                        reference = entry.value,
                                        mine = mine,
                                        controller = controller,
                                        appState = appState,
                                        onLongPress = onMediaLongPress,
                                    )
                                } else {
                                    MediaImageBubble(
                                        item = item,
                                        reference = entry.value,
                                        attachmentIndex = entry.index,
                                        controller = controller,
                                        appState = appState,
                                        mine = mine,
                                        onLongPress = onMediaLongPress,
                                    )
                                }
                                if (footerOnVisualMedia) {
                                    MediaFooterOverlay(
                                        timeText = rememberedClockTime(record.recordedAt),
                                        showStatus = mine,
                                        status = item.status,
                                    )
                                }
                            }
                        } else {
                            MediaVisualGridBubble(
                                item = item,
                                attachments = visualAttachments,
                                controller = controller,
                                appState = appState,
                                mine = mine,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && audioAttachments.isNotEmpty()) {
                        audioAttachments.forEach { entry ->
                            MediaVoiceBubble(
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = entry.index,
                                reference = entry.value,
                                mine = mine,
                                controller = controller,
                                appState = appState,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && fileAttachments.isNotEmpty()) {
                        fileAttachments.forEach { entry ->
                            // A vCard renders the contact card above; keep its file
                            // pill too so the .vcf stays downloadable/openable until
                            // the card gains its own save action (both app-generated
                            // and inbound shares must remain reachable as files).
                            MediaFileBubble(
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = entry.index,
                                reference = entry.value,
                                mine = mine,
                                controller = controller,
                                appState = appState,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && !anyConfirmedMedia && pendingAudio.isNotEmpty()) {
                        pendingAudio.forEach { (index, pending) ->
                            MediaVoiceBubble(
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = index,
                                reference =
                                    remember(record.messageIdHex, index, pending) {
                                        MediaAttachmentReferenceFfi(
                                            locators = emptyList(),
                                            ciphertextSha256 = "",
                                            plaintextSha256 = "",
                                            nonceHex = "",
                                            fileName = pending.fileName,
                                            mediaType = pending.mediaType,
                                            version = "encrypted-media-v1",
                                            sourceEpoch = 0u,
                                            dim = null,
                                            thumbhash = null,
                                        )
                                    },
                                mine = true,
                                controller = controller,
                                appState = appState,
                                onLongPress = onMediaLongPress,
                            )
                        }
                    }
                    if (!deleted && !invalidated && !anyConfirmedMedia && pendingVisualRefs.isNotEmpty()) {
                        val uploadFailed = item.status == MessageStatus.Failed
                        val retryUpload: () -> Unit = {
                            appState.launchMutation { controller.retryFailedSend(item) }
                        }
                        if (pendingVisualRefs.size == 1) {
                            val entry = pendingVisualRefs.first()
                            Box {
                                if (MediaReferenceParser.isVideoMedia(entry.value)) {
                                    MediaVideoBubble(
                                        messageIdHex = record.messageIdHex,
                                        attachmentIndex = entry.index,
                                        reference = entry.value,
                                        mine = true,
                                        controller = controller,
                                        appState = appState,
                                        onLongPress = onMediaLongPress,
                                        uploading = !uploadFailed,
                                        uploadFailed = uploadFailed,
                                        onRetryUpload = if (uploadFailed) retryUpload else null,
                                    )
                                } else {
                                    MediaImageBubble(
                                        item = item,
                                        reference = entry.value,
                                        attachmentIndex = entry.index,
                                        controller = controller,
                                        appState = appState,
                                        mine = true,
                                        onLongPress = onMediaLongPress,
                                        uploading = !uploadFailed,
                                    )
                                }
                                MediaFooterOverlay(
                                    timeText = rememberedClockTime(record.recordedAt),
                                    showStatus = true,
                                    status = item.status,
                                )
                            }
                        } else {
                            MediaVisualGridBubble(
                                item = item,
                                attachments = pendingVisualRefs,
                                controller = controller,
                                appState = appState,
                                mine = true,
                                onLongPress = onMediaLongPress,
                                uploading = !uploadFailed,
                            )
                        }
                    }
                    if (showPendingPlaceholder) {
                        MediaPendingPlaceholder(
                            pendingAttachments = controller.pendingAttachmentsList(record.messageIdHex),
                            failed = item.status == MessageStatus.Failed,
                            onRetry =
                                if (mine && item.status == MessageStatus.Failed) {
                                    { appState.launchMutation { controller.retryFailedSend(item) } }
                                } else {
                                    null
                                },
                        )
                    }
                }
                // Body text policy:
                // - Pending optimistic with an attachment: placeholder
                //   composable already renders, suppress text.
                // - Confirmed media (imeta tag present): render the
                //   user-typed caption, edit-overlay-aware so a
                //   subsequent edit on a media bubble updates the
                //   caption in place. We deliberately don't use
                //   `displayedBody` directly because MessageProjector
                //   falls back to the imeta filename for a blank
                //   caption — fine for chat-list previews, wrong for
                //   a bubble already showing the image inline.
                // - Non-media: render displayedBody (covers reactions,
                //   deletions, agent streams, plain text).
                val bodyTextToRender: String? =
                    when {
                        // Deleted/invalidated tombstones show only the
                        // tombstone copy, never an inline image/caption.
                        deleted || invalidated -> displayedBody
                        // The contact card / location bubble / user card carry
                        // the body, so the raw caption/link/npub text is hidden.
                        sharedContact != null || sharedLocation != null || sharedUser != null -> null
                        mediaPendingName != null && !anyConfirmedMedia -> null
                        anyConfirmedMedia ->
                            (editState?.latestText ?: record.plaintext).takeIf { it.isNotBlank() }
                        else -> displayedBody
                    }
                // Captions/plain bodies sit on the resolved bubble background and therefore use
                // its paired WCAG-safe content color. Footer-only media rows are
                // outside the bubble and retain the page's surface foreground.
                val timestampColor =
                    if (bodyTextToRender != null) bubbleContentColor else colorScheme.onSurfaceVariant
                LaunchedEffect(textSelectionMode, bodyTextToRender) {
                    if (textSelectionMode && bodyTextToRender.isNullOrBlank()) {
                        onTextSelectionModeChange(false)
                    }
                }
                val editedLabel =
                    if (editState != null && record.kind == 9uL && !deleted && !invalidated) {
                        if (editState.count > 1) {
                            stringResource(R.string.edited_count, editState.count)
                        } else {
                            stringResource(R.string.edited)
                        }
                    } else {
                        null
                    }
                val inlineFooter: @Composable () -> Unit = {
                    MessageInlineFooter(
                        timeText = rememberedClockTime(record.recordedAt),
                        color = timestampColor,
                        showStatus = mine && !deleted && !invalidated,
                        status = item.status,
                        editedLabel = editedLabel,
                        onEditedClick =
                            if (editState != null && !textSelectionMode) {
                                { editHistoryOpen = true }
                            } else {
                                null
                            },
                    )
                }
                // Last-line geometry of the body so the footer can sit on
                // that line when it fits, not merely when the widest line does.
                var lastLineLayout by remember(record.messageIdHex, bodyTextToRender) {
                    mutableStateOf<TextLayoutResult?>(null)
                }
                // Overflow decision is derived from a measurement of the FULL
                // body only. Keeping it separate from lastLineLayout (which
                // the currently-rendered text updates) avoids a recompose
                // loop: once we clip, the clipped text no longer overflows,
                // which would otherwise flip the decision back and forth.
                // Key it on the rendered text too: edit overlays can swap in a
                // shorter body before the next Text measurement, and an old
                // layout's line end must not index into the new string. Width is
                // also part of the measurement: a body that overflows in portrait
                // can fit after the same composition is resized to landscape.
                var bodyFullLayout by remember(record.messageIdHex, bodyTextToRender, bubbleColumnMaxWidth) {
                    mutableStateOf<TextLayoutResult?>(null)
                }
                // A long body collapses to MESSAGE_COLLAPSE_LINE_LIMIT lines
                // with Read More in the bottom footer row opening the full-screen view;
                // tombstones, edit/info copy, and groups with the local collapse
                // setting disabled never collapse (#325, #1180).
                val collapsible =
                    collapseLongMessages && !deleted && !invalidated && !textSelectionMode
                val readMoreLabel = stringResource(R.string.message_read_more)
                val readMoreStyle =
                    SpanStyle(color = bubbleContentColor, fontWeight = FontWeight.Bold)
                // The body/caption text + inline footer, plus the failed-send
                // retry row. Hoisted into a lambda so it can render either inside
                // the single text bubble (no media) or inside the caption bubble
                // just below standalone media (#527). When there is no body text
                // it falls through to the footer-only / retry handling exactly as
                // before.
                val bodyFooterAndRetry: @Composable ColumnScope.() -> Unit = {
                    if (bodyTextToRender != null) {
                        // Markdown only when the tokens describe exactly
                        // the text we're about to show: tombstone copy,
                        // imeta-filename fallbacks, etc. all diverge from
                        // record.plaintext and must stay plain. An empty
                        // document (legacy record, parse failure) falls
                        // through to the unchanged plain-text path.
                        val markdownDocument = record.contentTokens
                        val renderMarkdownBody =
                            !deleted &&
                                !invalidated &&
                                markdownDocument.blocks.isNotEmpty() &&
                                bodyTextToRender == record.plaintext
                        // Markdown can't be cleanly truncated to a line
                        // count mid-document, so clip to the height of
                        // MESSAGE_COLLAPSE_LINE_LIMIT body-large lines.
                        // The natural height is measured on the inner
                        // content (clipToBounds is visual only and doesn't
                        // constrain it); the overflow flag latches true so
                        // applying the cap can't shrink the measurement and
                        // flip it back.
                        val lineHeightPx =
                            with(density) { (MaterialTheme.typography.bodyLarge.lineHeight).toPx() }
                        val maxBodyHeightPx = lineHeightPx * MESSAGE_COLLAPSE_LINE_LIMIT
                        val maxBodyHeightDp = with(density) { maxBodyHeightPx.toDp() }
                        // Reset the one-way latch whenever the rendered body or its
                        // available width changes, then measure the natural height
                        // again before deciding whether to apply the cap.
                        var markdownOverflows by
                            remember(record.messageIdHex, bodyTextToRender, bubbleColumnMaxWidth) {
                                mutableStateOf(false)
                            }
                        val collapseMarkdown = renderMarkdownBody && collapsible && markdownOverflows
                        val plainTextOverflows =
                            !renderMarkdownBody &&
                                collapsible &&
                                bodyFullLayout?.let {
                                    it.hasVisualOverflow && it.lineCount > MESSAGE_COLLAPSE_LINE_LIMIT
                                } == true
                        val collapsedBody = collapseMarkdown || plainTextOverflows
                        val messageBody: @Composable () -> Unit = {
                            if (renderMarkdownBody) {
                                Box(
                                    modifier =
                                        Modifier
                                            .onSizeChanged {
                                                if (collapsible && it.height > maxBodyHeightPx) {
                                                    markdownOverflows = true
                                                }
                                            }.then(
                                                if (collapseMarkdown) {
                                                    Modifier
                                                        .heightIn(max = maxBodyHeightDp)
                                                        .clipToBounds()
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                ) {
                                    // Mention names resolve through the profile
                                    // cache; npub/nprofile taps stay in-app via
                                    // the profile sheet (never an external nostr:
                                    // intent). The "@" mention treatment is
                                    // reserved for an account in the roster
                                    // snapshot captured for this bubble (#1017),
                                    // so later roster updates do not rewrite old
                                    // rendered message semantics. If the roster
                                    // has not loaded yet, leave membership unknown
                                    // and keep pre-#1017 rendering until it does.
                                    val mentionMemberSnapshot =
                                        remember(record.messageIdHex, controller.membersLoaded) {
                                            if (controller.membersLoaded) controller.members else null
                                        }
                                    val mentionMembershipResolver =
                                        remember(appState, mentionMemberSnapshot) {
                                            mentionMemberSnapshot?.let { members ->
                                                { bech32: String -> appState.isRosterMember(bech32, members) }
                                            }
                                        }
                                    MarkdownMessageBody(
                                        markdownDocument,
                                        mentionDisplayName =
                                            remember(appState) {
                                                { bech32: String -> appState.mentionDisplayName(bech32) }
                                            },
                                        isGroupMember = mentionMembershipResolver,
                                        useDecorativeBackgrounds = !customBubbleColorActive,
                                        onNostrProfileTap =
                                            remember(appState) {
                                                { bech32: String -> appState.presentNostrProfile(bech32) }
                                            },
                                        onLastTextLayout = { lastLineLayout = it },
                                        onSelectableTextLayoutChanged =
                                            if (textSelectionMode) selectableTextLayoutReporter else null,
                                        onLinkTextLayoutChanged = markdownLinkLayoutReporter,
                                        onCopyLink = ::copyMarkdownLink,
                                    )
                                }
                            } else if (plainTextOverflows) {
                                val layout = bodyFullLayout!!
                                // Cut at the last fully-visible line and trim trailing
                                // whitespace. Read More now lives in the bottom footer
                                // row, so the body text has no inline link or tap span;
                                // long-press anywhere on the bubble still falls through
                                // to the action menu rather than expanding the bubble.
                                val clippedText =
                                    remember(bodyTextToRender, layout) {
                                        clippedMessageBodyText(
                                            bodyText = bodyTextToRender,
                                            lineEnd = layout.getLineEnd(MESSAGE_COLLAPSE_LINE_LIMIT - 1, visibleEnd = true),
                                        )
                                    }
                                Text(
                                    clippedText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = plainTextSelectionModifier,
                                    // Footer geometry follows the clipped text's
                                    // real last line, not the full measurement.
                                    onTextLayout = {
                                        lastLineLayout = it
                                        plainTextLayoutTracker.layoutResult = it
                                        reportPlainTextLayoutIfReady()
                                    },
                                )
                            } else {
                                Text(
                                    bodyTextToRender,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = plainTextSelectionModifier,
                                    maxLines = if (collapsible) MESSAGE_COLLAPSE_LINE_LIMIT + 1 else Int.MAX_VALUE,
                                    onTextLayout = {
                                        lastLineLayout = it
                                        bodyFullLayout = it
                                        plainTextLayoutTracker.layoutResult = it
                                        reportPlainTextLayoutIfReady()
                                    },
                                )
                            }
                        }
                        val selectableMessageBody: @Composable () -> Unit = {
                            if (textSelectionMode) {
                                CompositionLocalProvider(LocalClipboard provides textSelectionClipboard) {
                                    SelectionContainer(state = messageTextSelectionState) {
                                        messageBody()
                                    }
                                }
                            } else {
                                messageBody()
                            }
                        }
                        val readMoreFooter: @Composable () -> Unit = {
                            Text(
                                readMoreLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = readMoreStyle.color,
                                fontWeight = readMoreStyle.fontWeight,
                                modifier =
                                    Modifier.clickable(
                                        onClickLabel = readMoreLabel,
                                        role = Role.Button,
                                    ) { expandedFullView = true },
                            )
                        }
                        // Body text is always start-aligned inside the bubble,
                        // regardless of which side the bubble sits on or how wide
                        // a sibling (reply quote, media) makes the content column.
                        // End-aligning own messages left a short reply drifting to
                        // the right of a wide bubble (#439). The footer still places
                        // itself at the block's trailing edge internally.
                        val bodyModifier = Modifier.align(Alignment.Start)
                        if (collapsedBody) {
                            BubbleCollapsedFooterLayout(
                                readMore = readMoreFooter,
                                footer = inlineFooter,
                                modifier = bodyModifier,
                            ) {
                                selectableMessageBody()
                            }
                        } else {
                            BubbleFooterLayout(
                                footer = inlineFooter,
                                modifier = bodyModifier,
                                lastLineWidth =
                                    lastLineLayout?.let { layout ->
                                        if (layout.lineCount > 0) ceil(layout.getLineRight(layout.lineCount - 1)).toInt() else null
                                    },
                            ) {
                                selectableMessageBody()
                            }
                        }
                    } else if (!footerOnVisualMedia && !footerOnPendingVisual) {
                        Box(modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start)) {
                            inlineFooter()
                        }
                    }
                    if (mine && item.status == MessageStatus.Failed) {
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                enabled = !textSelectionMode,
                                onClick = { appState.launchMutation { controller.retryFailedSend(item) } },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.retry),
                                    tint = timestampColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                enabled = !textSelectionMode,
                                onClick = { controller.discardFailedSend(item) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.discard_failed_message),
                                    tint = timestampColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                // The sender-name label (group chats only). Rendered above the
                // media + caption when media is present (#527), or as the first
                // child of the single text bubble otherwise.
                val senderNameLabel: @Composable (insideBubble: Boolean) -> Unit = { insideBubble ->
                    if (showSenderAvatar) {
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
                                            onActionMenuOpenChange(true)
                                        }
                                    },
                                ),
                        )
                    }
                }
                // The reply quote card. Self-contained (own translucent Surface),
                // so it renders correctly whether inside the text bubble or
                // standalone above the media (#527).
                val replyPreviewCard: @Composable (insideBubble: Boolean) -> Unit = { insideBubble ->
                    replyPreview?.let { preview ->
                        val useCustomBubbleColors = insideBubble && customBubbleColorActive
                        ReplyPreviewCard(
                            senderTitle = senderTitleForReply(preview.sender, appState),
                            isOwn = isOwnReplySender(preview.sender, appState),
                            body = preview.body,
                            mediaKind = preview.mediaKind,
                            onClick = {
                                if (!textSelectionMode) onReplyPreviewClick(item)
                            },
                            onDismiss = null,
                            // Fill the content width: in the text bubble the
                            // column is sized to its widest child (IntrinsicSize.Max
                            // below) so the quote matches the bubble instead of
                            // hugging its own text (#428); above standalone media
                            // it lines up with the media's width (#527). A short
                            // quote + short reply still keeps a narrow bubble
                            // because the widest child is then small (#208 preserved).
                            fillWidth = true,
                            mentionDisplayName =
                                remember(appState, appState.profileRevisionForCompose) {
                                    { bech32: String -> appState.mentionDisplayName(bech32) }
                                },
                            containerColor = if (useCustomBubbleColors) Color.Transparent else null,
                            contentColor = if (useCustomBubbleColors) bubbleContentColor else null,
                            accentColor = if (useCustomBubbleColors) bubbleContentColor else null,
                        )
                    }
                }
                if (hasMedia) {
                    // #527: media renders on its OWN, outside the colored bubble.
                    // The sender label and reply quote sit above the media, then
                    // the caption (if any) follows in its own bubble just below.
                    Column(
                        modifier = Modifier.offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) },
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        senderNameLabel(false)
                        replyPreviewCard(false)
                        mediaBlocks()
                        // Caption: only when a non-blank caption accompanies the
                        // media. It gets the same colored bubble look as a plain
                        // text message, placed directly below the media.
                        if (bodyTextToRender != null) {
                            MessageBubbleFrame(
                                presentation = bubblePresentation,
                                highlighted = highlighted,
                                mine = mine,
                                invalidated = invalidated,
                                mentionedSelf = mentionedSelf,
                                mentionedYouLabel = mentionedYouLabel,
                                modifier = textSelectionBoundsModifier,
                            ) {
                                bodyFooterAndRetry()
                            }
                        } else {
                            // No caption: the footer (time/status) for audio,
                            // file, or multi-visual media still needs a home —
                            // and so does the failed-send retry row. Render them
                            // directly below the media, un-bubbled.
                            bodyFooterAndRetry()
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
                        invalidated = invalidated,
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
                        bodyFooterAndRetry()
                    }
                }
                MessageActionMenu(
                    // Never render the menu for a deleted message or while batch
                    // or partial text selection owns the row interaction.
                    expanded = isActionMenuOpen && !deleted && !selectionMode && !textSelectionMode,
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
                    canSelectText = !bodyTextToRender.isNullOrBlank(),
                    quickReactionEmojis = quickReactionEmojis,
                    onDismissRequest = { onActionMenuOpenChange(false) },
                    onReact = { emoji ->
                        onActionMenuOpenChange(false)
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
                        showStatus = mine && !deleted && !invalidated,
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
                                            messageTextCopy = messageTextCopy,
                                            onCancelReply = { controller.replyingTo = null },
                                            onSend = { text, onAccepted -> appState.launchMutation { controller.send(text, onAccepted) } },
                                            initialDraft = appState.draftFor(groupIdHex).orEmpty(),
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
                                        )
                                    }
                            }
                        },
                    )
                }
                if (emojiPickerOpen && !readOnly) {
                    EmojiPickerSheet(
                        restoreExpanded = restoreReactionPickerExpanded,
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
                            appState.present(R.string.copied)
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
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

internal fun clippedMessageBodyText(
    bodyText: String,
    lineEnd: Int,
): String = bodyText.substring(0, lineEnd.coerceIn(0, bodyText.length)).trimEnd()

// A body longer than this many rendered lines collapses to a Read More that
// opens the full-screen view rather than spilling down the transcript (#325).
private const val MESSAGE_COLLAPSE_LINE_LIMIT = 18
