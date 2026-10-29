package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsSeekResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableDocument
import dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableSource
import dev.ipf.whitenoise.android.core.ForwardEligibility
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.core.TimelineInvalidationPresentation
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.timelineInvalidationPresentation
import dev.ipf.whitenoise.android.core.usesPersistedFailurePresentation
import dev.ipf.whitenoise.android.share.presentOutboundShareFailure
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.ReportOutcome
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.authoritativeEditHistory
import dev.ipf.whitenoise.android.state.canReportMessage
import dev.ipf.whitenoise.android.state.dismissReports
import dev.ipf.whitenoise.android.state.mediaReferencesFor
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.state.reportMessage
import dev.ipf.whitenoise.android.state.reportsFor
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.ttsStartFailureMessage
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.TtsSentenceLayoutReporter
import dev.ipf.whitenoise.android.ui.common.boundedIntrinsicWidth
import dev.ipf.whitenoise.android.ui.common.directionalSwipe
import dev.ipf.whitenoise.android.ui.common.longPressOrVerticalDrag
import dev.ipf.whitenoise.android.ui.common.rememberDurableAvatar
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.common.rememberedClockTime
import dev.ipf.whitenoise.android.ui.common.rememberedMessageBubbleTime
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowTarget
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutReport
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutSink
import dev.ipf.whitenoise.android.ui.conversation.ConversationUiTrace
import dev.ipf.whitenoise.android.ui.conversation.InvitationActions
import dev.ipf.whitenoise.android.ui.conversation.InviteAcceptanceResolutionStatus
import dev.ipf.whitenoise.android.ui.conversation.LocalComposerSendMotion
import dev.ipf.whitenoise.android.ui.conversation.LocalPollVotesHost
import dev.ipf.whitenoise.android.ui.conversation.PollCard
import dev.ipf.whitenoise.android.ui.conversation.PollMessageActionOwner
import dev.ipf.whitenoise.android.ui.conversation.PollMessageContent
import dev.ipf.whitenoise.android.ui.conversation.composer.BlockedDmComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.DisbandedGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerPurpose
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.FrozenGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.composerDraftOwnerKey
import dev.ipf.whitenoise.android.ui.conversation.currentPollActionTarget
import dev.ipf.whitenoise.android.ui.conversation.currentPollCopyText
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationMediaViewerOpenRequest
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.media.messageHasShareablePayload
import dev.ipf.whitenoise.android.ui.conversation.media.presentAttachmentSaveOutcome
import dev.ipf.whitenoise.android.ui.conversation.media.saveMessageMediaAttachments
import dev.ipf.whitenoise.android.ui.conversation.media.shareMessageExternally
import dev.ipf.whitenoise.android.ui.conversation.media.shouldExpandCaptionedPortrait
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.reactions.CompleteReactionDetailsSheet
import dev.ipf.whitenoise.android.ui.conversation.reactions.ConfigureReactionsSheet
import dev.ipf.whitenoise.android.ui.conversation.reactions.localReactionOverlay
import dev.ipf.whitenoise.android.ui.conversation.reactions.rememberLocalReactionMotion
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.conversation.replies.isOwnReplySender
import dev.ipf.whitenoise.android.ui.conversation.replies.senderTitleForReply
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.isBareLocationShare
import dev.ipf.whitenoise.android.ui.conversation.share.isContactShareCaption
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedContactFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedLocationFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedUserFromText
import dev.ipf.whitenoise.android.ui.conversation.share.rememberLocalVCardContact
import dev.ipf.whitenoise.android.ui.conversation.traceConversationUiSection
import dev.ipf.whitenoise.android.ui.documentMentionsAccount
import dev.ipf.whitenoise.android.ui.group.isCurrentGroupMutationOwner
import dev.ipf.whitenoise.android.ui.markdownHasLinkAnnotationAt
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt
import dev.ipf.whitenoise.android.ui.theme.LocalMessageBubbleBaseColorScheme
import dev.ipf.whitenoise.android.ui.theme.amoledDirectionalAccentColor
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Draws semantic, saved-colour, or directional AMOLED borders in that precedence order. */
@Composable
internal fun messageBubbleBorder(
    highlighted: Boolean,
    mine: Boolean,
    persistedFailure: Boolean = false,
    customBorderArgb: Long? = null,
    mentionAccentArgb: Long? = null,
): BorderStroke? {
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        persistedFailure -> null
        highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        mentionAccentArgb != null && amoledAccent != null -> BorderStroke(2.dp, colorFromArgb(mentionAccentArgb))
        customBorderArgb != null && amoledAccent != null -> BorderStroke(2.dp, colorFromArgb(customBorderArgb))
        amoledAccent != null -> BorderStroke(2.dp, amoledAccent)
        else -> null
    }
}

/** Accent for a reply preview, inside or beside the bubble, honouring custom bubble colours. */
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

/** Bubble colours and shape for the direction, deletion state and custom colour. */
@Composable
internal fun messageBubblePresentation(
    deleted: Boolean,
    mine: Boolean,
    customArgb: Long? = null,
    persistedFailure: Boolean = false,
): BubblePresentation {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleScheme = LocalMessageBubbleBaseColorScheme.current ?: colorScheme
    // Keep tombstone and AMOLED foregrounds stable; live bubbles use the prototype's paired roles.
    val preserveSemanticSurface = deleted || isAmoledSurfaceTheme()
    val surfaceBackground =
        if (preserveSemanticSurface) {
            colorScheme.surfaceVariant
        } else {
            bubbleScheme.surfaceContainerHigh
        }
    val surfaceContent = if (preserveSemanticSurface) colorScheme.onSurfaceVariant else bubbleScheme.onSurface
    return resolveBubblePresentationArgb(
        deleted = deleted,
        amoled = isAmoledSurfaceTheme(),
        mine = mine,
        customArgb = customArgb,
        tokens =
            BubblePresentationTokens(
                errorBackgroundArgb = colorScheme.errorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                errorContentArgb = colorScheme.onErrorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceBackgroundArgb = surfaceBackground.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceContentArgb = surfaceContent.toArgb().toLong() and 0xFFFFFFFFL,
                mineBackgroundArgb = bubbleScheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
                mineContentArgb = bubbleScheme.onPrimary.toArgb().toLong() and 0xFFFFFFFFL,
                mentionAccentArgb = colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
            ),
        persistedFailure = persistedFailure,
    )
}

/** Fill colour of a bubble for the direction, deletion state and custom colour. */
@Composable
internal fun messageBubbleFillColor(
    deleted: Boolean,
    mine: Boolean,
    persistedFailure: Boolean = false,
): Color = colorFromArgb(messageBubblePresentation(deleted, mine, persistedFailure = persistedFailure).backgroundArgb)

/** Footer time colour for the direction and state. */
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
        deleted -> colorScheme.onSurfaceVariant
        else -> colorFromArgb(messageBubblePresentation(deleted = false, mine = mine).contentArgb)
    }
}

