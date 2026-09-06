package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.messageContainsOnlyNostrEventReferences
import dev.ipf.whitenoise.android.core.nostrEventReferences
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.TtsSentenceLayoutReporter
import dev.ipf.whitenoise.android.ui.common.rememberedMessageBubbleTime
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaImageBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPendingPlaceholder
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVideoBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVisualGridBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVoiceBubble
import dev.ipf.whitenoise.android.ui.conversation.media.VoicePresentationAttachmentKey
import dev.ipf.whitenoise.android.ui.conversation.media.rememberVoicePresentationOwner
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCards
import dev.ipf.whitenoise.android.ui.conversation.share.ContactMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.LocationMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.SharedLocation
import dev.ipf.whitenoise.android.ui.conversation.share.SharedUser
import dev.ipf.whitenoise.android.ui.conversation.share.UserMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.formatCoordinate
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlin.math.ceil

internal fun ttsBodyIsCollapsed(
    collapseEnabled: Boolean,
    measuredBodyHeightPx: Int?,
    maxBodyHeightPx: Int,
): Boolean = collapseEnabled && (measuredBodyHeightPx == null || measuredBodyHeightPx > maxBodyHeightPx)

internal fun fileCardOwnsFooter(
    deleted: Boolean,
    fileCount: Int,
    visualOwnsFooter: Boolean,
): Boolean = !deleted && fileCount > 0 && !visualOwnsFooter

internal fun visualMediaOwnsFooter(
    deleted: Boolean,
    hasInvalidationWarning: Boolean,
    visualCount: Int,
    hasCaption: Boolean,
): Boolean = !deleted && !hasInvalidationWarning && visualCount > 0 && !hasCaption

