package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
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
import dev.ipf.whitenoise.android.core.retentionIndicatorVisible
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.common.rememberedMessageBubbleTime
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaImageBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPendingPlaceholder
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVideoBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVisualGridBubble
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVoiceBubble
import dev.ipf.whitenoise.android.ui.conversation.share.ContactMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.LocationMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.SharedLocation
import dev.ipf.whitenoise.android.ui.conversation.share.SharedUser
import dev.ipf.whitenoise.android.ui.conversation.share.UserMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.share.formatCoordinate
import kotlin.math.ceil

internal fun ttsBodyIsCollapsed(
    collapseEnabled: Boolean,
    measuredBodyHeightPx: Int?,
    maxBodyHeightPx: Int,
): Boolean = collapseEnabled && (measuredBodyHeightPx == null || measuredBodyHeightPx > maxBodyHeightPx)

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ColumnScope.BubbleMediaBlocks(
    item: TimelineMessage,
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    bubbleMedia: BubbleMedia,
    sharedLocation: SharedLocation?,
    sharedContact: SharedContact?,
    sharedUser: SharedUser?,
    deleted: Boolean,
    mine: Boolean,
    footerOnVisualMedia: Boolean,
    footerOnPendingVisual: Boolean,
    showPendingPlaceholder: Boolean,
    onMediaLongPress: () -> Unit,
    attachedToCaption: Boolean,
) {
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
            Box {
                if (MediaReferenceSupport.isVideoMedia(entry.value)) {
                    MediaVideoBubble(
                        messageIdHex = record.messageIdHex,
                        attachmentIndex = entry.index,
                        reference = entry.value,
                        mine = mine,
                        controller = controller,
                        appState = appState,
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
                        mine = mine,
                        onLongPress = onMediaLongPress,
                        attachedToCaption = attachedToCaption,
                    )
                }
                if (footerOnVisualMedia) {
                    MediaFooterOverlay(
                        timeText = rememberedMessageBubbleTime(record.recordedAt),
                        showStatus = mine,
                        status = item.status,
                        showRetention = retentionIndicatorVisible(record.retentionSeconds),
                    )
                }
            }
        } else {
            MediaVisualGridBubble(
                item = item,
                attachments = bubbleMedia.visuals,
                controller = controller,
                appState = appState,
                mine = mine,
                onLongPress = onMediaLongPress,
                attachedToCaption = attachedToCaption,
            )
        }
    }
    if (!deleted && bubbleMedia.audio.isNotEmpty()) {
        bubbleMedia.audio.forEach { entry ->
            MediaVoiceBubble(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                mine = mine,
                controller = controller,
                appState = appState,
                onLongPress = onMediaLongPress,
                attachedToCaption = attachedToCaption,
            )
        }
    }
    if (!deleted && bubbleMedia.files.isNotEmpty()) {
        bubbleMedia.files.forEach { entry ->
            MediaFileBubble(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                mine = mine,
                controller = controller,
                appState = appState,
                onLongPress = onMediaLongPress,
                attachedToCaption = attachedToCaption,
            )
        }
    }
    if (!deleted && !bubbleMedia.hasConfirmedMedia && bubbleMedia.pendingAudio.isNotEmpty()) {
        bubbleMedia.pendingAudio.forEach { (index, pending) ->
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
                onLongPress = onMediaLongPress,
                attachedToCaption = attachedToCaption,
            )
        }
    }
    if (!deleted && !bubbleMedia.hasConfirmedMedia && bubbleMedia.pendingVisuals.isNotEmpty()) {
        val uploadFailed = item.status == MessageStatus.Failed
        val retryUpload: () -> Unit = {
            appState.launchMutation { controller.retryFailedSend(item) }
        }
        if (bubbleMedia.pendingVisuals.size == 1) {
            val entry = bubbleMedia.pendingVisuals.first()
            Box {
                if (MediaReferenceSupport.isVideoMedia(entry.value)) {
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
                        attachedToCaption = attachedToCaption,
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
                        attachedToCaption = attachedToCaption,
                    )
                }
                if (footerOnPendingVisual) {
                    MediaFooterOverlay(
                        timeText = rememberedMessageBubbleTime(record.recordedAt),
                        showStatus = true,
                        status = item.status,
                        showRetention = retentionIndicatorVisible(record.retentionSeconds),
                    )
                }
            }
        } else {
            MediaVisualGridBubble(
                item = item,
                attachments = bubbleMedia.pendingVisuals,
                controller = controller,
                appState = appState,
                mine = true,
                onLongPress = onMediaLongPress,
                uploading = !uploadFailed,
                attachedToCaption = attachedToCaption,
            )
        }
    }
    if (showPendingPlaceholder) {
        MediaPendingPlaceholder(
            pendingAttachments = controller.pendingAttachmentsList(record.messageIdHex),
            failed = item.status == MessageStatus.Failed,
            attachedToCaption = attachedToCaption,
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
    bodyText: String?,
    bodyMarkdownDocument: MarkdownDocumentFfi? = null,
    deleted: Boolean,
    persistedFailure: Boolean,
    textSelectionMode: Boolean,
    customBubbleColorActive: Boolean,
    selectableTextLayoutReporter: (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit,
    markdownLinkLayoutReporter: (Any, AnnotatedString, TextLayoutResult?, LayoutCoordinates?) -> Unit,
    onCopyMarkdownLink: (String) -> Unit,
    plainTextSelectionModifier: Modifier,
    onPlainTextLayout: (TextLayoutResult) -> Unit,
    ttsLeafHighlightResolver: TtsLeafHighlightResolver? = null,
    ttsReadAloudProgress: TtsReadAloudProgress? = null,
    selectionWrapper: @Composable (@Composable () -> Unit) -> Unit,
    collapsible: Boolean,
    replyPreviewPresent: Boolean,
    hasMedia: Boolean,
    bubbleContentColor: Color,
    timestampColor: Color,
    showStatus: Boolean,
    showRetention: Boolean,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
    footerOnVisualMedia: Boolean,
    footerOnPendingVisual: Boolean,
    invalidationWarning: String?,
    mine: Boolean,
    onExpand: () -> Unit,
) {
    val inlineFooter: @Composable () -> Unit = {
        MessageInlineFooter(
            timeText = rememberedMessageBubbleTime(record.recordedAt),
            color = timestampColor,
            showStatus = showStatus,
            status = item.status,
            showRetention = showRetention,
            editedLabel = editedLabel,
            onEditedClick = onEditedClick,
        )
    }
    var lastLineLayout by
        remember(record.messageIdHex, bodyText) {
            mutableStateOf<TextLayoutResult?>(null)
        }
    val readMoreLabel = stringResource(R.string.message_read_more)
    val readMoreStyle = SpanStyle(color = bubbleContentColor, fontWeight = FontWeight.Bold)
    if (bodyText != null) {
        val markdownDocument = bodyMarkdownDocument ?: record.contentTokens
        val renderMarkdownBody =
            !deleted &&
                !persistedFailure &&
                markdownDocument.blocks.isNotEmpty() &&
                (bodyText == record.plaintext || bodyMarkdownDocument != null)
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
        val presentedTtsLeafHighlightResolver =
            activeTtsLeafHighlightResolver(
                resolver = ttsLeafHighlightResolver,
                textSelectionMode = textSelectionMode,
                suppressForCollapsed = bodyIsCollapsed,
            )
        val bodyMeasurementModifier =
            if (collapsible) {
                Modifier.onSizeChanged { measuredBodyHeightPx = it.height }
            } else {
                Modifier
            }
        val highlightColor = ttsReadAloudHighlightColor()
        var plainLayoutResult by remember(bodyText) { mutableStateOf<TextLayoutResult?>(null) }
        val plainHighlightRange = presentedTtsLeafHighlightResolver?.invoke("plain", bodyText)
        val plainHighlightModifier =
            Modifier.ttsReadAloudHighlight(plainLayoutResult, plainHighlightRange, highlightColor)
        val messageBody: @Composable () -> Unit = {
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
                        onSelectableTextLayoutChanged = selectableTextLayoutReporter,
                        onLinkTextLayoutChanged = markdownLinkLayoutReporter,
                        onCopyLink = onCopyMarkdownLink,
                        ttsLeafHighlightResolver = presentedTtsLeafHighlightResolver,
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
                            modifier = plainTextSelectionModifier.then(plainHighlightModifier),
                            maxLines = if (collapsible) MESSAGE_COLLAPSE_LINE_LIMIT + 1 else Int.MAX_VALUE,
                            onTextLayout = {
                                lastLineLayout = it
                                plainLayoutResult = it
                                onPlainTextLayout(it)
                            },
                        )
                    },
                )
            }
        }
        val selectableMessageBody: @Composable () -> Unit = { selectionWrapper(messageBody) }
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
        if (collapsible) {
            BubbleCollapsibleFooterLayout(
                maxBodyHeight = maxBodyHeightDp,
                readMore = readMoreFooter,
                footer = inlineFooter,
                modifier = bodyModifier,
                lastLineWidth = lastLineWidth,
            ) {
                selectableMessageBody()
            }
        } else {
            BubbleFooterLayout(
                footer = inlineFooter,
                modifier = bodyModifier,
                lastLineWidth = lastLineWidth,
            ) {
                selectableMessageBody()
            }
        }
    } else if (!footerOnVisualMedia && !footerOnPendingVisual) {
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