/** Compact accepted references for legacy callers; see [rememberMessageAttachments] for indexed use. */
@Composable
internal fun rememberMessageMediaReferences(
    tags: List<MessageTagFfi>,
    messageIdHex: String,
    sourceEpoch: ULong?,
    projectedMedia: List<MediaAttachmentOutcomeFfi>?,
): List<MediaAttachmentReferenceFfi> {
    val attachments = rememberMessageAttachments(tags, messageIdHex, sourceEpoch, projectedMedia)
    return attachments.references
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

/** Renders one message bubble and arbitrates its overlapping row-level interactions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onOpenConversationMedia: (ConversationMediaViewerOpenRequest) -> Unit = {},
    eventCardResolver: NostrEventCardResolver? = null,
    documentSaveFallback: DocumentSaveFallback? = null,
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
    onReplyPreviewClick: (TimelineMessage) -> Unit,
    composerGate: ComposerGate,
    blockedDmUnblockInFlight: Boolean = false,
    onUnblockBlockedDm: () -> Unit = {},
    groupDisbanded: Boolean = false,
    inviteMutationInFlight: Boolean,
    onJoinInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    mentionCandidates: List<MentionComposer.Candidate>,
    mentionPickerEnabled: Boolean,
    // The conversation's accepted-send reveal, run after a send from the Read more reader closes it (#3268).
    onReaderSendAccepted: () -> Unit = {},
    onBeforeBodyCollapse: () -> Unit = {},
    showSenderName: Boolean = false,
    showSenderAvatar: Boolean = false,
    collapseLongMessages: Boolean = true,
    readOnly: Boolean = false,
    ttsHighlightPassage: TtsPassage? = null,
    ttsReadAloudProgress: TtsReadAloudProgress? = null,
    ttsFollowTarget: ConversationTtsFollowTarget? = null,
    ttsSessionId: Long? = null,
    ttsQuickTransportViewportLock: TtsQuickTransportViewportLock? = null,
    ttsSentenceLayoutSink: ConversationTtsSentenceLayoutSink? = null,
    onTtsSentenceSeek: (TtsState) -> Unit = {},
    ttsRowInstance: Any? = null,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
) = traceConversationUiSection(ConversationUiTrace.BUBBLE) {
    val record = item.record
    val isPollRecord = MessageProjector.isPollKind(record.kind)
    val pollOwner =
        remember(controller, record.messageIdHex) {
            PollMessageActionOwner(controller.boundAccountRef, controller.group.groupIdHex, record.messageIdHex)
        }
    val actionsReadOnly = readOnly || (isPollRecord && currentPollActionTarget(controller, pollOwner) == null)
    val protocolAttachments =
        rememberMessageAttachments(
            tags = record.tags,
            messageIdHex = record.messageIdHex,
            sourceEpoch = record.sourceEpoch,
            projectedMedia = item.projected?.media,
        )
    // NIP-30 artwork renders inline in the text, so it is not listed as a shared file.
    val receivedEmoji = LocalReceivedEmoji.current
    val messageAttachments =
        remember(protocolAttachments, receivedEmoji) { protocolAttachments.withoutEmoji(receivedEmoji) }
    val mediaReferences = messageAttachments.references
    val keptMessages = LocalKeptMessages.current
    // Null until the controller has bound an account, which is also the only
    // state in which a kept reference could not be scoped to one identity.
    val keptMessageKey =
        remember(controller.boundAccountRef, controller.group.groupIdHex, record.messageIdHex) {
            controller.boundAccountRef?.takeIf { record.messageIdHex.isNotBlank() }?.let { accountRef ->
                KeptMessageKey(
                    accountRef = accountRef,
                    groupIdHex = controller.group.groupIdHex,
                    messageIdHex = record.messageIdHex,
                )
            }
        }
    val mine = controller.isMessageMine(record)
    val deleted =
        item.projected?.deleted == true || MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    // The same capability model the controller re-validates on the mutation
    // path; the UI only decides what to OFFER from it, never what to permit.
    val deleteCapability =
        if (readOnly) {
            MessageDeleteCapability(canDeleteForMe = false, canDeleteForEveryone = false)
        } else {
            controller.deleteCapabilityFor(record, alreadyDeleted = deleted, optimisticKeyOverride = item.id)
        }
    val tombstoneCleanupUnavailable = deleted && !deleteCapability.canDeleteForMe
    // Convergence reasons and local publish failures keep their content and
    // add a warning; only unknown reasons still take the error-styled
    // tombstone. Explicit deletion always wins.
    val invalidated = !deleted && item.projected?.invalidationStatus != null
    val rowReactionMotion = dev.ipf.whitenoise.android.ui.conversation.reactions.LocalRowReactionMotion.current
    val localReactionMotion =
        rowReactionMotion ?: run {
            val runtime = remember(controller) { appState.runtimeGeneration }
            val currentOwner =
                !appState.appLockScreenVisible &&
                    controller.isCurrentGroupMutationOwner(appState, runtime) &&
                    controller.acceptsConversationActionOwner(controller.boundAccountRef, controller.group.groupIdHex)
            val available = currentOwner && !deleted && !invalidated && !actionsReadOnly
            rememberLocalReactionMotion(
                controller to item.presentationId,
                enabled = rememberConversationMotionEnabled() && available,
            )
        }
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
    val customBubbleColorActive =
        bubbleTheme != BubbleTheme.Amoled && customBubbleArgb != null && !deleted && !persistedFailure
    val bubblePresentation =
        messageBubblePresentation(
            deleted = deleted,
            mine = mine,
            customArgb = customBubbleArgb,
            persistedFailure = persistedFailure,
        )
    val bubbleBackgroundColor = colorFromArgb(bubblePresentation.backgroundArgb)
    val bubbleContentColor = colorFromArgb(bubblePresentation.contentArgb)
    val replyQuoteColors =
        messageReplyQuoteColors(
            customBubbleColorActive = customBubbleColorActive,
            outgoingReply = mine && bubbleTheme != BubbleTheme.Amoled,
            background = bubbleBackgroundColor,
            content = bubbleContentColor,
            colorScheme = colorScheme,
        )
    val replyFillColor = replyQuoteColors.container
    val replySecondaryColor = replyQuoteColors.secondary
    // #414: "you were mentioned" treatment. A received (not mine), live
    // message whose markdown body @-mentions the current
    // account gets a logical-start outer-frame accent so a self-mention is
    // spottable while scrolling. Keyed on the body tokens + account so a late account switch /
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
    // Per-key state lives in three holders so the bubble stays within ART's compile limit (MessageBubbleState.kt).
    val pressState = remember(item.presentationId) { MessageBubblePressState() }
    val surfaces = remember(controller, item.presentationId) { MessageBubbleSurfaceState() }
    var explicitlyCollapsed by androidx.compose.runtime.saveable.rememberSaveable(
        controller.boundAccountRef,
        controller.group.groupIdHex,
        item.presentationId,
    ) { mutableStateOf(false) }
    val textLayouts = remember(record.messageIdHex) { MessageBubbleTextLayouts() }
    LaunchedEffect(isActionMenuOpen) {
        if (!isActionMenuOpen) pressState.longPressTtsStart = null
    }
    var longPressWindowY by remember { mutableStateOf<Float?>(null) }
    val hasReports = item.projected?.hasReports == true
    val focusedMessageLayer = if (isActionMenuOpen) rememberGraphicsLayer() else null
    var focusedMediaReady by remember(item.presentationId, focusedMessageLayer) { mutableStateOf(false) }
    val focusedWindowWidth =
        LocalWindowInfo.current.containerSize.width
            .takeIf { it > 0 } ?: Int.MAX_VALUE
    val focusedMediaWidthLimit =
        with(LocalDensity.current) {
            (minOf(focusedWindowWidth, 560.dp.roundToPx()) - 32.dp.roundToPx()).coerceAtLeast(1)
        }
    val focusedMediaCaptureModifier =
        Modifier
            .focusedMediaFootprint(
                focused = isActionMenuOpen,
                maximumPreviewWidth = focusedMediaWidthLimit,
                mine = mine,
            ).onSizeChanged { pressState.focusedMediaSize = it }
            .drawWithContent {
                if (focusedMessageLayer != null) {
                    focusedMessageLayer.record { this@drawWithContent.drawContent() }
                    focusedMediaReady = true
                    drawLayer(focusedMessageLayer)
                } else {
                    drawContent()
                }
            }
    val actionAnchorBoundsModifier =
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            pressState.messageBoundsInWindow[0] =
                IntRect(
                    left = bounds.left.roundToInt(),
                    top = bounds.top.roundToInt(),
                    right = bounds.right.roundToInt(),
                    bottom = bounds.bottom.roundToInt(),
                )
        }
    val swipeSettings = appState.swipePreferences.state
    val swipeRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipeAccount = controller.boundAccountRef
    val swipeGroup = controller.group.groupIdHex
    val swipeRuntime = appState.runtimeGeneration
    val swipeOwner =
        listOf(
            controller,
            record,
            swipeAccount,
            swipeRuntime,
            selectionMode,
            textSelectionMode,
            actionsReadOnly,
        )
    val replySwipe = rememberMessageReplySwipeState(record.messageIdHex, listOf(swipeOwner, swipeSettings, swipeRtl))
    var swipeDirection by remember(replySwipe) { mutableIntStateOf(if (swipeRtl) -1 else 1) }
    val swipeMounted = remember(replySwipe) { booleanArrayOf(true) }
    DisposableEffect(replySwipe) {
        onDispose {
            swipeMounted[0] = false
            replySwipe.cancel()
        }
    }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val messageTextCopy = rememberMessageTextCopy()
    val messageTextSelectionState = rememberSelectionState()
    val textSelectionClipboard =
        rememberExitOnCopyClipboard { onTextSelectionModeChange(false) }
    LaunchedEffect(textSelectionMode, pressState.longPressWindowPosition) {
        if (!textSelectionMode || textLayouts.textSelectionSeeded) return@LaunchedEffect
        // Let every Markdown Text leaf report in this frame before calculating
        // the global selection offset across the SelectionContainer.
        withFrameNanos { }
        val renderedLayouts = textLayouts.selectableTextLayouts.snapshot()
        textSelectionSeedRange(renderedLayouts, pressState.longPressWindowPosition)?.let { range ->
            messageTextSelectionState.select(range)
            textLayouts.textSelectionSeeded = true
        }
    }
    LaunchedEffect(textSelectionMode) {
        if (!textSelectionMode) {
            textLayouts.textSelectionSeeded = false
            textLayouts.textSelectionBoundsInWindow = null
            onTextSelectionBoundsChange(null)
        }
    }

    /** Registers the plain-text leaf for selection once both its layout and its position are known. */
    fun reportPlainTextLayoutIfReady() {
        val layoutResult = textLayouts.plainTextLayoutTracker.layoutResult ?: return
        val coordinates = textLayouts.plainTextLayoutTracker.coordinates ?: return
        textLayouts.selectableTextLayouts.update(textLayouts.plainTextLayoutKey, layoutResult, coordinates)
    }

    val composerSendMotion = dev.ipf.whitenoise.android.ui.conversation.LocalComposerSendMotion.current
    val plainTextSelectionModifier =
        Modifier.onGloballyPositioned { coordinates ->
            composerSendMotion?.destinationText(item.presentationId, coordinates)
            textLayouts.plainTextLayoutTracker.coordinates = coordinates
            reportPlainTextLayoutIfReady()
        }
    val textSelectionBoundsModifier =
        if (textSelectionMode) {
            Modifier.onGloballyPositioned {
                val bounds = it.boundsInWindow()
                textLayouts.textSelectionBoundsInWindow = bounds
                onTextSelectionBoundsChange(bounds)
            }
        } else {
            Modifier
        }
    // The prototype speaks in the first person whenever this account is
    // responsible for the removal — because it authored the message, or because
    // it hid the message locally — and in the passive voice otherwise.
    val deletedByMe = mine || MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    // An admin removal outranks authorship: the engine carries who actually removed the message, so a
    // message of this account's own that an admin took down must not read as "You deleted this message".
    // A tombstone with no evidence stays UNKNOWN and keeps the passive voice it has always had.
    val deletedBodyText =
        stringResource(
            when {
                item.projected?.deletionSource == DeletionSourceFfi.ADMIN -> R.string.message_deleted_by_admin
                deletedByMe -> R.string.message_deleted_by_you
                else -> R.string.message_deleted_by_other
            },
        )
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
    var translationDialogOpen by remember(controller, record.messageIdHex) { mutableStateOf(false) }
    var translationTarget by remember(controller, record.messageIdHex) { mutableStateOf<String?>(null) }
    val translationSource =
        dev.ipf.whitenoise.android.translation.rememberMessageTranslationSource(
            messageId = record.messageIdHex,
            text =
                if (record.kind == 9uL && !deleted && !persistedFailure) {
                    editState?.latestText ?: record.plaintext
                } else {
                    ""
                },
            account = controller.boundAccountRef,
            group = controller.group.groupIdHex,
            automaticEligible = !mine && !readOnly,
            selectedTarget = translationTarget,
            sourceRevision =
                listOf(record.sourceEpoch, editState?.count, editState?.versions?.lastOrNull()?.messageIdHex)
                    .joinToString(":"),
        )
    val visibleTranslation =
        dev.ipf.whitenoise.android.translation.LocalConversationTranslation.current
            ?.renditions
            ?.get(translationSource)
            ?.takeIf { it.showTranslation }
            ?.text
    if (translationDialogOpen && translationSource != null) {
        dev.ipf.whitenoise.android.translation.MessageTranslationDialog(
            source = translationSource,
            onTargetSelected = { translationTarget = it },
            onDismiss = { translationDialogOpen = false },
        )
    }
    val displayedBody =
        remember(item, deleted, messageTextCopy, deletedBodyText, invalidatedBodyText, editState) {
            messageBubbleDisplayedBody(
                item = item,
                deleted = deleted,
                persistedFailure = persistedFailure,
                editState = editState,
                textCopy = messageTextCopy,
                deletedBodyText = deletedBodyText,
                invalidatedBodyText = invalidatedBodyText,
            )
        }
    val canSpeakAloud =
        messageBubbleCanSpeak(
            record = record,
            editedText = editState?.latestText,
            deleted = deleted,
            invalidated = invalidated,
            ttsHasUsableEngine = appState.ttsHasUsableEngine,
        )
    val speakAloudLabel = stringResource(R.string.speak_aloud)
    val ttsSpeakableSource =
        remember(record, editState, deleted, persistedFailure) {
            if (deleted || persistedFailure) {
                null
            } else {
                resolveTtsSpeakableSource(
                    message = record,
                    editedText = editState?.latestText?.takeIf { record.kind == 9uL },
                )
            }
        }
    val speakableIdentity =
        messageBubbleTtsSpeakableIdentity(
            bodyText = ttsSpeakableSource?.text,
            deleted = deleted,
            persistedFailure = persistedFailure,
        )
    val ttsGateInput =
        MessageBubbleTtsGateInput(
            messageIdHex = record.messageIdHex,
            ttsHighlightPassage = ttsHighlightPassage,
            textSelectionMode = textSelectionMode,
            deleted = deleted,
            persistedFailure = persistedFailure,
            speakableIdentity = speakableIdentity,
        )
    val effectiveTtsCandidate = messageBubbleTtsProjectionCandidate(ttsGateInput)
    val editedMarkdownDocument =
        rememberMessageBubbleEditedDisplayMarkdownDocument(
            record = record,
            editState = editState,
            deleted = deleted,
            persistedFailure = persistedFailure,
            parseMarkdown = parseMarkdown,
        )
    var activeSpeakableDocument by remember(record.messageIdHex, ttsSpeakableSource, record.contentTokens) {
        mutableStateOf<MarkdownDocumentFfi?>(null)
    }
    LaunchedEffect(
        effectiveTtsCandidate,
        ttsSpeakableSource,
        record.messageIdHex,
        record.contentTokens,
        editedMarkdownDocument,
    ) {
        if (!effectiveTtsCandidate || speakableIdentity == null || ttsSpeakableSource == null) {
            activeSpeakableDocument = null
            return@LaunchedEffect
        }
        // Only an active edit has a rendered document to reuse. Everything else
        // without stored content tokens - a message saved before they existed,
        // or one the parser gave no blocks - has to be parsed here, exactly as
        // projectTtsSpeakableEntry parses it. Substituting a null edited
        // document leaves no projection, and the passage below is then rejected
        // for carrying a projection id the bubble cannot match, so read-aloud
        // speaks with no sentence or word highlight at all.
        if (!ttsSpeakableSource.useStoredContentTokens && editedMarkdownDocument != null) {
            activeSpeakableDocument = editedMarkdownDocument
            return@LaunchedEffect
        }
        val document =
            resolveTtsSpeakableDocument(
                message = record,
                source = ttsSpeakableSource,
                parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
            )
        currentCoroutineContext().ensureActive()
        activeSpeakableDocument = document
    }
    val queuedTtsPresentation =
        ttsFollowTarget?.let { target ->
            appState.ttsController
                .presentationEntryFor(
                    target.sessionId,
                    record.messageIdHex,
                    target.projectionId,
                    ttsSpeakableSource?.text,
                )?.takeIf { effectiveTtsCandidate }
        }
    val speakableProjection =
        remember(
            queuedTtsPresentation,
            activeSpeakableDocument,
            effectiveTtsCandidate,
            speakableIdentity,
        ) {
            if (queuedTtsPresentation != null) {
                queuedTtsPresentation.speakableProjection()
            } else if (!effectiveTtsCandidate || speakableIdentity == null) {
                null
            } else {
                activeSpeakableDocument?.let { document ->
                    messageSpeakableProjection(
                        bodyText = speakableIdentity.bodyText,
                        document = document,
                        mentionDisplayName = appState::mentionSpeechName,
                    )
                }
            }
        }
    val ttsProjectionState =
        resolveMessageBubbleTtsProjectionState(
            gateInput = ttsGateInput,
            projectionId = speakableProjection?.projectionId,
            progress = ttsReadAloudProgress,
        )
    val effectiveTtsPassage = ttsProjectionState.effectivePassage
    val preparedTtsSpeech =
        appState.ttsController.preparedSpeechFor(record.messageIdHex, speakableProjection?.projectionId)
    val ttsProjectionResolver =
        rememberTtsHighlightProjectionResolver(
            speakableProjection.takeIf { visibleTranslation == null },
            preparedTtsSpeech,
        )
    val ttsLeafHighlightResolver =
        remember(effectiveTtsPassage, record.messageIdHex, ttsProjectionResolver) {
            if (effectiveTtsPassage == null || effectiveTtsPassage.messageIdHex != record.messageIdHex) {
                null
            } else {
                ttsProjectionResolver?.resolverFor(effectiveTtsPassage, record.messageIdHex)
            }
        }
    val effectiveTtsFollowTarget =
        ttsFollowTarget?.takeIf { target ->
            target.messageIdHex == record.messageIdHex &&
                target.projectionId == speakableProjection?.projectionId &&
                target.sentenceIndex == effectiveTtsPassage?.sentenceIndex
        }
    val ttsSentenceLayoutReporter: TtsSentenceLayoutReporter? =
        remember(
            effectiveTtsFollowTarget,
            ttsProjectionResolver,
            ttsSentenceLayoutSink,
            ttsRowInstance,
        ) {
            val target = effectiveTtsFollowTarget
            val projectionResolver = ttsProjectionResolver
            val sink = ttsSentenceLayoutSink
            val rowInstance = ttsRowInstance
            when {
                target == null || projectionResolver == null -> null
                sink == null || rowInstance == null -> null
                else ->
                    { leafId: String, text: String, layoutResult: TextLayoutResult?, coordinates: LayoutCoordinates? ->
                        val leafLayout = projectionResolver.sentenceLayoutFor(target.sentenceIndex, leafId, text)
                        val bounds =
                            if (layoutResult != null && coordinates != null && leafLayout != null) {
                                ttsSentenceBoundsInWindow(layoutResult, coordinates, leafLayout.renderedRanges)
                            } else {
                                null
                            }
                        if (leafLayout == null || bounds == null) {
                            sink.clear(target, rowInstance, leafId)
                        } else {
                            sink.report(
                                ConversationTtsSentenceLayoutReport(
                                    target = target,
                                    rowInstance = rowInstance,
                                    renderedLeafId = leafId,
                                    boundsInWindow = bounds,
                                    coverage = leafLayout.coverage,
                                    expectedCoverage = leafLayout.expectedCoverage,
                                ),
                            )
                        }
                    }
            }
        }
    val effectiveTtsReadAloudProgress = ttsProjectionState.effectiveProgress
    val ttsBodyExpanded =
        rememberTtsMessageBodyExpanded(
            messageIdHex = record.messageIdHex,
            sourceText = speakableIdentity?.bodyText,
            sessionId = ttsSessionId,
            verifiedActivePassage = effectiveTtsFollowTarget != null && ttsProjectionResolver != null,
        )
    val forwardNowSeconds = (System.currentTimeMillis() / 1000L).toULong()
    val forwardEligibility =
        remember(
            record,
            editState,
            deleted,
            invalidated,
            persistedFailure,
            mediaReferences,
            item.status,
            isActionMenuOpen,
            forwardNowSeconds,
        ) {
            messageBubbleForwardEligibility(
                item = item,
                editState = editState,
                deleted = deleted,
                invalidated = invalidated,
                persistedFailure = persistedFailure,
                attachments = messageAttachments,
                controller = controller,
                nowSeconds = forwardNowSeconds,
            )
        }
    val forwardPayload = (forwardEligibility as? ForwardEligibility.Eligible)?.payload
    val reserveSenderAvatarSlot =
        GroupProjector.shouldShowTranscriptSenderAvatar(
            isDm = controller.usesDirectTranscriptChrome,
            mine = mine,
        )
    val tallies = controller.reactions[record.messageIdHex].orEmpty()
    val reactionTallies = if (deleted) emptyList() else tallies
    val showReactionSummary = reactionTallies.isNotEmpty()
    val reactionVisibilityState =
        remember(record.messageIdHex) {
            MutableTransitionState(showReactionSummary)
        }
    reactionVisibilityState.targetState = showReactionSummary
    val reactionHostPresent = reactionVisibilityState.currentState || reactionVisibilityState.targetState
    val messageShareScope = rememberCoroutineScope()
    val ttsLinkTapCoordinator =
        remember(record.messageIdHex, messageShareScope, appState, controller.group.groupIdHex) {
            messageBubbleTtsLinkTapCoordinator(messageShareScope, appState, controller)
        }
    DisposableEffect(ttsLinkTapCoordinator) {
        onDispose { ttsLinkTapCoordinator.cancelPendingActivation() }
    }
    val deferMarkdownLinkActivation =
        remember(ttsLinkTapCoordinator) {
            { action: () -> Unit -> ttsLinkTapCoordinator.activate(action) }
        }
    key(item.presentationId) {
        val currentActionMenuOpen by rememberUpdatedState(isActionMenuOpen)
        val currentActionMenuOpenChange by rememberUpdatedState(onActionMenuOpenChange)
        DisposableEffect(Unit) {
            onDispose {
                // A navigation, configuration change, or dataset replacement
                // can dispose a stationary row without pointer-up. Range-drag
                // cleanup remains owned by the pointer detector; this keyed
                // effect retires only this row's popup and cannot close a newly
                // recycled row's menu.
                if (currentActionMenuOpen) currentActionMenuOpenChange(false)
            }
        }
    }
    // A deleted message is inert: tear down every live-message surface if the
    // message is deleted out from under it (optimistic or remote delete).
    LaunchedEffect(deleted) {
        if (deleted) {
            surfaces.messageShareJob?.cancel()
            surfaces.messageShareJob = null
            onActionMenuOpenChange(false)
            onTextSelectionModeChange(false)
            surfaces.emojiPickerOpen = false
            surfaces.expandedFullView = false
            surfaces.infoSheetOpen = false
            surfaces.forwardSheetOpen = false
            surfaces.editHistoryOpen = false
            surfaces.reactionSheetOpen = false
            surfaces.customizeReactionsOpen = false
            surfaces.configureReactionSlot = null
            surfaces.deleteDialogOpen = false
            surfaces.messageShareInFlight = false
        }
    }
    LaunchedEffect(actionsReadOnly, isPollRecord) {
        if (isPollRecord && actionsReadOnly) {
            if (isActionMenuOpen) onActionMenuOpenChange(false)
            surfaces.emojiPickerOpen = false
            surfaces.reactionSheetOpen = false
            if (controller.replyingTo?.messageIdHex == record.messageIdHex) controller.replyingTo = null
        }
    }
    LaunchedEffect(deleteCapability.canDeleteAtAll) {
        if (!deleteCapability.canDeleteAtAll) surfaces.deleteDialogOpen = false
    }
    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            onActionMenuOpenChange(false)
            onTextSelectionModeChange(false)
            surfaces.forwardSheetOpen = false
            surfaces.infoSheetOpen = false
            surfaces.deleteDialogOpen = false
        }
    }

    /** Starts a reply to this message, or to the current poll target, unless it is deleted or read-only. */
    fun beginReply() {
        if (deleted || actionsReadOnly) return
        val target = if (isPollRecord) currentPollActionTarget(controller, pollOwner)?.record ?: return else record
        controller.replyingTo = target
        onActionMenuOpenChange(false)
    }

    /** Closes the action menu and shows Info, unless the message is already deleted. */
    fun openInfoSheet() {
        if (deleted) return
        onActionMenuOpenChange(false)
        surfaces.infoSheetOpen = true
    }

    /** Closes the action menu and asks which delete scope to use, when any scope is offered. */
    fun requestDelete() {
        if (!deleteCapability.canDeleteAtAll) return
        onActionMenuOpenChange(false)
        surfaces.deleteDialogOpen = true
    }

    /** Hides the message on this device only, closing the dialog once the controller confirms the removal. */
    fun performDeleteForMe() {
        if (surfaces.deleteInFlight) return
        surfaces.deleteInFlight = true
        appState.launchMutation {
            try {
                val removed = controller.hideMessageForMe(record.messageIdHex)
                if (removed) surfaces.deleteDialogOpen = false
            } finally {
                surfaces.deleteInFlight = false
            }
        }
    }

    /** Deletes the message for every member, keeping the dialog open until the outcome is known. */
    fun performDeleteForEveryone() {
        // The in-flight flag is the repeated-tap guard on top of the
        // controller's own idempotency (a second deleteMessage on an already
        // tombstoned id is a no-op).
        if (surfaces.deleteInFlight) return
        surfaces.deleteInFlight = true
        // launchMutation so the MLS commit + Nostr publish survive navigating
        // away from the conversation. The dialog stays open with its options
        // disabled until the outcome is known, and stays open on failure so
        // the error toast never explains a surface that silently vanished;
        // the optimistic tombstone rollback restores the bubble either way.
        appState.launchMutation {
            try {
                val removed = controller.deleteMessage(record, optimisticKeyOverride = item.id)
                if (removed) surfaces.deleteDialogOpen = false
            } finally {
                // Cancellation must not leave the flag stuck true, which would
                // disable deletion for this bubble's remember scope.
                surfaces.deleteInFlight = false
            }
        }
    }

    fun reactWithEmoji(emoji: String) {
        // Chokepoint guard: never react to a deleted message, whatever path
        // (menu, emoji picker) called in — even if that surface was open when
        // the delete landed.
        if (deleted || actionsReadOnly) return
        // Route via launchMutation: same survives-navigation rationale as delete/send.
        val reactionOrigin = localReactionMotion.takeOrigin(emoji)
        appState.launchMutation {
            val target =
                if (isPollRecord) {
                    currentPollActionTarget(controller, pollOwner)?.record ?: return@launchMutation
                } else {
                    record
                }
            controller.toggleReaction(emoji, target, onLocalIntent = { added ->
                localReactionMotion.accept(emoji, added, reactionOrigin)
            }) {
                !isPollRecord || currentPollActionTarget(controller, pollOwner) != null
            }
        }
    }

    /** Copies the whole message as plain text, a poll as its question and options from the current row. */
    fun copyMessageText() {
        if (deleted) return
        // Polls use the current question/options; ordinary messages copy the visible rendition.
        val text =
            if (isPollRecord) {
                currentPollCopyText(controller, pollOwner, messageTextCopy)
            } else {
                visibleTranslation ?: displayedBody
            }
        text?.let { clipboard.setText(AnnotatedString(it)) }
        onActionMenuOpenChange(false)
    }

    /** Captures display names and the conversation size alongside the rendered speech projection. */
    suspend fun ttsEntry(entryRecord: AppMessageRecordFfi) =
        projectTtsSpeakableEntry(
            message = entryRecord,
            editedText = controller.editsByTarget[entryRecord.messageIdHex]?.latestText,
            senderDisplayName = appState.displayName(entryRecord.sender),
            conversationMemberCount = controller.memberCount.takeIf { controller.membersLoaded },
            parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
            mentionDisplayName = appState::mentionSpeechName,
        )

    // Speak aloud reads from this message onward: catch-up listening is the
    // point of the action, and Stop on the transport bar is one tap. The
    // session takes auto-read ownership so messages arriving while it speaks
    // continue the read. Falls back to just this bubble when its record has
    // left the loaded timeline. When text is selected or the action menu was
    // opened from a text selection, start at the containing visible sentence.
    // A text press retains its rendered hit; genuine gutter/edge presses retain
    // the deterministic message-top fallback without guessing a nearby glyph.

    suspend fun speakFromHereEntries(literalCode: Boolean): List<TtsSpeakableEntry> =
        ttsSpeakFromHereCandidates(timeline = controller.timeline, selected = record)
            .mapNotNull { entryRecord -> ttsEntry(entryRecord) }
            .mapIndexed { index, entry ->
                if (index == 0 && literalCode) {
                    entry.copy(speechMode = dev.ipf.whitenoise.android.audio.tts.speech.SpeechMode.LiteralCode)
                } else {
                    entry
                }
            }

    /** Starts from the selected sentence and preserves a specific start-gate explanation. */
    fun startSpeakAloud(
        startRenderedHit: RenderedTextHit? = null,
        fallbackVisibleText: String? = null,
        fallbackVisibleOffset: Int? = null,
        literalCode: Boolean = false,
        expectedPress: TtsLongPressStart? = null,
    ) {
        if (deleted) return
        val owner =
            expectedPress ?: captureTtsLongPressStart(
                startRenderedHit,
                ttsSpeakableSource?.text,
                controller,
                appState,
            )
        val canCommit = { isTtsLongPressStartCurrent(owner, record, controller, appState) }
        if (!canCommit()) return
        val projectionStillCurrent = appState.ttsController.speechStartProjectionGuard()
        val locale = java.util.Locale.getDefault()
        appState.launchMutation {
            val entries = speakFromHereEntries(literalCode)
            if (!canCommit() || !projectionStillCurrent()) {
                return@launchMutation
            }
            if (entries.firstOrNull()?.messageIdHex != record.messageIdHex) {
                appState.present(R.string.tts_bar_error)
                return@launchMutation
            }
            val fallbackSentenceIndex =
                if (fallbackVisibleText == null || fallbackVisibleOffset == null) {
                    0
                } else {
                    speakableSentenceIndexAtVisibleOffset(
                        visibleText = fallbackVisibleText,
                        speakableText = entries.first().text,
                        visibleOffset = fallbackVisibleOffset,
                        locale = locale,
                    )
                }
            val started =
                appState.speakAloudAutoRead(
                    controller.group.groupIdHex,
                    entries,
                    locale,
                    startSentenceIndex = fallbackSentenceIndex,
                    startRenderedHit =
                        startRenderedHit?.let {
                            preparedHitFromRenderedHit(entries.first(), it)
                        },
                    backgroundPreparation = true,
                    canCommit = canCommit,
                )
            if (!started && canCommit()) appState.present(appState.ttsStartFailureMessage())
        }
    }

    /** Starts read-aloud at the selected sentence, or at the long-pressed text when nothing is selected. */
    fun speakFromHere() {
        val layouts = textLayouts.selectableTextLayouts.snapshot()
        val selectionActive = textSelectionMode && messageTextSelectionState.selectedTexts.isNotEmpty()
        if (!selectionActive) {
            startSpeakAloud(pressState.longPressTtsStart?.renderedHit, expectedPress = pressState.longPressTtsStart)
            return
        }
        val visibleText = concatenatedVisibleText(layouts).ifBlank { displayedBody }
        val visibleOffset =
            if (record.contentTokens.truncated) {
                null
            } else {
                visibleOffsetFromSelection(
                    layouts = layouts,
                    selectedTexts = messageTextSelectionState.selectedTexts,
                    preferredVisibleOffset = pressState.selectionSeedVisibleOffset,
                )
            }
        val renderedHit =
            if (record.contentTokens.truncated) {
                null
            } else {
                renderedTextHitFromSelection(
                    layouts = layouts,
                    selectedTexts = messageTextSelectionState.selectedTexts,
                    preferredVisibleOffset = pressState.selectionSeedVisibleOffset,
                )
            }
        startSpeakAloud(
            startRenderedHit = renderedHit,
            fallbackVisibleText = visibleText,
            fallbackVisibleOffset = visibleOffset,
        )
    }

    fun seekActiveSentence(sentenceIndex: Int) {
        when (
            appState.ttsController.seekToSentence(
                record.messageIdHex,
                sentenceIndex,
                speakableProjection?.projectionId,
            )
        ) {
            TtsSeekResult.Repositioned,
            TtsSeekResult.RepositionedAcrossMessages,
            -> {
                appState.ttsHistorySession.cancelPendingSeek()
                ttsLinkTapCoordinator.cancelPendingActivation()
                onTtsSentenceSeek(appState.ttsController.state.value)
                return
            }

            TtsSeekResult.MessageNotInWindow -> {
                if (appState.ttsHistorySession.requestSentenceSeek(
                        record.messageIdHex,
                        record.recordedAt,
                        sentenceIndex,
                        speakableProjection?.projectionId.orEmpty(),
                    )
                ) {
                    ttsLinkTapCoordinator.cancelPendingActivation()
                }
            }
            TtsSeekResult.SentenceOutOfRange,
            TtsSeekResult.SessionInactive,
            -> Unit
        }
    }

    /** Seeks read-aloud to a tapped sentence through the history session when this message is not projected. */
    fun seekRenderedSentenceOutsideCurrentProjection(
        hit: RenderedTextHit,
        allowOmittedLinkNeighbor: Boolean,
    ) {
        val account = controller.boundAccountRef
        val owner =
            appState.ttsHistorySession.conversationSource.value?.takeIf {
                it.accountRef == account && it.groupIdHex == controller.group.groupIdHex
            } ?: return
        val runtimeGeneration = appState.runtimeGeneration
        val expectedText = ttsSpeakableSource?.text
        val canCommit = {
            val live = controller.timeline.firstOrNull { it.record.messageIdHex == record.messageIdHex }
            val source =
                dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableSource(
                    live?.record ?: record,
                    controller.editsByTarget[record.messageIdHex]?.latestText,
                )
            val sameOwner = appState.activeAccountRef == account && appState.runtimeGeneration == runtimeGeneration
            val unavailable =
                record.messageIdHex in controller.deletedMessageIds ||
                    live?.projected?.deleted == true ||
                    live?.projected?.invalidationStatus != null
            sameOwner && !unavailable && source?.text == expectedText
        }
        if (!canCommit()) return
        val accepted =
            appState.ttsHistorySession.requestRenderedSentenceSeek(
                record.messageIdHex,
                record.recordedAt,
                expectedSource = owner,
                resolveTarget = {
                    if (!canCommit()) return@requestRenderedSentenceSeek null
                    val entry = ttsEntry(record) ?: return@requestRenderedSentenceSeek null
                    val projection = entry.speakableProjection()
                    dev.ipf.whitenoise.android.audio.tts.TtsRenderedSeekRequest(
                        entry = entry,
                        sentenceIndex = { prepared ->
                            TtsHighlightProjectionResolver(projection, prepared)
                                .sentenceIndexAtRenderedOffset(hit, allowOmittedLinkNeighbor)
                        },
                        canCommit = canCommit,
                    )
                },
                onCommitted = { onTtsSentenceSeek(appState.ttsController.state.value) },
            )
        if (accepted) ttsLinkTapCoordinator.cancelPendingActivation()
    }

    /** Whether this conversation's read-aloud session is speaking, paused or preparing right now. */
    fun ownsActiveReadAloud(): Boolean {
        val activeState = appState.ttsController.state.value
        val active =
            activeState is TtsState.Speaking || activeState is TtsState.Paused || activeState is TtsState.Preparing
        return active && appState.ownsTtsAutoReadSession(controller.group.groupIdHex)
    }

    /** Seeks owned read-aloud to the double-tapped sentence, or starts reading there unless the tap hit a link. */
    @Suppress("ComplexCondition", "ReturnCount")
    fun seekSpeakAloudAt(pressInWindow: Offset) {
        if (deleted || selectionMode || textSelectionMode || !canSpeakAloud) return
        val layouts = textLayouts.selectableTextLayouts.snapshot()
        if (ownsActiveReadAloud()) {
            val hit = renderedTextHitAtWindowPosition(layouts, pressInWindow)
            val sentenceIndex =
                hit?.let {
                    ttsProjectionResolver?.sentenceIndexAtRenderedOffset(
                        it,
                        // Retain the established link double-tap coordination during playback.
                        allowOmittedLinkNeighbor =
                            markdownHasLinkAnnotationAt(
                                textLayouts.markdownLinkLayouts.values,
                                pressInWindow,
                            ),
                    )
                }
            if (sentenceIndex != null) {
                seekActiveSentence(sentenceIndex)
            } else if (ttsProjectionResolver == null && hit != null && !record.contentTokens.truncated) {
                seekRenderedSentenceOutsideCurrentProjection(
                    hit,
                    markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, pressInWindow),
                )
            }
            // An owned session must never be replaced merely because a hit is
            // ambiguous, outside the queue window, or denied audio focus. A
            // pending link remains armed and opens after its tap window.
            return
        }
        // Outside playback, links retain their immediate activation and do
        // not become a read-aloud gesture target.
        if (markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, pressInWindow)) return
        val renderedHit =
            if (record.contentTokens.truncated) {
                null
            } else {
                renderedTextHitAtWindowPosition(layouts, pressInWindow)
            } ?: return
        startSpeakAloud(renderedHit)
    }

    val sentenceActionsEnabled =
        !deleted &&
            !selectionMode &&
            !textSelectionMode &&
            canSpeakAloud &&
            appState.ttsController.effectiveSpeechLocale != null &&
            appState.ownsTtsAutoReadSession(controller.group.groupIdHex)
    val ttsSentenceActions =
        remember(ttsProjectionResolver, sentenceActionsEnabled, speakableProjection?.projectionId) {
            if (!sentenceActionsEnabled || ttsProjectionResolver == null) {
                null
            } else {
                dev.ipf.whitenoise.android.ui.TtsSentenceActions(
                    choices = ttsProjectionResolver::sentenceChoices,
                    select = { choice ->
                        if (choice.revision != speakableProjection?.projectionId) {
                            false
                        } else {
                            when (
                                appState.ttsController.seekToSentence(
                                    record.messageIdHex,
                                    choice.ordinal,
                                    choice.revision,
                                )
                            ) {
                                TtsSeekResult.Repositioned, TtsSeekResult.RepositionedAcrossMessages -> {
                                    appState.ttsHistorySession.cancelPendingSeek()
                                    ttsLinkTapCoordinator.cancelPendingActivation()
                                    onTtsSentenceSeek(appState.ttsController.state.value)
                                    true
                                }
                                TtsSeekResult.MessageNotInWindow ->
                                    appState.ttsHistorySession.requestSentenceSeek(
                                        record.messageIdHex,
                                        record.recordedAt,
                                        choice.ordinal,
                                        choice.revision,
                                    )
                                else -> false
                            }
                        }
                    },
                )
            }
        }

    /** Copies a link destination to the clipboard and closes the action menu. */
    fun copyMarkdownLink(url: String) {
        if (deleted) return
        clipboard.setText(AnnotatedString(url))
        onActionMenuOpenChange(false)
    }

    /** Enters text selection for this message with a cleared layout registry, closing the action menu. */
    fun beginTextSelection() {
        if (deleted) return
        textLayouts.selectableTextLayouts.clear()
        textLayouts.textSelectionSeeded = false
        onActionMenuOpenChange(false)
        onTextSelectionModeChange(true)
    }

    /** Opens the forward sheet when the message has a forwardable payload. */
    fun beginForward() {
        if (deleted || actionsReadOnly || forwardPayload == null) return
        onActionMenuOpenChange(false)
        surfaces.forwardSheetOpen = true
    }

    val pendingAttachmentsForRecord = controller.pendingAttachmentsList(record.messageIdHex)
    val bubbleMedia = rememberBubbleMedia(messageAttachments, pendingAttachmentsForRecord)
    val mediaCaption =
        MessageProjector.mediaCaption(
            message = record,
            body = editState?.latestText ?: record.plaintext,
        )
    val outboundShareText =
        when {
            mediaReferences.isNotEmpty() || pendingAttachmentsForRecord.isNotEmpty() -> mediaCaption
            record.kind == 9uL -> editState?.latestText ?: record.plaintext
            else -> null
        }?.takeIf { it.isNotBlank() }
    val protocolAttachmentCount =
        remember(record.tags, receivedEmoji) {
            record.tags.count { it.values.firstOrNull() == "imeta" } - receivedEmoji.attachmentIndexes.size
        }
    val canShareMessage =
        !isPollRecord &&
            !deleted &&
            !invalidated &&
            !persistedFailure &&
            !surfaces.messageShareInFlight &&
            messageHasShareablePayload(
                text = outboundShareText,
                references = mediaReferences,
                retained = pendingAttachmentsForRecord,
                protocolAttachmentCount = protocolAttachmentCount,
            )
    val messageShareTitle = stringResource(R.string.shared_media_share)
    val mediaPendingName =
        remember(record.tags) {
            record.tags
                .firstOrNull { it.values.firstOrNull() == "_media_pending" }
                ?.values
                ?.getOrNull(1)
        }
    val showPendingPlaceholder =
        shouldShowPendingFilePlaceholder(
            deleted = deleted,
            hasConfirmedMedia = bubbleMedia.hasConfirmedMedia,
            pendingAudioCount = bubbleMedia.pendingAudio.size,
            pendingVisualCount = bubbleMedia.pendingVisuals.size,
            hasPendingMediaMarker = mediaPendingName != null,
        )
    val hasGeneralFileCard =
        !deleted &&
            !persistedFailure &&
            (
                bubbleMedia.files.isNotEmpty() ||
                    (
                        showPendingPlaceholder && pendingAttachmentsForRecord.any(::isPendingGeneralFile)
                    )
            )

    // Whether each of the two most recent presses landed on a link: a link tap followed by a nearby text tap has
    // already activated the link, so that pair must not also open the editor.
    val recentPressesOnLink = remember { BooleanArray(2) }
    val canEditMessage =
        !actionsReadOnly && mine && record.kind == 9uL && record.messageIdHex.isNotBlank() && !deleted

    /** Opens this message in the edit composer; reply and edit modes are mutually exclusive in the composer banner. */
    fun beginEdit() {
        if (deleted || actionsReadOnly) return
        onActionMenuOpenChange(false)
        controller.replyingTo = null
        controller.beginMessageEdit(record.messageIdHex)
    }

    /**
     * Whether a press lands on this bubble's rendered text rather than a link, the row's empty width or its
     * chrome, so a double tap there may edit without stealing a link's own activation.
     */
    fun ordinaryTextHitAt(pressInWindow: Offset): Boolean =
        !markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, pressInWindow) &&
            renderedTextHitAtWindowPosition(
                textLayouts.selectableTextLayouts.snapshot(),
                pressInWindow,
                requireTextLineHit = true,
            ) != null

    /**
     * Double-tapping the ordinary text of your own editable message opens it for editing (#3388). While this
     * conversation's read-aloud session is active the same gesture keeps seeking there, and other messages seek or
     * start read-aloud as before.
     */
    val messageTextDoubleTap: (Offset) -> Unit = { position ->
        val pressInWindow = pressState.rowCoordinates[0]?.localToWindow(position)
        when {
            pressInWindow == null -> Unit
            canEditMessage && !ownsActiveReadAloud() ->
                if (recentPressesOnLink.none { it } && ordinaryTextHitAt(pressInWindow)) beginEdit()
            else -> seekSpeakAloudAt(pressInWindow)
        }
    }

    // Resolved when the gesture fires rather than on every recomposition: the
    // conversation recomposes constantly while a message is being read, and a
    // bubble has no reason to observe playback state just to know what a
    // gesture it may never receive would have meant.
    fun quickTransportFromTwoFingerSwipe() {
        val action =
            ttsQuickTransportActionFor(
                state = appState.ttsController.state.value,
                ownsSession = appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
                canSpeakMessage = !deleted && canSpeakAloud,
            )
        if (action == TtsQuickTransportAction.Ignore) return
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        when (action) {
            TtsQuickTransportAction.Pause -> appState.ttsController.pause()
            TtsQuickTransportAction.Resume -> appState.ttsController.resume()
            TtsQuickTransportAction.StartReadingMessage -> startSpeakAloud()
            TtsQuickTransportAction.Ignore -> Unit
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (rowReactionMotion == null) Modifier.localReactionOverlay(localReactionMotion) else Modifier)
            .onGloballyPositioned {
                if (replySwipe.atRest) replySwipe.rowBoundsInRoot = it.boundsInRoot()
            },
    ) {
        val selectionGutterWidth = if (selectionMode) messageBubbleSelectionGutterWidth else 0.dp
        val senderAvatarSlotWidth = if (reserveSenderAvatarSlot) MessageBubbleSenderAvatarSlotWidth else 0.dp
        val bubbleColumnMaxWidth =
            messageBubbleColumnMaxWidth(
                containerWidth = maxWidth,
                selectionGutterWidth = selectionGutterWidth,
                senderAvatarSlotWidth = senderAvatarSlotWidth,
            )
        val bubbleColumnMinWidth =
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = hasGeneralFileCard,
                maxWidth = bubbleColumnMaxWidth,
            )
        val longPressBlockedBySelection = selectionMode && !rangeDragActive
        val replySwipeUnavailable = deleted || actionsReadOnly || textSelectionMode || selectionMode

        /** An unavailable menu command must not claim a swipe or show a misleading cue. */
        fun availableSwipe(action: SwipeAction): SwipeAction =
            when {
                replySwipeUnavailable -> SwipeAction.Off
                action == SwipeAction.Forward && forwardPayload == null -> SwipeAction.Off
                else -> action
            }
        val leftSwipe = availableSwipe(swipeSettings.resolved(SwipeBinding.MessageLeft, swipeRtl))
        val rightSwipe = availableSwipe(swipeSettings.resolved(SwipeBinding.MessageRight, swipeRtl))
        val swipeAction = if (swipeDirection < 0) leftSwipe else rightSwipe

        /** Re-resolves the retained row and owner before reusing the normal reply/chooser handlers. */
        @Suppress("ReturnCount") // Separate missing target, owner and eligibility rejection boundaries.
        fun commitSwipe(direction: Int) {
            val current = controller.currentActionTimelineItem(record.messageIdHex) ?: return
            val gestureCurrent = swipeMounted[0] && appState.swipePreferences.state == swipeSettings
            val ownerCurrent =
                appState.runtimeGeneration == swipeRuntime &&
                    appState.activeAccountRef == swipeAccount &&
                    controller.acceptsConversationActionOwner(swipeAccount, swipeGroup)
            val targetCurrent =
                current.projected?.deleted != true &&
                    !MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds) &&
                    current.record == record
            if (!gestureCurrent || !ownerCurrent) return
            if (!targetCurrent || replySwipeUnavailable) return
            when (if (direction < 0) leftSwipe else rightSwipe) {
                SwipeAction.Reply -> beginReply()
                SwipeAction.Forward -> beginForward()
                SwipeAction.React -> {
                    onActionMenuOpenChange(false)
                    surfaces.emojiPickerOpen = true
                }
                else -> Unit
            }
        }

        // The cue describes the chosen action; reaction chrome translates with the same bubble container.
        if (!replySwipeUnavailable) {
            MessageReplySwipeGlyph(
                state = replySwipe,
                messageIdHex = record.messageIdHex,
                physicalDirection = swipeDirection,
                action = swipeAction,
            )
        }

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
                    .testTag(messageBubbleRowTestTag(record.messageIdHex))
                    .twoFingerSwipeDown(
                        // Batch selection and text selection own the row while
                        // they are active, and a deleted message has nothing to
                        // read. Everything else keeps the shortcut, including a
                        // bubble that cannot itself be spoken: holding what is
                        // already playing does not need this message to have
                        // anything to say.
                        enabled = !selectionMode && !textSelectionMode && !deleted,
                        viewportLock = ttsQuickTransportViewportLock,
                        onSwipe = ::quickTransportFromTwoFingerSwipe,
                    )
                    // Keep the active hold detector ahead of selection chrome that changes mid-drag.
                    .then(
                        // Long-press lives in a raw pointerInput, not
                        // combinedClickable, so it WINS over inner media
                        // children (image/video/file/voice) that install their
                        // own tap `clickable`. Those children sit deeper in the
                        // hit-test tree and would otherwise swallow the press
                        // before a row-level combinedClickable saw the
                        // long-press — which is why long-press did nothing on a
                        // media bubble while it worked on a text bubble (#262).
                        // A quick tap still reaches the child's viewer/player.
                        // Once held, ordinary message actions open at the platform
                        // threshold while a vertical drag dismisses them and
                        // switches to anchored batch selection. Markdown links
                        // keep their copy-on-release routing. Horizontal motion
                        // remains available to swipe-to-reply above. Batch/text selection
                        // own the row, and a tombstone stays actionable only when its local
                        // cleanup path is usable. That is an argument, never an omitted
                        // modifier: selection chrome must not restructure the chain under an
                        // active hold.
                        Modifier.longPressOrVerticalDrag(
                            enabled =
                                !tombstoneCleanupUnavailable && !longPressBlockedBySelection && !textSelectionMode,
                            onLongPressStart = { position ->
                                haptics.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                                textLayouts.pendingLongPressLinkDestination[0] = null
                                // A tombstone has exactly one action, so it skips
                                // the menu and opens its confirmation directly.
                                if (deleted) {
                                    requestDelete()
                                    return@longPressOrVerticalDrag
                                }
                                val windowPosition =
                                    pressState.rowCoordinates[0]?.let {
                                        messageBubbleLongPressPositionInWindow(it, position)
                                    } ?: return@longPressOrVerticalDrag
                                val linkLayouts = textLayouts.markdownLinkLayouts.values
                                val linkDestination =
                                    if (deleted) {
                                        null
                                    } else {
                                        markdownLinkDestinationAt(linkLayouts, windowPosition)
                                    }
                                textLayouts.pendingLongPressLinkDestination[0] = linkDestination
                                if (linkDestination == null) {
                                    // Capture the press in window space before
                                    // opening so both the popover and text
                                    // selection seed at the finger (#326, #1370).
                                    pressState.longPressWindowPosition = windowPosition
                                    pressState.longPressTtsStart =
                                        captureTtsLongPressStart(
                                            hit =
                                                if (record.contentTokens.truncated) {
                                                    null
                                                } else {
                                                    renderedTextHitAtWindowPosition(
                                                        textLayouts.selectableTextLayouts.snapshot(),
                                                        windowPosition,
                                                        requireTextLineHit = true,
                                                    )
                                                },
                                            sourceText = ttsSpeakableSource?.text,
                                            controller = controller,
                                            appState = appState,
                                        )
                                    pressState.selectionSeedVisibleOffset =
                                        if (record.contentTokens.truncated) {
                                            null
                                        } else {
                                            textOffsetAtWindowPosition(
                                                textLayouts.selectableTextLayouts.snapshot(),
                                                windowPosition,
                                            )
                                        }
                                    longPressWindowY = windowPosition.y
                                    pressState.actionMenuAnchorBounds = pressState.messageBoundsInWindow[0]
                                    pressState.initiatingMenuHoldActive = true
                                    // Freeze the rendered hit now. The bubble
                                    // can move while its popup is open; an old
                                    // window coordinate must never be re-hit-
                                    // tested against a different line later.
                                    onActionMenuOpenChange(true)
                                }
                            },
                            onLongPressRelease = {
                                pressState.initiatingMenuHoldActive = false
                                val linkDestination = textLayouts.pendingLongPressLinkDestination[0]
                                textLayouts.pendingLongPressLinkDestination[0] = null
                                if (linkDestination != null) copyMarkdownLink(linkDestination)
                            },
                            onDragStart = { position ->
                                pressState.initiatingMenuHoldActive = false
                                if (deleted) return@longPressOrVerticalDrag
                                // A range gesture begins after the threshold
                                // action was shown. Hand ownership over without
                                // leaving a stale popup above selection mode.
                                textLayouts.pendingLongPressLinkDestination[0] = null
                                onActionMenuOpenChange(false)
                                pressState.rowCoordinates[0]
                                    ?.let { messageBubbleLongPressPositionInWindow(it, position).y }
                                    ?.let(onDragSelectionStart)
                            },
                            onDrag = { position ->
                                if (deleted) return@longPressOrVerticalDrag false
                                pressState.rowCoordinates[0]
                                    ?.let { messageBubbleLongPressPositionInWindow(it, position).y }
                                    ?.let(onDragSelection)
                                    ?: false
                            },
                            onDragEnd = {
                                if (!deleted) onDragSelectionEnd()
                            },
                            onGestureCancel = {
                                pressState.initiatingMenuHoldActive = false
                                textLayouts.pendingLongPressLinkDestination[0] = null
                                onActionMenuOpenChange(false)
                                if (!deleted) onDragSelectionCancel()
                            },
                        ),
                    ).messageBubbleSelectionRow(
                        selectionMode = selectionMode,
                        selected = selected,
                    ).then(
                        Modifier.directionalSwipe(
                            owner = swipeOwner,
                            settings = listOf(swipeSettings, swipeRtl),
                            left = leftSwipe != SwipeAction.Off,
                            right = rightSwipe != SwipeAction.Off,
                            onDistance = { distance, direction ->
                                swipeDirection = direction
                                replySwipe.dragTo(distance)
                            },
                            onRelease = { direction -> replySwipe.release { commitSwipe(direction) } },
                            onCancel = replySwipe::cancel,
                        ),
                    ).then(
                        Modifier.observeMessageTextDoubleTap(
                            enabled =
                                !deleted && !selectionMode && !textSelectionMode && (canSpeakAloud || canEditMessage),
                            allowConsumedTapAt = { position ->
                                pressState.rowCoordinates[0]
                                    ?.localToWindow(position)
                                    ?.let { press ->
                                        markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, press)
                                    } == true
                            },
                            onPointerDown = { position ->
                                val pressInWindow = pressState.rowCoordinates[0]?.localToWindow(position)
                                val linkLayouts = textLayouts.markdownLinkLayouts.values
                                val onLink =
                                    pressInWindow != null && markdownHasLinkAnnotationAt(linkLayouts, pressInWindow)
                                recentPressesOnLink[0] = recentPressesOnLink[1]
                                recentPressesOnLink[1] = onLink
                                if (onLink) ttsLinkTapCoordinator.beginPointerActivation()
                            },
                            onPointerFinished = ttsLinkTapCoordinator::endPointerActivation,
                            onDoubleTap = messageTextDoubleTap,
                        ),
                    ).then(
                        // The raw pointerInput above only fires on a physical
                        // pointer long-press, so it leaves accessibility services
                        // (TalkBack, Switch Access) and keyboard/semantic callers
                        // without a way to reach the actions menu — a regression
                        // from the old combinedClickable, which exposed an
                        // onLongClick semantic action for the whole row (#262).
                        // Re-publish that action via Modifier.semantics so the
                        // reply/copy/delete/reaction entry point stays reachable
                        // without a hold gesture. Tombstones expose this only for
                        // their focused local cleanup; batch/text selection still
                        // owns all other row interaction.
                        if (tombstoneCleanupUnavailable || selectionMode || textSelectionMode) {
                            Modifier
                        } else {
                            Modifier.semantics {
                                onLongClick(label = messageActionsLabel) {
                                    // Accessibility entry has no touch point;
                                    // anchor to the bubble top and seed the first word.
                                    pressState.longPressWindowPosition = null
                                    pressState.selectionSeedVisibleOffset = null
                                    longPressWindowY = null
                                    pressState.actionMenuAnchorBounds = pressState.messageBoundsInWindow[0]
                                    if (deleted) requestDelete() else onActionMenuOpenChange(true)
                                    true
                                }
                            }
                        },
                    )
                    // Keep the row transform itself: clipped bounds are not the
                    // row's local origin when a message is partially off-screen.
                    .onGloballyPositioned { pressState.rowCoordinates[0] = it },
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
                MessageSenderAvatarSlot(
                    showSenderAvatar = showSenderAvatar,
                    title = appState.displayName(record.sender),
                    seed = record.sender,
                    pictureUrl = appState.contactAvatarSource(record.sender),
                    picture =
                        if (showSenderAvatar) {
                            rememberDurableAvatar(
                                appState,
                                controller.window.frame
                                    ?.identities
                                    ?.get(record.sender)
                                    ?.avatarAsset,
                            )
                        } else {
                            null
                        },
                    enabled = !textSelectionMode,
                    alignToBubbleBottom = reactionHostPresent,
                    onClick = { appState.presentProfile(appState.npub(record.sender)) },
                )
            }
            Column(
                // Swipe-to-reply translates this whole visual column — bubble or
                // media surface plus its attached reaction summary — as one unit,
                // while the stationary parent Row keeps gesture and hitbox
                // ownership (#204). Applying the offset here, and only here,
                // keeps every bubble variant from double-applying it and keeps
                // the sender avatar and selection gutter at rest. The placement
                // lambda shifts drawing without invalidating measurement, so
                // row alignment and width strategy are unaffected mid-drag.
                modifier =
                    Modifier
                        .widthIn(min = bubbleColumnMinWidth, max = bubbleColumnMaxWidth)
                        .testTag(messageBubbleColumnTestTag(record.messageIdHex))
                        .then(
                            if (reserveSenderAvatarSlot && reactionHostPresent) {
                                Modifier.alignBy(MessageBubbleBottomAlignmentLine)
                            } else {
                                Modifier
                            },
                        ).absoluteOffset { IntOffset((replySwipe.displayedDistance * swipeDirection).roundToInt(), 0) }
                        .onGloballyPositioned {
                            if (replySwipe.atRest) replySwipe.bubbleBoundsInRoot = it.boundsInRoot()
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
                    if (deleted) {
                        // A tombstone keeps only its body, reactions and footer;
                        // the quoted target is part of the removed content.
                        null
                    } else if (item.projected != null) {
                        remember(item, messageTextCopy) {
                            controller.replyPreview(item, messageTextCopy)
                        }
                    } else {
                        controller.replyPreview(item, messageTextCopy)
                    }
                val replyReceivedEmoji =
                    rememberReplyReceivedEmoji(
                        controller.replyTargetMessageId(item).takeUnless { replyPreview?.originalUnavailable != false },
                        controller,
                        appState,
                    )
                val imageAttachments = bubbleMedia.images
                val videoAttachments = bubbleMedia.videos
                val fileAttachments = bubbleMedia.files
                val visualAttachments = bubbleMedia.visuals
                val pendingAudio = bubbleMedia.pendingAudio
                val pendingVisualRefs = bubbleMedia.pendingVisuals
                val anyConfirmedMedia = bubbleMedia.hasConfirmedMedia

                /** Saves the message's accepted media, ignoring taps while a save runs, then reports the outcome. */
                fun saveAttachments() {
                    if (deleted || mediaReferences.isEmpty() || surfaces.attachmentSaveInFlight) return
                    onActionMenuOpenChange(false)
                    surfaces.attachmentSaveInFlight = true
                    appState.launchMutation {
                        try {
                            val summary =
                                saveMessageMediaAttachments(
                                    context = context,
                                    controller = controller,
                                    messageIdHex = record.messageIdHex,
                                    attachments = messageAttachments.accepted,
                                    mine = mine,
                                    documentSaveFallback = documentSaveFallback,
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
                            surfaces.attachmentSaveInFlight = false
                        }
                    }
                }

                /** Shares the message text and media through Android Share, one share at a time. */
                fun shareMessage() {
                    if (!canShareMessage) return
                    onActionMenuOpenChange(false)
                    surfaces.messageShareInFlight = true
                    surfaces.messageShareJob =
                        messageShareScope.launch {
                            try {
                                runCatchingCancellable {
                                    shareMessageExternally(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = record.messageIdHex,
                                        references = mediaReferences,
                                        protocolAttachmentCount = protocolAttachmentCount,
                                        mine = mine,
                                        text = outboundShareText,
                                        chooserTitle = messageShareTitle,
                                    )
                                }.onFailure { appState.presentOutboundShareFailure("MESSAGE_SHARE", it) }
                            } finally {
                                surfaces.messageShareInFlight = false
                                surfaces.messageShareJob = null
                            }
                        }
                }
                // An uncaptioned visual-only message carries the footer over the
                // bottom-right media tile. For an album that is the last visible
                // image/video; a file card or caption owns the footer instead.
                // The prototype keeps time and status below the media, inside the bubble, never overlaid on it.
                val footerOnVisualMedia = false
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
                val captionContact =
                    remember(vcardAttachment, shareBodyText, canRenderSharedContent) {
                        if (vcardAttachment != null && canRenderSharedContent) {
                            parseSharedContactFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                // The vCard itself is the authority once its bytes are on this
                // device, which also covers a raw .vcf sent without the caption.
                // Reading it never starts a download.
                val fileContact =
                    rememberLocalVCardContact(
                        controller = controller,
                        appState = appState,
                        messageIdHex = record.messageIdHex,
                        attachment = vcardAttachment.takeIf { canRenderSharedContent },
                        mine = mine,
                    )
                val sharedContact = fileContact ?: captionContact
                // The card replaces the caption only when the caption is the
                // generated name/phone text. Once the file is local, anything
                // else, such as "Call Ada" above the number, is the sender's own
                // words and stays visible.
                val captionIsContactText =
                    remember(fileContact, captionContact, shareBodyText) {
                        if (fileContact == null) {
                            captionContact != null
                        } else {
                            isContactShareCaption(shareBodyText, fileContact)
                        }
                    }
                // A caption is the message's last line, so it carries the time and
                // delivery state the way a text bubble does; the file card keeps the
                // footer only when there is no caption to carry it. A contact card
                // replaces its .vcf file card, so that file cannot own the footer.
                val confirmedFileFooterInCard =
                    fileCardOwnsFooter(
                        deleted = deleted,
                        fileCount = fileAttachments.size - if (sharedContact != null) 1 else 0,
                        visualOwnsFooter = footerOnVisualMedia,
                        hasCaption = mediaCaption != null,
                    )
                val sharedLocation =
                    remember(shareBodyText, canRenderStructuredShare) {
                        if (canRenderStructuredShare) {
                            parseSharedLocationFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                // A maps link inside prose draws the card with the prose as its
                // caption; only a bare link lets the card replace the text.
                val sharedLocationOwnsBody =
                    remember(shareBodyText, sharedLocation) {
                        sharedLocation != null && isBareLocationShare(shareBodyText)
                    }
                val sharedUser =
                    remember(shareBodyText, canRenderStructuredShare) {
                        if (canRenderStructuredShare) {
                            parseSharedUserFromText(shareBodyText)
                        } else {
                            null
                        }
                    }
                val remoteGiphyMedia =
                    remember(shareBodyText, canRenderStructuredShare) {
                        if (canRenderStructuredShare) RemoteGiphyMedia.parse(shareBodyText) else null
                    }
                val footerOnPendingVisual = false
                val pendingFileFooterInCard =
                    fileCardOwnsFooter(
                        deleted = deleted,
                        fileCount = pendingAttachmentsForRecord.size,
                        visualOwnsFooter = footerOnPendingVisual,
                        hasCaption = mediaCaption != null,
                    ) &&
                        showPendingPlaceholder
                val fileFooterInCard = confirmedFileFooterInCard || pendingFileFooterInCard
                val invalidationWarningPlacement =
                    placeInvalidationWarning(
                        warning = invalidationWarning,
                        fileCardOwnsFooter = confirmedFileFooterInCard,
                    )
                val fileFooterWarning = invalidationWarningPlacement.fileFooter
                val outerInvalidationWarning = invalidationWarningPlacement.outerBubble
                val showOutgoingStatus = shouldShowMessageStatus(mine, deleted, invalidationPresentation)
                // `hasMedia` decides whether this row renders a media card with
                // an optional integrated caption, or stays a text-only bubble.
                // Deleted and persisted-failure tombstones stay text bubbles;
                // convergence-invalidated messages retain local media, and a
                // message whose every attachment MDK rejected still renders its
                // placeholders rather than an empty text bubble.
                val hasMedia =
                    !deleted &&
                        !persistedFailure &&
                        (
                            anyConfirmedMedia ||
                                bubbleMedia.rejected.isNotEmpty() ||
                                pendingAudio.isNotEmpty() ||
                                pendingVisualRefs.isNotEmpty() ||
                                showPendingPlaceholder ||
                                sharedLocation != null ||
                                sharedUser != null ||
                                remoteGiphyMedia != null
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
                    remember(
                        textSelectionMode,
                        selectionMode,
                        displayedBody,
                        record.contentTokens.truncated,
                        onActionMenuOpenChange,
                    ) {
                        {
                            if (!selectionMode && !textSelectionMode) {
                                pressState.longPressWindowPosition = null
                                pressState.selectionSeedVisibleOffset = null
                                longPressWindowY = null
                                pressState.actionMenuAnchorBounds = pressState.messageBoundsInWindow[0]
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
                    visibleTranslation ?: messageBodyTextToRender(
                        displayedBody = displayedBody,
                        deleted = deleted,
                        persistedFailure = persistedFailure,
                        structuredShareOwnsBody =
                            captionIsContactText ||
                                sharedLocationOwnsBody ||
                                sharedUser != null ||
                                remoteGiphyMedia != null,
                        hasPendingMediaName = mediaPendingName != null,
                        hasConfirmedMedia = anyConfirmedMedia,
                        mediaCaption = mediaCaption,
                    )
                val displayedMarkdownDocument =
                    if (visibleTranslation != null) {
                        null
                    } else {
                        rememberMessageMarkdownDocumentForDisplayedBody(
                            messageIdHex = record.messageIdHex,
                            bodyText = bodyTextToRender,
                            recordPlaintext = record.plaintext,
                            storedDocument = record.contentTokens,
                            overrideDocument = editedMarkdownDocument,
                            deleted = deleted,
                            persistedFailure = persistedFailure,
                            fallbackParsingEnabled = record.kind == 9uL,
                            parseMarkdown = parseMarkdown,
                        )
                    }
                SideEffect {
                    val document = displayedMarkdownDocument
                    if (document != null && document.blocks.isNotEmpty() && !isSinglePlainSendParagraph(document)) {
                        composerSendMotion
                            ?.takeIf { it.activePresentationId == item.presentationId }
                            ?.clear("destination-markdown-transformed")
                    }
                }
                val currentSendDocument by rememberUpdatedState(displayedMarkdownDocument)
                val sendSelectableTextLayoutReporter: dev.ipf.whitenoise.android.ui.SelectableTextLayoutReporter =
                    remember(textLayouts, composerSendMotion, item.presentationId) {
                        { key, layout, coordinates ->
                            textLayouts.selectableTextLayoutReporter(key, layout, coordinates)
                            if (composerSendMotion?.activePresentationId == item.presentationId &&
                                layout != null &&
                                coordinates != null
                            ) {
                                if (currentSendDocument?.let(::isSinglePlainSendParagraph) == true) {
                                    composerSendMotion.destinationLayout(item.presentationId, layout)
                                    composerSendMotion.destinationText(item.presentationId, coordinates)
                                }
                            }
                        }
                    }
                val bodyOrWarningInsideBubble =
                    shouldFrameMessageBubbleSupplement(bodyTextToRender, outerInvalidationWarning)
                val soleCaptionedVisual =
                    if (bubbleMedia.hasConfirmedMedia) {
                        bubbleMedia.visuals.singleOrNull()?.value.takeIf {
                            bubbleMedia.audio.isEmpty() && bubbleMedia.files.isEmpty()
                        }
                    } else {
                        bubbleMedia.pendingVisuals.singleOrNull()?.value.takeIf {
                            bubbleMedia.pendingAudio.isEmpty()
                        }
                    }
                val expandCaptionedNarrowVisual =
                    bodyOrWarningInsideBubble &&
                        sharedLocation == null &&
                        sharedContact == null &&
                        sharedUser == null &&
                        remoteGiphyMedia == null &&
                        bubbleMedia.rejected.isEmpty() &&
                        shouldExpandCaptionedPortrait(
                            hasCaption = mediaCaption != null,
                            dim = soleCaptionedVisual?.dim,
                        )
                // The footer's time and delivery glyph are secondary metadata: a quiet
                // gray against the resolved bubble fill, the error pairing for a
                // persisted failure, and the AMOLED directional accent. Media scrim
                // footers stay white on black and do not route through here.
                val timestampColor =
                    messageBubbleFooterColor(
                        mine = mine,
                        persistedFailure = persistedFailure,
                        bubbleBackgroundColor = bubbleBackgroundColor,
                        bubbleContentColor = bubbleContentColor,
                    )
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
                // MarmotKit 0.10.1 flags a message any member reported; the footer says so beside the
                // edited marker, and Message Details lists the reports.
                val reportedLabel =
                    stringResource(R.string.message_reported).takeIf { hasReports && !deleted }
                val footerLabel =
                    listOfNotNull(editedLabel, reportedLabel).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                // A long body collapses to MESSAGE_COLLAPSE_LINE_LIMIT lines
                // with Read More in the bottom footer row opening the full-screen view;
                // tombstones, edit/info copy, and groups with the local collapse
                // setting disabled never collapse (#325, #1180).
                val bodyCanCollapse = (collapseLongMessages || explicitlyCollapsed) && !deleted && !persistedFailure
                val collapsible = bodyCanCollapse && !textSelectionMode && !ttsBodyExpanded

                /**
                 * The sender-name label (group chats only). Rendered above the
                 * shared media card when media is present, or as the first child
                 * of the text-only bubble otherwise.
                 */
                val senderNameLabel: @Composable (insideBubble: Boolean) -> Unit = { insideBubble ->
                    if (showSenderName) {
                        EmojiLabel(
                            appState.displayName(record.sender),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color =
                                if (insideBubble) {
                                    bubbleContentColor
                                } else {
                                    groupAuthorColor(record.sender)
                                },
                            modifier =
                                Modifier.combinedClickable(
                                    enabled = !textSelectionMode,
                                    onClick = { appState.presentProfile(appState.npub(record.sender)) },
                                    onLongClick = {
                                        if (!deleted && !selectionMode && !textSelectionMode) {
                                            pressState.longPressWindowPosition = null
                                            pressState.selectionSeedVisibleOffset = null
                                            longPressWindowY = null
                                            pressState.actionMenuAnchorBounds = pressState.messageBoundsInWindow[0]
                                            onActionMenuOpenChange(true)
                                        }
                                    },
                                ),
                        )
                    }
                }

                /**
                 * The reply quote card. Self-contained (own translucent Surface),
                 * so it renders correctly whether inside the text bubble or
                 * standalone above the media card.
                 */
                val replyPreviewCard: @Composable (insideBubble: Boolean) -> Unit = { insideBubble ->
                    replyPreview?.let { preview ->
                        val pairedReplyAccent = bubbleContentColor.takeIf { insideBubble }
                        val replyAccentArgb =
                            replyPreviewAccentArgb(
                                insideBubble = insideBubble,
                                customBubbleColorActive = customBubbleColorActive,
                                presentation = bubblePresentation,
                            )
                        ReplyPreviewCard(
                            appState = appState,
                            receivedEmoji = replyReceivedEmoji,
                            senderTitle =
                                if (preview.originalUnavailable) {
                                    ""
                                } else {
                                    senderTitleForReply(preview.sender, appState)
                                },
                            isOwn =
                                if (preview.originalUnavailable) {
                                    mine
                                } else {
                                    isOwnReplySender(preview.sender, appState)
                                },
                            body = preview.body,
                            mediaKind = preview.mediaKind,
                            mediaFileName = preview.mediaFileName,
                            mediaType = preview.mediaType,
                            originalUnavailable = preview.originalUnavailable,
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
                            containerColor = replyFillColor.takeIf { insideBubble },
                            contentColor = bubbleContentColor.takeIf { insideBubble },
                            accentColor = replyAccentArgb?.let(::colorFromArgb) ?: pairedReplyAccent,
                            secondaryColor = replySecondaryColor.takeIf { insideBubble },
                            warningColor = replyQuoteColors.warning.takeIf { insideBubble },
                        )
                    }
                }

                /** Records the plain-text leaf's layout and registers it for selection once positioned. */
                val onPlainTextLayout: (TextLayoutResult) -> Unit = {
                    composerSendMotion?.destinationLayout(item.presentationId, it)
                    textLayouts.plainTextLayoutTracker.layoutResult = it
                    reportPlainTextLayoutIfReady()
                }

                /** Wraps the message text in a selection container while text selection mode is active. */
                val selectionWrapper: @Composable (@Composable () -> Unit) -> Unit = { content ->
                    if (textSelectionMode) {
                        val systemReadAloudKeyIds =
                            remember(context) { systemReadAloudProcessTextKeyIds(context) }
                        CompositionLocalProvider(LocalClipboard provides textSelectionClipboard) {
                            SelectionContainer(
                                state = messageTextSelectionState,
                                modifier =
                                    Modifier.appendSpeakAloudTextContextMenuAction(
                                        enabled = canSpeakAloud && !deleted,
                                        label = speakAloudLabel,
                                        systemReadAloudKeyIds = systemReadAloudKeyIds,
                                        onSpeak = {
                                            speakFromHere()
                                            onTextSelectionModeChange(false)
                                        },
                                    ),
                            ) {
                                content()
                            }
                        }
                    } else {
                        content()
                    }
                }
                val onEditedClick: (() -> Unit)? =
                    if (editState != null && !textSelectionMode) {
                        { surfaces.editHistoryOpen = true }
                    } else {
                        null
                    }
                val visiblePoll =
                    item.projected?.poll.takeIf { isPollRecord && !deleted && !persistedFailure }
                // A deleted, failed or expired poll takes its open votes sheet down, as the row used to.
                if (isPollRecord) {
                    val pollVotesHost = LocalPollVotesHost.current
                    val pollGone = visiblePoll == null
                    LaunchedEffect(pollGone, pollOwner, pollVotesHost) {
                        if (pollGone) pollVotesHost?.dismissIf(pollOwner)
                    }
                }
                val pollTimestampColor =
                    if (visiblePoll != null) {
                        messageBubbleFooterColor(
                            mine = mine,
                            persistedFailure = false,
                            bubbleBackgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            bubbleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        timestampColor
                    }
                if (visiblePoll != null) {
                    Column(
                        Modifier.fillMaxWidth().then(actionAnchorBoundsModifier),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        senderNameLabel(false)
                        replyPreviewCard(false)
                        PollMessageContent(
                            item,
                            controller,
                            appState,
                            canVote = !actionsReadOnly && !selectionMode,
                            modifier = Modifier.testTag("poll-message-card"),
                            footer = {
                                Column(horizontalAlignment = Alignment.End) {
                                    invalidationWarning?.let { warning ->
                                        MessageBubbleInvalidationWarning(
                                            warning = warning,
                                            color = pollTimestampColor,
                                            modifier = Modifier.testTag("poll-message-warning"),
                                        )
                                    }
                                    MessageInlineFooter(
                                        timeText = rememberedMessageBubbleTime(record.recordedAt),
                                        color = pollTimestampColor,
                                        showStatus = showOutgoingStatus,
                                        status = item.status,
                                        editedLabel = footerLabel,
                                        onEditedClick = null,
                                        modifier = Modifier.testTag("poll-message-footer"),
                                        statusContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                } else if (hasMedia) {
                    // Media plus caption renders as one message surface. The
                    // media owns no internal corners or border when
                    // a caption is present; the shared frame owns the continuous
                    // outer shape, color, border, and single footer.
                    Column(
                        // The swipe translation lives on the enclosing bubble
                        // column so the reaction summary moves too; applying it
                        // here as well would double-shift media messages.
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        senderNameLabel(false)
                        replyPreviewCard(false)
                        if (bodyOrWarningInsideBubble) {
                            MediaCaptionFrame(
                                modifier = actionAnchorBoundsModifier,
                                presentation = bubblePresentation,
                                highlighted = highlighted,
                                mine = mine,
                                mentionedSelf = mentionedSelf,
                                mentionedYouLabel = mentionedYouLabel,
                                alignEnd = mine,
                                contentModifier = textSelectionBoundsModifier,
                                media = {
                                    LookaheadScope {
                                        Column(
                                            modifier = focusedMediaCaptureModifier,
                                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            BubbleMediaBlocks(
                                                item = item,
                                                record = record,
                                                controller = controller,
                                                appState = appState,
                                                onOpenConversationMedia = onOpenConversationMedia,
                                                bubbleMedia = bubbleMedia,
                                                sharedLocation = sharedLocation,
                                                sharedContact = sharedContact,
                                                sharedUser = sharedUser,
                                                remoteGiphyMedia = remoteGiphyMedia,
                                                giphyEditedLabel = footerLabel,
                                                onGiphyEditedClick = onEditedClick,
                                                deleted = deleted,
                                                mine = mine,
                                                showStatus = showOutgoingStatus,
                                                footerOnVisualMedia = footerOnVisualMedia,
                                                footerOnPendingVisual = footerOnPendingVisual,
                                                showPendingPlaceholder = showPendingPlaceholder,
                                                fileFooterWarning = fileFooterWarning,
                                                onMediaLongPress = onMediaLongPress,
                                                focusedPreview = isActionMenuOpen,
                                                hasCaption = mediaCaption != null,
                                                expandNarrowVisualToStandardWidth = expandCaptionedNarrowVisual,
                                            )
                                        }
                                    }
                                },
                            ) {
                                BubbleBodyFooterAndRetry(
                                    item = item,
                                    record = record,
                                    controller = controller,
                                    appState = appState,
                                    eventCardResolver = eventCardResolver,
                                    bodyText = bodyTextToRender,
                                    bodyMarkdownDocument = displayedMarkdownDocument,
                                    deleted = deleted,
                                    persistedFailure = persistedFailure,
                                    textSelectionMode = textSelectionMode,
                                    customBubbleColorActive = customBubbleColorActive,
                                    selectableTextLayoutReporter = sendSelectableTextLayoutReporter,
                                    markdownLinkLayoutReporter = textLayouts.markdownLinkLayoutReporter,
                                    onCopyMarkdownLink = ::copyMarkdownLink,
                                    deferMarkdownLinkActivation = deferMarkdownLinkActivation,
                                    plainTextSelectionModifier = plainTextSelectionModifier,
                                    onPlainTextLayout = onPlainTextLayout,
                                    ttsLeafHighlightResolver = ttsLeafHighlightResolver,
                                    ttsSentenceActions = ttsSentenceActions,
                                    ttsSentenceLayoutReporter = ttsSentenceLayoutReporter,
                                    ttsMentionPresentation = queuedTtsPresentation?.mentionPresentation,
                                    ttsReadAloudProgress = effectiveTtsReadAloudProgress,
                                    selectionWrapper = selectionWrapper,
                                    collapsible = collapsible,
                                    onCollapseEligibilityChanged = { surfaces.bodyExceedsCollapseLimit = it },
                                    replyPreviewPresent = replyPreview != null,
                                    hasMedia = hasMedia,
                                    bubbleBackgroundColor = bubbleBackgroundColor,
                                    bubbleContentColor = bubbleContentColor,
                                    timestampColor = timestampColor,
                                    statusContainerColor = colorFromArgb(bubblePresentation.backgroundArgb),
                                    showStatus = showOutgoingStatus && !fileFooterInCard && remoteGiphyMedia == null,
                                    editedLabel = footerLabel.takeIf { remoteGiphyMedia == null },
                                    onEditedClick = onEditedClick.takeIf { remoteGiphyMedia == null },
                                    footerOnVisualMedia = footerOnVisualMedia,
                                    footerOnPendingVisual = footerOnPendingVisual,
                                    showTimestamp = !fileFooterInCard && remoteGiphyMedia == null,
                                    invalidationWarning = outerInvalidationWarning,
                                    mine = mine,
                                    onExpand = { if (!deleted) surfaces.openExpandedFullView() },
                                )
                            }
                        } else {
                            MediaCaptionFrame(
                                modifier = actionAnchorBoundsModifier,
                                presentation = bubblePresentation,
                                highlighted = highlighted,
                                mine = mine,
                                mentionedSelf = mentionedSelf,
                                mentionedYouLabel = mentionedYouLabel,
                                alignEnd = mine,
                                media = {
                                    LookaheadScope {
                                        Column(
                                            modifier = focusedMediaCaptureModifier,
                                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            BubbleMediaBlocks(
                                                item = item,
                                                record = record,
                                                controller = controller,
                                                appState = appState,
                                                onOpenConversationMedia = onOpenConversationMedia,
                                                bubbleMedia = bubbleMedia,
                                                sharedLocation = sharedLocation,
                                                sharedContact = sharedContact,
                                                sharedUser = sharedUser,
                                                remoteGiphyMedia = remoteGiphyMedia,
                                                giphyEditedLabel = footerLabel,
                                                onGiphyEditedClick = onEditedClick,
                                                deleted = deleted,
                                                mine = mine,
                                                showStatus = showOutgoingStatus,
                                                footerOnVisualMedia = footerOnVisualMedia,
                                                footerOnPendingVisual = footerOnPendingVisual,
                                                showPendingPlaceholder = showPendingPlaceholder,
                                                fileFooterWarning = fileFooterWarning,
                                                onMediaLongPress = onMediaLongPress,
                                                focusedPreview = isActionMenuOpen,
                                                hasCaption = mediaCaption != null,
                                                expandNarrowVisualToStandardWidth = expandCaptionedNarrowVisual,
                                            )
                                        }
                                    }
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
                                    eventCardResolver = eventCardResolver,
                                    bodyText = bodyTextToRender,
                                    bodyMarkdownDocument = displayedMarkdownDocument,
                                    deleted = deleted,
                                    persistedFailure = persistedFailure,
                                    textSelectionMode = textSelectionMode,
                                    customBubbleColorActive = customBubbleColorActive,
                                    selectableTextLayoutReporter = sendSelectableTextLayoutReporter,
                                    markdownLinkLayoutReporter = textLayouts.markdownLinkLayoutReporter,
                                    onCopyMarkdownLink = ::copyMarkdownLink,
                                    deferMarkdownLinkActivation = deferMarkdownLinkActivation,
                                    plainTextSelectionModifier = plainTextSelectionModifier,
                                    onPlainTextLayout = onPlainTextLayout,
                                    ttsLeafHighlightResolver = ttsLeafHighlightResolver,
                                    ttsSentenceActions = ttsSentenceActions,
                                    ttsSentenceLayoutReporter = ttsSentenceLayoutReporter,
                                    ttsMentionPresentation = queuedTtsPresentation?.mentionPresentation,
                                    ttsReadAloudProgress = effectiveTtsReadAloudProgress,
                                    selectionWrapper = selectionWrapper,
                                    collapsible = collapsible,
                                    onCollapseEligibilityChanged = { surfaces.bodyExceedsCollapseLimit = it },
                                    replyPreviewPresent = replyPreview != null,
                                    hasMedia = hasMedia,
                                    bubbleBackgroundColor = bubbleBackgroundColor,
                                    bubbleContentColor = bubbleContentColor,
                                    timestampColor = timestampColor,
                                    statusContainerColor = colorFromArgb(bubblePresentation.backgroundArgb),
                                    showStatus = showOutgoingStatus && !fileFooterInCard && remoteGiphyMedia == null,
                                    editedLabel = footerLabel.takeIf { remoteGiphyMedia == null },
                                    onEditedClick = onEditedClick.takeIf { remoteGiphyMedia == null },
                                    footerOnVisualMedia = footerOnVisualMedia,
                                    footerOnPendingVisual = footerOnPendingVisual,
                                    showTimestamp = !fileFooterInCard && remoteGiphyMedia == null,
                                    invalidationWarning = outerInvalidationWarning,
                                    mine = mine,
                                    onExpand = { if (!deleted) surfaces.openExpandedFullView() },
                                )
                            }
                        }
                    }
                } else {
                    MessageBubbleFrame(
                        // Swipe-to-reply and long-press live on the parent Row
                        // (see #204) so the whole message row is the hitbox, and
                        // the visual slide driven by swipeDrag lives on the
                        // enclosing bubble column so the reaction summary
                        // translates with this surface.
                        modifier =
                            Modifier
                                .then(textSelectionBoundsModifier)
                                .then(actionAnchorBoundsModifier)
                                .composerSendDestination(
                                    item.presentationId,
                                    composerSendMotion,
                                    bubblePresentation,
                                    mine,
                                ),
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
                        contentModifier =
                            if (replyPreview != null) {
                                Modifier.boundedIntrinsicWidth(IntrinsicSize.Max)
                            } else {
                                Modifier
                            },
                    ) {
                        senderNameLabel(true)
                        replyPreviewCard(true)
                        BubbleBodyFooterAndRetry(
                            item = item,
                            record = record,
                            controller = controller,
                            appState = appState,
                            eventCardResolver = eventCardResolver,
                            bodyText = bodyTextToRender,
                            bodyMarkdownDocument = displayedMarkdownDocument,
                            deleted = deleted,
                            persistedFailure = persistedFailure,
                            textSelectionMode = textSelectionMode,
                            customBubbleColorActive = customBubbleColorActive,
                            selectableTextLayoutReporter = sendSelectableTextLayoutReporter,
                            markdownLinkLayoutReporter = textLayouts.markdownLinkLayoutReporter,
                            onCopyMarkdownLink = ::copyMarkdownLink,
                            deferMarkdownLinkActivation = deferMarkdownLinkActivation,
                            plainTextSelectionModifier = plainTextSelectionModifier,
                            onPlainTextLayout = onPlainTextLayout,
                            ttsLeafHighlightResolver = ttsLeafHighlightResolver,
                            ttsSentenceActions = ttsSentenceActions,
                            ttsSentenceLayoutReporter = ttsSentenceLayoutReporter,
                            ttsMentionPresentation = queuedTtsPresentation?.mentionPresentation,
                            ttsReadAloudProgress = effectiveTtsReadAloudProgress,
                            selectionWrapper = selectionWrapper,
                            collapsible = collapsible,
                            onCollapseEligibilityChanged = { surfaces.bodyExceedsCollapseLimit = it },
                            replyPreviewPresent = replyPreview != null,
                            hasMedia = hasMedia,
                            bubbleBackgroundColor = bubbleBackgroundColor,
                            bubbleContentColor = bubbleContentColor,
                            timestampColor = timestampColor,
                            statusContainerColor = bubbleBackgroundColor,
                            showStatus = shouldShowMessageStatus(mine, deleted, invalidationPresentation),
                            editedLabel = footerLabel,
                            onEditedClick = onEditedClick,
                            footerOnVisualMedia = footerOnVisualMedia,
                            footerOnPendingVisual = footerOnPendingVisual,
                            invalidationWarning = invalidationWarning,
                            mine = mine,
                            onExpand = { if (!deleted) surfaces.openExpandedFullView() },
                        )
                    }
                }
                MessageActionMenu(
                    // A tombstone gets a focused delete-only menu. Batch or partial
                    // text selection still owns the row interaction completely.
                    expanded = isActionMenuOpen && !selectionMode && !textSelectionMode,
                    initiatingHoldActive = pressState.initiatingMenuHoldActive,
                    anchorBoundsInWindow = pressState.actionMenuAnchorBounds,
                    anchorWindowYPx = longPressWindowY,
                    canReply = !deleted && !actionsReadOnly,
                    canReact = !deleted && !actionsReadOnly,
                    canDelete = deleteCapability.canDeleteAtAll,
                    canEdit = canEditMessage,
                    canForward = !deleted && !actionsReadOnly && forwardPayload != null,
                    forwardBlockedReason =
                        if (!isPollRecord && !deleted && !actionsReadOnly) {
                            (forwardEligibility as? ForwardEligibility.Blocked)?.reason
                        } else {
                            null
                        },
                    canSelect = !deleted && !actionsReadOnly && batchSelectable,
                    // Whole-message Copy keeps using its actual clipboard payload,
                    // including card-style bubbles whose body is rendered by the
                    // card rather than the text renderer — a poll card copies its
                    // question and options. Partial selection is only available
                    // when this bubble has selectable rendered text.
                    canCopyText =
                        messageBubbleCanCopyText(item, deleted, persistedFailure, displayedBody, messageTextCopy),
                    // Speak aloud uses the same edit-aware user-authored text as TTS
                    // projection, not the display fallback (filenames, placeholders,
                    // reactions, system copy).
                    canShowLess =
                        record.kind == 9uL &&
                            item.status != MessageStatus.Failed &&
                            !deleted &&
                            !persistedFailure &&
                            !collapsible &&
                            !ttsBodyExpanded &&
                            surfaces.bodyExceedsCollapseLimit,
                    onShowLess = {
                        onActionMenuOpenChange(false)
                        onBeforeBodyCollapse()
                        explicitlyCollapsed = true
                    },
                    canTranslate = translationSource != null,
                    onTranslate = {
                        onActionMenuOpenChange(false)
                        translationDialogOpen = true
                    },
                    canSpeak = !deleted && canSpeakAloud,
                    canSpeakCodeLiterally = speakableProjection?.speechRoles?.isNotEmpty() == true,
                    canSelectText = !isPollRecord && !deleted && !bodyTextToRender.isNullOrBlank(),
                    canKeepOnScreen =
                        !isPollRecord &&
                            keptMessages != null &&
                            keptMessageKey != null &&
                            !deleted &&
                            !keptMessages.isKept(keptMessageKey),
                    canShare = canShareMessage,
                    canSave = !deleted && mediaReferences.isNotEmpty() && !surfaces.attachmentSaveInFlight,
                    canInfo = !deleted,
                    canReport =
                        canReportMessage(
                            mine = mine,
                            deleted = deleted,
                            directConversation = controller.isDirectConversation,
                        ),
                    quickReactionEmojis = quickReactionEmojis,
                    onDismissRequest = { onActionMenuOpenChange(false) },
                    onReactionOrigin = localReactionMotion::choose,
                    onReact = { emoji ->
                        if (!deleted && !actionsReadOnly) {
                            onActionMenuOpenChange(false)
                            onEmojiUsed(emoji)
                            reactWithEmoji(emoji)
                        }
                    },
                    onKeepOnScreen = {
                        onActionMenuOpenChange(false)
                        if (keptMessageKey != null) keptMessages?.keep(keptMessageKey)
                    },
                    onOpenEmojiPicker = {
                        if (!deleted && !actionsReadOnly) {
                            onActionMenuOpenChange(false)
                            surfaces.emojiPickerOpen = true
                        }
                    },
                    onReply = ::beginReply,
                    onEdit = ::beginEdit,
                    onCopyText = ::copyMessageText,
                    onSpeak = {
                        speakFromHere()
                        onActionMenuOpenChange(false)
                    },
                    onSpeakCodeLiterally = {
                        startSpeakAloud(literalCode = true)
                        onActionMenuOpenChange(false)
                    },
                    onShare = ::shareMessage,
                    onSave = ::saveAttachments,
                    onSelectText = ::beginTextSelection,
                    onForward = ::beginForward,
                    onSelect = {
                        if (!deleted && !actionsReadOnly) {
                            onActionMenuOpenChange(false)
                            onToggleSelection()
                        }
                    },
                    onInfo = ::openInfoSheet,
                    onReport = {
                        onActionMenuOpenChange(false)
                        surfaces.reportSheetOpen = true
                    },
                    onDelete = ::requestDelete,
                    mine = mine,
                    selectedReactionEmojis = reactionTallies.filter { it.mine }.mapTo(mutableSetOf()) { it.emoji },
                    previewDescription =
                        if (isActionMenuOpen) {
                            focusedMessagePreviewDescription(
                                author = appState.displayName(record.sender),
                                body =
                                    focusedMessagePreviewText(
                                        displayedBody,
                                        displayedMarkdownDocument,
                                        appState::mentionDisplayName,
                                    ).text,
                                mediaLabels =
                                    if (hasMedia) {
                                        mediaReferences.map { it.fileName }.filter(String::isNotBlank).ifEmpty {
                                            listOf(
                                                mediaPendingName?.takeIf(String::isNotBlank)
                                                    ?: stringResource(R.string.media_attachment),
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    },
                                time = rememberedMessageBubbleTime(record.recordedAt),
                                status =
                                    if (showOutgoingStatus) {
                                        when (item.status) {
                                            MessageStatus.Pending -> stringResource(R.string.sending)
                                            MessageStatus.Sent -> stringResource(R.string.sent)
                                            MessageStatus.Failed -> stringResource(R.string.send_failed)
                                            else -> null
                                        }
                                    } else {
                                        null
                                    },
                                warning = invalidationWarning,
                            )
                        } else {
                            ""
                        },
                    previewReady = !hasMedia || focusedMediaReady,
                    preview = {
                        val previewFooter: @Composable () -> Unit = {
                            MessageInlineFooter(
                                timeText = rememberedMessageBubbleTime(record.recordedAt),
                                color = if (visiblePoll != null) pollTimestampColor else timestampColor,
                                showStatus = showOutgoingStatus && !fileFooterInCard && remoteGiphyMedia == null,
                                status = item.status,
                                editedLabel = footerLabel.takeIf { remoteGiphyMedia == null },
                                onEditedClick = null,
                                showTime = !fileFooterInCard && remoteGiphyMedia == null,
                                modifier =
                                    if (visiblePoll != null) Modifier.testTag("poll-preview-footer") else Modifier,
                                statusContainerColor =
                                    if (visiblePoll != null) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        colorFromArgb(bubblePresentation.backgroundArgb)
                                    },
                            )
                        }
                        val mediaPreview: (@Composable () -> Unit)? =
                            if (visiblePoll != null) {
                                {
                                    Box(Modifier.testTag("poll-preview-card")) {
                                        PollCard(visiblePoll, canVote = false, onVote = {}, footer = previewFooter)
                                    }
                                }
                            } else if (hasMedia) {
                                {
                                    FocusedRenderedMessagePreview(
                                        layer = checkNotNull(focusedMessageLayer),
                                        sourceSize = pressState.focusedMediaSize,
                                    )
                                }
                            } else {
                                null
                            }
                        if (visiblePoll != null || (hasMedia && !bodyOrWarningInsideBubble)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                replyPreview?.let { preview ->
                                    ReplyPreviewCard(
                                        appState = appState,
                                        receivedEmoji = replyReceivedEmoji,
                                        senderTitle =
                                            if (preview.originalUnavailable) {
                                                ""
                                            } else {
                                                senderTitleForReply(preview.sender, appState)
                                            },
                                        isOwn =
                                            if (preview.originalUnavailable) {
                                                mine
                                            } else {
                                                isOwnReplySender(preview.sender, appState)
                                            },
                                        body = preview.body,
                                        mediaKind = preview.mediaKind,
                                        mediaFileName = preview.mediaFileName,
                                        mediaType = preview.mediaType,
                                        originalUnavailable = preview.originalUnavailable,
                                        warning = preview.warning,
                                        onClick = null,
                                        onDismiss = null,
                                        fillWidth = true,
                                        mentionDisplayName = appState::mentionDisplayName,
                                        containerColor = null,
                                        contentColor = null,
                                        accentColor =
                                            replyPreviewAccentArgb(
                                                insideBubble = false,
                                                customBubbleColorActive = customBubbleColorActive,
                                                presentation = bubblePresentation,
                                            )?.let(::colorFromArgb),
                                    )
                                }
                                mediaPreview?.invoke()
                                if (visiblePoll == null) {
                                    if (!footerOnVisualMedia && !footerOnPendingVisual && remoteGiphyMedia == null) {
                                        previewFooter()
                                    }
                                }
                            }
                        } else {
                            FocusedTextMessagePreview(
                                presentation = bubblePresentation,
                                mine = mine,
                                text = bodyTextToRender ?: displayedBody.takeUnless { hasMedia }.orEmpty(),
                                document = displayedMarkdownDocument,
                                time = rememberedMessageBubbleTime(record.recordedAt),
                                status = item.status,
                                showStatus = showOutgoingStatus,
                                senderName = appState.displayName(record.sender).takeIf { showSenderName },
                                mentionDisplayName = appState::mentionDisplayName,
                                media = mediaPreview,
                                footerContent = previewFooter,
                                warning = outerInvalidationWarning,
                                editedLabel = footerLabel,
                                mentionedSelf = mentionedSelf,
                                mentionedYouLabel = mentionedYouLabel,
                                reply =
                                    replyPreview?.let { preview ->
                                        {
                                            ReplyPreviewCard(
                                                appState = appState,
                                                receivedEmoji = replyReceivedEmoji,
                                                senderTitle =
                                                    if (preview.originalUnavailable) {
                                                        ""
                                                    } else {
                                                        senderTitleForReply(preview.sender, appState)
                                                    },
                                                isOwn =
                                                    if (preview.originalUnavailable) {
                                                        mine
                                                    } else {
                                                        isOwnReplySender(preview.sender, appState)
                                                    },
                                                body = preview.body,
                                                mediaKind = preview.mediaKind,
                                                mediaFileName = preview.mediaFileName,
                                                mediaType = preview.mediaType,
                                                originalUnavailable = preview.originalUnavailable,
                                                warning = preview.warning,
                                                onClick = null,
                                                onDismiss = null,
                                                fillWidth = true,
                                                mentionDisplayName = appState::mentionDisplayName,
                                                containerColor = replyFillColor.takeUnless { hasMedia },
                                                contentColor = bubbleContentColor.takeUnless { hasMedia },
                                                accentColor =
                                                    replyPreviewAccentArgb(
                                                        insideBubble = !hasMedia,
                                                        customBubbleColorActive = customBubbleColorActive,
                                                        presentation = bubblePresentation,
                                                    )?.let(::colorFromArgb)
                                                        ?: bubbleContentColor.takeUnless { hasMedia },
                                                secondaryColor = replySecondaryColor.takeUnless { hasMedia },
                                                warningColor = replyQuoteColors.warning.takeUnless { hasMedia },
                                            )
                                        }
                                    },
                            )
                        }
                    },
                )
                if (surfaces.expandedFullView && !deleted) {
                    val readerSession = surfaces.expandedFullViewSession
                    ReaderSendDismissal(surfaces, onReaderSendAccepted)
                    val groupIdHex = controller.group.groupIdHex
                    val editingRecord =
                        controller.editingMessageId?.let { id ->
                            controller.timeline.firstOrNull { it.record.messageIdHex == id }?.record
                        }
                    val canUseExpandedComposer = !deleted && !actionsReadOnly && composerGate == ComposerGate.COMPOSER
                    val expandedBody = bodyTextToRender ?: displayedBody
                    MessageFullScreenView(
                        senderDisplayName = appState.displayName(record.sender),
                        senderSeed = record.sender,
                        senderAvatarUrl = appState.contactAvatarSource(record.sender),
                        body = expandedBody,
                        bodyMarkdownDocument = displayedMarkdownDocument,
                        mentionDisplayName =
                            remember(appState) {
                                { bech32: String -> appState.mentionDisplayName(bech32) }
                            },
                        onNostrProfileTap =
                            remember(appState) {
                                { bech32: String -> appState.presentNostrProfile(bech32) }
                            },
                        onCopyMarkdownLink = ::copyMarkdownLink,
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
                                surfaces.expandedFullView = false
                                surfaces.emojiPickerOpen = true
                            }
                        },
                        onShowLess =
                            if (
                                item.status == MessageStatus.Failed ||
                                ttsBodyExpanded ||
                                !surfaces.bodyExceedsCollapseLimit
                            ) {
                                null
                            } else {
                                {
                                    onBeforeBodyCollapse()
                                    explicitlyCollapsed = true
                                    surfaces.expandedFullView = false
                                }
                            },
                        onCopy = ::copyMessageText,
                        onDelete = {
                            // Close the full view before opening the shared
                            // delete surface (same handoff the reaction picker
                            // uses) so the sheet never renders behind the
                            // full-screen dialog window.
                            surfaces.expandedFullView = false
                            requestDelete()
                        },
                        onDismiss = { surfaces.expandedFullView = false },
                        bottomBar = {
                            when (composerGate) {
                                ComposerGate.PENDING ->
                                    if (controller.inviteAcceptanceResolutionPending) {
                                        InviteAcceptanceResolutionStatus(
                                            state = controller.inviteAcceptanceResolutionState,
                                            onRetry = {
                                                appState.launchMutation {
                                                    controller.retryInviteAcceptanceAuthority()
                                                }
                                            },
                                        )
                                    } else {
                                        Spacer(
                                            Modifier
                                                .fillMaxWidth()
                                                .navigationBarsPadding()
                                                .imePadding()
                                                .height(64.dp),
                                        )
                                    }
                                ComposerGate.NOTICE -> RemovedMemberComposerNotice()
                                ComposerGate.BLOCKED ->
                                    BlockedDmComposerNotice(
                                        unblockInFlight = blockedDmUnblockInFlight,
                                        onUnblock = onUnblockBlockedDm,
                                    )
                                ComposerGate.FROZEN -> FrozenGroupComposerNotice()
                                ComposerGate.DISBANDED -> DisbandedGroupComposerNotice(disbanded = groupDisbanded)
                                ComposerGate.INVITE ->
                                    InvitationActions(
                                        inviterName = controller.inviteAccount?.let { appState.chatMemberTitle(it) },
                                        mutationInFlight = inviteMutationInFlight,
                                        onAccept = onJoinInvite,
                                        onDecline = onDeclineInvite,
                                    )
                                ComposerGate.COMPOSER ->
                                    if (!actionsReadOnly) {
                                        CompositionLocalProvider(
                                            LocalComposerSendMotion provides null,
                                        ) {
                                            ComposerBar(
                                                replyingToEmoji =
                                                    rememberReplyReceivedEmoji(
                                                        controller.replyingTo?.messageIdHex,
                                                        controller,
                                                        appState,
                                                    ),
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
                                                onSend = { text, onAccepted ->
                                                    appState.launchMutation {
                                                        appState.sendConversationText(
                                                            controller,
                                                            text,
                                                            onAccepted = onAccepted,
                                                        )
                                                    }
                                                },
                                                initialDraft =
                                                    appState
                                                        .draftSnapshotFor(controller.boundAccountRef, groupIdHex)
                                                        ?.textFieldValue
                                                        ?: TextFieldValue(""),
                                                onDraftChange = {
                                                    appState.setDraft(controller.boundAccountRef, groupIdHex, it)
                                                },
                                                draftKey =
                                                    composerDraftOwnerKey(controller.boundAccountRef, groupIdHex),
                                                draftAccountRef = controller.boundAccountRef,
                                                draftGroupIdHex = groupIdHex,
                                                textState = composerTextState,
                                                editingMessageId = controller.editingMessageId,
                                                editingInitialText =
                                                    editingRecord?.let { controller.displayedText(it) },
                                                onCancelEdit = controller::cancelMessageEdit,
                                                appState = appState,
                                                mentionCandidates = mentionCandidates,
                                                mentionPickerEnabled = mentionPickerEnabled,
                                                enterKeyBehavior = appState.enterKeyBehavior,
                                                recentEmojis = recentEmojis,
                                                onEmojiUsed = onEmojiUsed,
                                                onAfterSend = { surfaces.finishExpandedReaderSend(readerSession) },
                                            )
                                        }
                                    }
                            }
                        },
                    )
                }
                if (surfaces.emojiPickerOpen && !deleted && !actionsReadOnly) {
                    val pickingSlot = surfaces.configureReactionSlot
                    EmojiPickerSheet(
                        purpose =
                            if (pickingSlot == null) {
                                EmojiPickerPurpose.USE
                            } else {
                                EmojiPickerPurpose.CONFIGURE_QUICK_REACTION
                            },
                        recentEmojis = recentEmojis,
                        onEmojiUsed = onEmojiUsed,
                        onDismissRequest = {
                            surfaces.emojiPickerOpen = false
                            surfaces.configureReactionSlot = null
                        },
                        onEmojiPicked = { emoji ->
                            surfaces.emojiPickerOpen = false
                            if (pickingSlot == null) {
                                reactWithEmoji(emoji)
                            } else {
                                surfaces.pickConfigureReaction(pickingSlot, emoji)
                                surfaces.configureReactionSlot = null
                                surfaces.customizeReactionsOpen = true
                            }
                        },
                        onConfigure =
                            if (pickingSlot != null) {
                                null
                            } else {
                                {
                                    surfaces.configureReactionsDraft = quickReactionEmojis
                                    surfaces.emojiPickerOpen = false
                                    surfaces.customizeReactionsOpen = true
                                }
                            },
                    )
                }
                if (surfaces.customizeReactionsOpen && !deleted) {
                    ConfigureReactionsSheet(
                        current = surfaces.configureReactionsDraft,
                        onDismiss = { surfaces.customizeReactionsOpen = false },
                        onApply = { choices ->
                            onQuickReactionsSave(choices)
                            surfaces.customizeReactionsOpen = false
                        },
                        onPickSlot = { index, draft ->
                            surfaces.configureReactionsDraft = draft
                            surfaces.configureReactionSlot = index
                            surfaces.customizeReactionsOpen = false
                            surfaces.emojiPickerOpen = true
                        },
                    )
                }
                if (surfaces.editHistoryOpen && !deleted && editState != null) {
                    EditHistoryDialog(
                        // With an engine edit summary the record's plaintext is already the edited body.
                        original = record.plaintext.takeIf { item.projected?.edit == null },
                        originalTimestamp = record.recordedAt,
                        editState = editState,
                        onDismissRequest = { surfaces.editHistoryOpen = false },
                        loadAuthoritativeHistory = { controller.authoritativeEditHistory(record.messageIdHex) },
                    )
                }
                if (surfaces.reportSheetOpen) {
                    ReportMessageSheet(
                        onDismissRequest = { surfaces.reportSheetOpen = false },
                        sending = surfaces.reportInFlight,
                        onSubmit = { reason, explanation ->
                            surfaces.reportInFlight = true
                            appState.launchMutation {
                                try {
                                    val outcome = controller.reportMessage(record.messageIdHex, reason, explanation)
                                    surfaces.reportSheetOpen = false
                                    when (outcome) {
                                        ReportOutcome.Sent -> appState.present(R.string.report_message_sent)
                                        ReportOutcome.Queued -> appState.present(R.string.report_message_queued)
                                        ReportOutcome.NotAllowed ->
                                            appState.present(R.string.report_message_not_allowed)
                                        // The controller already presented the failure with its report.
                                        ReportOutcome.Failed -> Unit
                                    }
                                } finally {
                                    surfaces.reportInFlight = false
                                }
                            }
                        },
                    )
                }
                if (surfaces.infoSheetOpen && !deleted) {
                    LaunchedEffect(hasReports, surfaces.reportsRevision) {
                        surfaces.messageReports = if (hasReports) controller.reportsFor(record.messageIdHex) else null
                    }
                    MessageDetailsScreen(
                        record = record,
                        reports = surfaces.messageReports?.takeIf { hasReports },
                        reporterName = appState::displayName,
                        canDismissReports = controller.isSelfAdmin && !controller.isDirectConversation,
                        onDismissReport = { report ->
                            appState.launchMutation {
                                when (controller.dismissReports(listOf(report.reportIdHex), "")) {
                                    ReportOutcome.Sent, ReportOutcome.Queued -> {
                                        appState.present(R.string.report_dismissed)
                                        surfaces.reportsRevision += 1
                                    }
                                    ReportOutcome.NotAllowed -> appState.present(R.string.report_dismiss_failed)
                                    // The controller already presented the failure with its report.
                                    ReportOutcome.Failed -> Unit
                                }
                            }
                        },
                        status = item.status,
                        mine = mine,
                        senderDisplayName = appState.displayName(record.sender),
                        senderNpub = appState.npubForDisplay(record.sender),
                        senderAvatarUrl = appState.contactAvatarSource(record.sender),
                        reactions = controller.reactions[record.messageIdHex].orEmpty(),
                        recipients = messageDetailsRecipients(controller, appState, mine),
                        attachmentLabels = mediaReferences.map { it.fileName.ifBlank { it.mediaType } },
                        onDismissRequest = { surfaces.infoSheetOpen = false },
                        onCopy = { value ->
                            clipboard.setText(AnnotatedString(value))
                        },
                    )
                }
                val openForwardPayload = forwardPayload
                if (surfaces.forwardSheetOpen && !deleted && openForwardPayload != null) {
                    ForwardMessageSheet(
                        appState = appState,
                        payloads = listOf(openForwardPayload),
                        sourceAccountRef = controller.boundAccountRef,
                        originGroupIdHex = record.groupIdHex,
                        onDismiss = { surfaces.forwardSheetOpen = false },
                    )
                }
                if (surfaces.deleteDialogOpen) {
                    MessageDeleteDialog(
                        capability = deleteCapability,
                        mine = mine,
                        senderDisplayName = appState.displayName(record.sender),
                        deleteInFlight = surfaces.deleteInFlight,
                        onDeleteForEveryone = ::performDeleteForEveryone,
                        onDeleteForMe = ::performDeleteForMe,
                        onDismissRequest = { surfaces.deleteDialogOpen = false },
                    )
                }
                // Keep the host composed through its exit; deleted messages target an empty summary.
                MessageReactionSummary(
                    tallies = reactionTallies,
                    mine = mine,
                    visibilityState = reactionVisibilityState,
                    localMotion = localReactionMotion,
                    enabled = !deleted,
                    onClick = {
                        if (!deleted) {
                            surfaces.reactionSheetOpen = true
                        }
                    },
                )
                dev.ipf.whitenoise.android.translation
                    .MessageTranslationRendition(translationSource)
                if (surfaces.reactionSheetOpen && !deleted) {
                    CompleteReactionDetailsSheet(
                        item = item,
                        controller = controller,
                        appState = appState,
                        onRemoveOwnReaction = if (actionsReadOnly || deleted) null else ::reactWithEmoji,
                        onDismissRequest = { surfaces.reactionSheetOpen = false },
                    )
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