@Composable
@Suppress("FunctionNaming") // Compose UI entry point.
internal fun VisualMediaFooterFrame(
    showFooter: Boolean,
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
    retention: RetentionIndicatorInput?,
    reserveRetentionSpace: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Box {
        content()
        if (showFooter) {
            MediaFooterOverlay(
                timeText = timeText,
                showStatus = showStatus,
                status = status,
                retention = retention,
                reserveRetentionSpace = reserveRetentionSpace,
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ColumnScope.BubbleMediaBlocks(
    item: TimelineMessage,
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    conversationVisualPages: List<MediaViewerPage>,
    bubbleMedia: BubbleMedia,
    sharedLocation: SharedLocation?,
    sharedContact: SharedContact?,
    sharedUser: SharedUser?,
    deleted: Boolean,
    mine: Boolean,
    showStatus: Boolean,
    footerOnVisualMedia: Boolean,
    footerOnPendingVisual: Boolean,
    showPendingPlaceholder: Boolean,
    onMediaLongPress: () -> Unit,
    attachedToCaption: Boolean,
) {
    val retentionInput =
        record.retentionIndicatorInput(
            controllerKey = controller,
            accountRef = controller.boundAccountRef,
            deleted = deleted,
            retentionAtSendSeconds = item.retentionAtSendSeconds,
        )
    val reserveRetentionSpace =
        !deleted &&
            shouldReserveRetentionIndicatorSpace(
                input = retentionInput,
                projectedRetentionSeconds = record.retentionSeconds,
                mine = mine,
                status = item.status,
                groupRetentionSeconds = controller.group.disappearingMessageSecs,
            )
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
    if (!deleted && bubbleMedia.visuals.isNotEmpty()) {
        if (bubbleMedia.visuals.size == 1) {
            val entry = bubbleMedia.visuals.first()
            VisualMediaFooterFrame(
                showFooter = footerOnVisualMedia,
                timeText = rememberedMessageBubbleTime(record.recordedAt),
                showStatus = mine,
                status = item.status,
                retention = retentionInput,
                reserveRetentionSpace = reserveRetentionSpace,
            ) {
                if (MediaReferenceSupport.isVideoMedia(entry.value)) {
                    MediaVideoBubble(
                        item = item,
                        attachmentIndex = entry.index,
                        reference = entry.value,
                        mine = mine,
                        controller = controller,
                        appState = appState,
                        conversationVisualPages = conversationVisualPages,
                        onLongPress = onMediaLongPress,
                        attachedToCaption = attachedToCaption,
                    )
                } else {
                    MediaImageBubble(
                        item = item,
                        reference = entry.value,
                        attachmentIndex = entry.index,
                        controller = controller,
                        appState = appState,
                        conversationVisualPages = conversationVisualPages,
                        mine = mine,
                        onLongPress = onMediaLongPress,
                        attachedToCaption = attachedToCaption,
                    )
                }
            }
        } else {
            VisualMediaFooterFrame(
                showFooter = footerOnVisualMedia,
                timeText = rememberedMessageBubbleTime(record.recordedAt),
                showStatus = mine,
                status = item.status,
                retention = retentionInput,
                reserveRetentionSpace = reserveRetentionSpace,
            ) {
                MediaVisualGridBubble(
                    item = item,
                    attachments = bubbleMedia.visuals,
                    controller = controller,
                    appState = appState,
                    conversationVisualPages = conversationVisualPages,
                    mine = mine,
                    onLongPress = onMediaLongPress,
                    attachedToCaption = attachedToCaption,
                )
            }
        }
    }
    if (!deleted && bubbleMedia.audio.isNotEmpty()) {
        bubbleMedia.audio.forEach { entry ->
            val presentationOwner = rememberVoicePresentationOwner(controller, appState)
            val attachmentKey =
                VoicePresentationAttachmentKey(record.messageIdHex, entry.index, entry.value.sourceEpoch)
            key(presentationOwner, attachmentKey) {
                MediaVoiceBubble(
                    messageIdHex = record.messageIdHex,
                    attachmentIndex = entry.index,
                    reference = entry.value,
                    mine = mine,
                    controller = controller,
                    appState = appState,
                    presentationOwner = presentationOwner,
                    onLongPress = onMediaLongPress,
                    attachedToCaption = attachedToCaption,
                )
            }
        }
    }
    if (!deleted && bubbleMedia.files.isNotEmpty()) {
        val fileOwnsFooter =
            fileCardOwnsFooter(
                deleted = deleted,
                fileCount = bubbleMedia.files.size,
                visualOwnsFooter = footerOnVisualMedia,
            )
        val fileTimestamp = if (fileOwnsFooter) rememberedMessageBubbleTime(record.recordedAt) else null
        bubbleMedia.files.forEachIndexed { filePosition, entry ->
            val isFooterOwner = fileOwnsFooter && filePosition == bubbleMedia.files.lastIndex
            MediaFileBubble(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                mine = mine,
                controller = controller,
                appState = appState,
                senderKey = record.sender,
                senderDisplayName = appState.displayName(record.sender),
                onLongPress = onMediaLongPress,
                attachedToCaption = attachedToCaption,
                timestampText = fileTimestamp.takeIf { isFooterOwner },
                showStatus = isFooterOwner && showStatus,
                status = item.status,
                retention = retentionInput.takeIf { isFooterOwner },
                reserveRetentionSpace = isFooterOwner && reserveRetentionSpace,
            )
        }
    }
    if (!deleted && !bubbleMedia.hasConfirmedMedia && bubbleMedia.pendingAudio.isNotEmpty()) {
        bubbleMedia.pendingAudio.forEach { (index, pending) ->
            val presentationOwner = rememberVoicePresentationOwner(controller, appState)
            val attachmentKey = VoicePresentationAttachmentKey(record.messageIdHex, index, 0uL)
            key(presentationOwner, attachmentKey) {
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
                                version = EncryptedMediaVersionFfi.V1,
                                sourceEpoch = 0uL,
                                dim = null,
                                thumbhash = null,
                            )
                        },
                    mine = true,
                    controller = controller,
                    appState = appState,
                    presentationOwner = presentationOwner,
                    onLongPress = onMediaLongPress,
                    attachedToCaption = attachedToCaption,
                )
            }
        }
    }
    if (!deleted && !bubbleMedia.hasConfirmedMedia && bubbleMedia.pendingVisuals.isNotEmpty()) {
        val uploadFailed = item.status == MessageStatus.Failed
        val retryUpload: () -> Unit = {
            appState.launchMutation { controller.retryFailedSend(item) }
        }
        if (bubbleMedia.pendingVisuals.size == 1) {
            val entry = bubbleMedia.pendingVisuals.first()
            VisualMediaFooterFrame(
                showFooter = footerOnPendingVisual,
                timeText = rememberedMessageBubbleTime(record.recordedAt),
                showStatus = true,
                status = item.status,
                retention = retentionInput,
                reserveRetentionSpace = reserveRetentionSpace,
            ) {
                if (MediaReferenceSupport.isVideoMedia(entry.value)) {
                    MediaVideoBubble(
                        item = item,
                        attachmentIndex = entry.index,
                        reference = entry.value,
                        mine = true,
                        controller = controller,
                        appState = appState,
                        conversationVisualPages = conversationVisualPages,
                        onLongPress = onMediaLongPress,
                        uploading = !uploadFailed,
                        uploadFailed = uploadFailed,
                        onRetryUpload = if (uploadFailed) retryUpload else null,
                        attachedToCaption = attachedToCaption,
                    )
                } else {
                    MediaImageBubble(
                        item = item,
                        reference = entry.value,
                        attachmentIndex = entry.index,
                        controller = controller,
                        appState = appState,
                        conversationVisualPages = conversationVisualPages,
                        mine = true,
                        onLongPress = onMediaLongPress,
                        uploading = !uploadFailed,
                        attachedToCaption = attachedToCaption,
                    )
                }
            }
        } else {
            VisualMediaFooterFrame(
                showFooter = footerOnPendingVisual,
                timeText = rememberedMessageBubbleTime(record.recordedAt),
                showStatus = true,
                status = item.status,
                retention = retentionInput,
                reserveRetentionSpace = reserveRetentionSpace,
            ) {
                MediaVisualGridBubble(
                    item = item,
                    attachments = bubbleMedia.pendingVisuals,
                    controller = controller,
                    appState = appState,
                    conversationVisualPages = conversationVisualPages,
                    mine = true,
                    onLongPress = onMediaLongPress,
                    uploading = !uploadFailed,
                    attachedToCaption = attachedToCaption,
                )
            }
        }
    }
    if (showPendingPlaceholder) {
        val pendingFileOwnsFooter =
            fileCardOwnsFooter(
                deleted = deleted,
                fileCount = controller.pendingAttachmentsList(record.messageIdHex).size,
                visualOwnsFooter = footerOnPendingVisual,
            )
        MediaPendingPlaceholder(
            pendingAttachments = controller.pendingAttachmentsList(record.messageIdHex),
            failed = item.status == MessageStatus.Failed,
            attachedToCaption = attachedToCaption,
            timestampText = rememberedMessageBubbleTime(record.recordedAt).takeIf { pendingFileOwnsFooter },
            showStatus = pendingFileOwnsFooter && showStatus,
            status = item.status,
            retention = retentionInput.takeIf { pendingFileOwnsFooter },
            reserveRetentionSpace = pendingFileOwnsFooter && reserveRetentionSpace,
            onRetry =
                if (mine && item.status == MessageStatus.Failed) {
                    { appState.launchMutation { controller.retryFailedSend(item) } }
                } else {
                    null
                },
        )
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ColumnScope.BubbleBodyFooterAndRetry(
    item: TimelineMessage,
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    eventCardResolver: NostrEventCardResolver? = null,
    bodyText: String?,
    bodyMarkdownDocument: MarkdownDocumentFfi? = null,
    deleted: Boolean,
    persistedFailure: Boolean,
    textSelectionMode: Boolean,
    customBubbleColorActive: Boolean,
    selectableTextLayoutReporter: (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit,
    markdownLinkLayoutReporter: (Any, AnnotatedString, TextLayoutResult?, LayoutCoordinates?) -> Unit,
    onCopyMarkdownLink: (String) -> Unit,
    deferMarkdownLinkActivation: ((() -> Unit) -> Unit)? = null,
    plainTextSelectionModifier: Modifier,
    onPlainTextLayout: (TextLayoutResult) -> Unit,
    ttsLeafHighlightResolver: TtsLeafHighlightResolver? = null,
    ttsSentenceLayoutReporter: TtsSentenceLayoutReporter? = null,
    ttsReadAloudProgress: TtsReadAloudProgress? = null,
    selectionWrapper: @Composable (@Composable () -> Unit) -> Unit,
    collapsible: Boolean,
    replyPreviewPresent: Boolean,
    hasMedia: Boolean,
    bubbleBackgroundColor: Color,
    bubbleContentColor: Color,
    timestampColor: Color,
    showStatus: Boolean,
    retentionOwnedByFileCard: Boolean = false,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
    footerOnVisualMedia: Boolean,
    footerOnPendingVisual: Boolean,
    showTimestamp: Boolean = true,
    invalidationWarning: String?,
    mine: Boolean,
    onExpand: () -> Unit,
) {
    val retentionInput =
        record
            .retentionIndicatorInput(
                controllerKey = controller,
                accountRef = controller.boundAccountRef,
                deleted = deleted,
                retentionAtSendSeconds = item.retentionAtSendSeconds,
            ).takeUnless { retentionOwnedByFileCard }
    val reserveRetentionSpace =
        !retentionOwnedByFileCard &&
            !deleted &&
            shouldReserveRetentionIndicatorSpace(
                input = retentionInput,
                projectedRetentionSeconds = record.retentionSeconds,
                mine = mine,
                status = item.status,
                groupRetentionSeconds = controller.group.disappearingMessageSecs,
            )
    val inlineFooter: @Composable () -> Unit = {
        MessageInlineFooter(
            timeText = rememberedMessageBubbleTime(record.recordedAt),
            color = timestampColor,
            showStatus = showStatus,
            status = item.status,
            retention = retentionInput,
            reserveRetentionSpace = reserveRetentionSpace,
            editedLabel = editedLabel,
            onEditedClick = onEditedClick,
            showTime = showTimestamp,
        )
    }
    val hasInlineFooter =
        showTimestamp || showStatus || retentionInput != null || reserveRetentionSpace || editedLabel != null
    var lastLineLayout by
        remember(record.messageIdHex, bodyText) {
            mutableStateOf<TextLayoutResult?>(null)
        }
    val readMoreLabel = stringResource(R.string.message_read_more)
    val readMoreStyle = SpanStyle(color = bubbleContentColor, fontWeight = FontWeight.Bold)
    if (bodyText != null) {
        val markdownDocument =
            messageMarkdownDocumentForDisplayedBody(
                bodyText = bodyText,
                recordPlaintext = record.plaintext,
                storedDocument = record.contentTokens,
                overrideDocument = bodyMarkdownDocument,
                deleted = deleted,
                persistedFailure = persistedFailure,
            )
        val renderMarkdownBody = markdownDocument != null
        val eventReferences =
            remember(renderMarkdownBody, markdownDocument, eventCardResolver) {
                if (renderMarkdownBody && eventCardResolver != null) {
                    nostrEventReferences(markdownDocument)
                } else {
                    emptyList()
                }
            }
        val showMessageTextBody =
            !messageContainsOnlyNostrEventReferences(
                message = bodyText,
                references = eventReferences,
            )
        val density = LocalDensity.current
        val lineHeightPx =
            with(density) { (MaterialTheme.typography.bodyLarge.lineHeight).toPx() }
        val maxBodyHeightDp =
            with(density) { (lineHeightPx * MESSAGE_COLLAPSE_LINE_LIMIT).toDp() }
        val maxBodyHeightPx = with(density) { maxBodyHeightDp.roundToPx() }
        var measuredBodyHeightPx by
            remember(record.messageIdHex, bodyText) {
                mutableStateOf<Int?>(null)
            }
        val bodyIsCollapsed =
            ttsBodyIsCollapsed(
                collapseEnabled = collapsible,
                measuredBodyHeightPx = measuredBodyHeightPx,
                maxBodyHeightPx = maxBodyHeightPx,
            )
        val bodyCollapsePending = collapsible && measuredBodyHeightPx == null
        val presentedTtsLeafHighlightResolver =
            activeTtsLeafHighlightResolver(
                resolver = ttsLeafHighlightResolver,
                textSelectionMode = textSelectionMode,
                suppressForCollapsed = bodyIsCollapsed || bodyCollapsePending,
            )
        val presentedTtsSentenceLayoutReporter =
            ttsSentenceLayoutReporter.takeUnless {
                textSelectionMode || bodyIsCollapsed || bodyCollapsePending
            }
        val bodyMeasurementModifier =
            if (collapsible) {
                Modifier.onSizeChanged { measuredBodyHeightPx = it.height }
            } else {
                Modifier
            }
        val highlightStyle =
            rememberTtsReadAloudHighlightStyle(
                background = bubbleBackgroundColor,
                content = bubbleContentColor,
                sentenceAccent = MaterialTheme.colorScheme.outlineVariant,
                wordAccent = MaterialTheme.colorScheme.tertiary,
                amoled = isAmoledSurfaceTheme(),
            )
        var plainLayoutResult by remember(bodyText) { mutableStateOf<TextLayoutResult?>(null) }
        var plainLayoutCoordinates by remember(bodyText) { mutableStateOf<LayoutCoordinates?>(null) }
        DisposableEffect(presentedTtsSentenceLayoutReporter, bodyText) {
            onDispose {
                presentedTtsSentenceLayoutReporter?.invoke("plain", bodyText, null, null)
            }
        }
        val plainHighlight = presentedTtsLeafHighlightResolver?.invoke("plain", bodyText)
        val plainHighlightModifier =
            Modifier.ttsReadAloudHighlight(plainLayoutResult, plainHighlight, highlightStyle)
        val messageTextBody: @Composable () -> Unit = {
            if (renderMarkdownBody) {
                readAloudMessageSemantics(
                    progress = ttsReadAloudProgress,
                    modifier = bodyMeasurementModifier,
                ) {
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
                        checkNotNull(markdownDocument),
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
                        onSelectableTextLayoutChanged = selectableTextLayoutReporter,
                        onLinkTextLayoutChanged = markdownLinkLayoutReporter,
                        onCopyLink = onCopyMarkdownLink,
                        deferLinkActivation = deferMarkdownLinkActivation,
                        ttsLeafHighlightResolver = presentedTtsLeafHighlightResolver,
                        ttsReadAloudHighlightStyle = highlightStyle,
                        ttsSentenceLayoutReporter = presentedTtsSentenceLayoutReporter,
                    )
                }
            } else {
                readAloudMessageSemantics(
                    progress = ttsReadAloudProgress,
                    modifier = bodyMeasurementModifier,
                    messageContent = {
                        Text(
                            bodyText,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier =
                                plainTextSelectionModifier
                                    .then(plainHighlightModifier)
                                    .onGloballyPositioned { coordinates ->
                                        plainLayoutCoordinates = coordinates
                                        presentedTtsSentenceLayoutReporter?.invoke(
                                            "plain",
                                            bodyText,
                                            plainLayoutResult,
                                            coordinates,
                                        )
                                    },
                            maxLines = if (collapsible) MESSAGE_COLLAPSE_LINE_LIMIT + 1 else Int.MAX_VALUE,
                            onTextLayout = {
                                lastLineLayout = it
                                plainLayoutResult = it
                                presentedTtsSentenceLayoutReporter?.invoke(
                                    "plain",
                                    bodyText,
                                    it,
                                    plainLayoutCoordinates,
                                )
                                onPlainTextLayout(it)
                            },
                        )
                    },
                )
            }
        }
        val eventCards: @Composable () -> Unit = {
            if (eventCardResolver != null && eventReferences.isNotEmpty()) {
                NostrEventCards(
                    references = eventReferences,
                    resolver = eventCardResolver,
                    authorDisplayName = appState::contactDisplayNameCached,
                    mentionDisplayName = appState::mentionDisplayName,
                    onNostrProfileTap = appState::presentNostrProfile,
                    parseMarkdown = appState::parseMarkdownOrEmpty,
                    contentColor = bubbleContentColor,
                )
            }
        }
        val selectableMessageBody: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showMessageTextBody) selectionWrapper(messageTextBody)
                eventCards()
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
                        onClick = onExpand,
                    ),
            )
        }
        val bodyModifier =
            messageBubbleBodyModifier(
                hasReplyPreview = replyPreviewPresent,
                hasMedia = hasMedia,
            )
        val lastLineWidth =
            lastLineLayout?.let { layout ->
                if (layout.lineCount > 0) {
                    ceil(layout.getLineRight(layout.lineCount - 1)).toInt()
                } else {
                    null
                }
            }
        val footerLastLineWidth = lastLineWidth.takeIf { eventReferences.isEmpty() }
        if (collapsible && eventReferences.isNotEmpty() && showMessageTextBody) {
            Column(
                modifier = bodyModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BubbleCollapsibleFooterLayout(
                    maxBodyHeight = maxBodyHeightDp,
                    readMore = readMoreFooter,
                    footer = {},
                    modifier = Modifier.fillMaxWidth(),
                    lastLineWidth = lastLineWidth,
                ) {
                    selectionWrapper(messageTextBody)
                }
                eventCards()
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    inlineFooter()
                }
            }
        } else if (collapsible && showMessageTextBody) {
            BubbleCollapsibleFooterLayout(
                maxBodyHeight = maxBodyHeightDp,
                readMore = readMoreFooter,
                footer = { if (hasInlineFooter) inlineFooter() },
                modifier = bodyModifier,
                lastLineWidth = footerLastLineWidth,
            ) {
                selectableMessageBody()
            }
        } else if (hasInlineFooter) {
            BubbleFooterLayout(
                footer = inlineFooter,
                modifier = bodyModifier,
                lastLineWidth = footerLastLineWidth,
            ) {
                selectableMessageBody()
            }
        } else {
            Box(modifier = bodyModifier) {
                selectableMessageBody()
            }
        }
    } else if (!footerOnVisualMedia && !footerOnPendingVisual && hasInlineFooter) {
        Box(modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start)) {
            inlineFooter()
        }
    }
    invalidationWarning?.let { warning ->
        MessageBubbleInvalidationWarning(
            warning = warning,
            color = timestampColor,
            modifier = Modifier.align(Alignment.Start).padding(top = 2.dp),
        )
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
