package dev.ipf.whitenoise.android.ui.conversation.replies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.MARKDOWN_PREVIEW_MAX_LENGTH
import dev.ipf.whitenoise.android.ui.MarkdownTimestampText
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.common.boundedIntrinsicHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAccessoryRemoveButton
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.fileIconFor
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.safeAttachmentDisplayName
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Description
import dev.ipf.whitenoise.android.ui.icons.filled.Image
import dev.ipf.whitenoise.android.ui.icons.filled.Mic
import dev.ipf.whitenoise.android.ui.icons.filled.Movie
import dev.ipf.whitenoise.android.ui.previewTake
import dev.ipf.whitenoise.android.ui.rememberMarkdownPreviewText
import dev.ipf.whitenoise.android.ui.resolveMentionsInPlaintext
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/** Resolves the current profile title for a reply sender. */
internal fun senderTitleForReply(
    senderPubkey: String,
    appState: WhiteNoiseAppState,
): String = appState.displayName(senderPubkey)

/** Compares a reply sender with the active account without crossing account scope. */
internal fun isOwnReplySender(
    senderPubkey: String,
    appState: WhiteNoiseAppState,
): Boolean {
    val active = appState.activeAccount?.accountIdHex ?: return false
    return senderPubkey.equals(active, ignoreCase = true)
}

/** The quote's heading: the original sender, "You", or the bare reply label when the original is gone. */
@Composable
internal fun replyQuoteTitle(
    senderTitle: String,
    isOwn: Boolean,
    originalUnavailable: Boolean,
): String =
    when {
        originalUnavailable -> stringResource(R.string.reply)
        isOwn -> stringResource(R.string.reply_you)
        else -> senderTitle
    }

/** A typed document presentation for the quoted attachment, when its name or type is known. */
internal fun replyQuoteAttachmentPresentation(
    mediaKind: dev.ipf.whitenoise.android.core.ReplyMediaKind,
    mediaFileName: String?,
    mediaType: String?,
) = if (
    mediaKind == dev.ipf.whitenoise.android.core.ReplyMediaKind.Document &&
    (!mediaFileName.isNullOrBlank() || !mediaType.isNullOrBlank())
) {
    resolveAttachmentPresentation(mediaType.orEmpty(), mediaFileName.orEmpty())
} else {
    null
}

/** The label that replaces the quoted text for media or an unavailable original; null for a text quote. */
@Composable
internal fun replyQuoteMediaLabel(
    mediaKind: dev.ipf.whitenoise.android.core.ReplyMediaKind,
    mediaFileName: String?,
    originalUnavailable: Boolean,
    attachmentPresentation: AttachmentPresentation?,
): String? =
    when {
        originalUnavailable -> stringResource(R.string.toast_original_message_unavailable)
        attachmentPresentation != null ->
            replyAttachmentPreviewText(mediaFileName.orEmpty(), attachmentPresentation)
                ?: stringResource(R.string.reply_media_document)
        mediaKind == dev.ipf.whitenoise.android.core.ReplyMediaKind.Photo ->
            stringResource(R.string.reply_media_photo)
        mediaKind == dev.ipf.whitenoise.android.core.ReplyMediaKind.Video ->
            stringResource(R.string.reply_media_video)
        mediaKind == dev.ipf.whitenoise.android.core.ReplyMediaKind.Voice ->
            stringResource(R.string.reply_media_voice)
        mediaKind == dev.ipf.whitenoise.android.core.ReplyMediaKind.Document ->
            stringResource(R.string.reply_media_document)
        else -> null
    }

/**
 * The spoken form of an in-bubble quote, for a message row that reads as one screen-reader stop
 * (#3603). It uses the same heading and media label as [ReplyPreviewCard].
 */
@Composable
internal fun replyQuoteAccessibilityText(
    preview: dev.ipf.whitenoise.android.core.TimelineReplyDisplay,
    appState: WhiteNoiseAppState,
): String {
    val mediaLabel =
        replyQuoteMediaLabel(
            mediaKind = preview.mediaKind,
            mediaFileName = preview.mediaFileName,
            originalUnavailable = preview.originalUnavailable,
            attachmentPresentation =
                remember(preview) {
                    replyQuoteAttachmentPresentation(preview.mediaKind, preview.mediaFileName, preview.mediaType)
                },
        )
    if (preview.originalUnavailable) return mediaLabel.orEmpty()
    val title =
        replyQuoteTitle(
            senderTitle = senderTitleForReply(preview.sender, appState),
            isOwn = isOwnReplySender(preview.sender, appState),
            originalUnavailable = false,
        )
    val excerpt = mediaLabel ?: replyFallbackPreviewText(preview.body, appState::mentionDisplayName)
    return stringResource(R.string.message_reply_quote_description, title, excerpt)
}

/** Renders a compact reply quote with truthful unavailable and typed-attachment states. */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ReplyPreviewCard(
    senderTitle: String,
    isOwn: Boolean,
    body: String,
    warning: String? = null,
    mediaKind: dev.ipf.whitenoise.android.core.ReplyMediaKind,
    mediaFileName: String? = null,
    mediaType: String? = null,
    originalUnavailable: Boolean = false,
    onClick: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    // The composer banner spans the input row, so it fills its width. The
    // in-bubble quote (#208) must instead hug its content: forcing
    // fillMaxWidth there expands the enclosing bubble Column to its max
    // width even when the quote and reply text are both short.
    fillWidth: Boolean = true,
    mentionDisplayName: ((String) -> String?)? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    accentColor: Color? = null,
    secondaryColor: Color? = null,
    warningColor: Color? = null,
    receivedEmoji: ReceivedEmoji = ReceivedEmoji.None,
    appState: WhiteNoiseAppState? = null,
) {
    val title = replyQuoteTitle(senderTitle, isOwn, originalUnavailable)
    val attachmentPresentation =
        remember(mediaKind, mediaFileName, mediaType) {
            replyQuoteAttachmentPresentation(mediaKind, mediaFileName, mediaType)
        }
    val mediaLabel = replyQuoteMediaLabel(mediaKind, mediaFileName, originalUnavailable, attachmentPresentation)
    val mediaIcon =
        if (originalUnavailable) {
            null
        } else {
            attachmentPresentation?.let { fileIconFor(it.iconCategory) }
                ?: when (mediaKind) {
                    dev.ipf.whitenoise.android.core.ReplyMediaKind.Photo -> Icons.Default.Image
                    dev.ipf.whitenoise.android.core.ReplyMediaKind.Video -> Icons.Default.Movie
                    dev.ipf.whitenoise.android.core.ReplyMediaKind.Voice -> Icons.Default.Mic
                    dev.ipf.whitenoise.android.core.ReplyMediaKind.Document -> Icons.Default.Description
                    dev.ipf.whitenoise.android.core.ReplyMediaKind.None -> null
                }
        }
    // Media path shows a label; only the plaintext body carries raw profile
    // mention runs, so resolve them to match the bubble's rendering (#615/#1090).
    val bodyText =
        remember(body, mediaLabel, mentionDisplayName) {
            mediaLabel ?: replyFallbackPreviewText(body, mentionDisplayName)
        }
    var bodyDocument by remember(body, appState, appState?.activeAccount?.accountIdHex) {
        mutableStateOf<MarkdownDocumentFfi?>(null)
    }
    LaunchedEffect(body, mediaLabel, appState, appState?.activeAccount?.accountIdHex) {
        bodyDocument = if (mediaLabel == null) appState?.parseMarkdownOrEmpty(body) else null
    }
    val projectedBody =
        bodyDocument?.takeIf { it.blocks.isNotEmpty() }?.let {
            rememberMarkdownPreviewText(it, mentionDisplayName = mentionDisplayName)
        }
            ?: remember(bodyText) { EmojiShortcodes.annotate(AnnotatedString(bodyText)) }
    val resolvedAccentColor =
        accentColor
            ?: if (isOwn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            }
    val resolvedContainerColor = containerColor ?: MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    val resolvedContentColor = secondaryColor ?: contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    val resolvedSurfaceContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val quoteEndPadding = if (onDismiss == null) 12.dp else 48.dp
    Surface(
        color = resolvedContainerColor,
        contentColor = resolvedSurfaceContentColor,
        shape = RoundedCornerShape(10.dp),
        border = amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier),
    ) {
        Box {
            Row(
                modifier =
                    Modifier
                        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                        .boundedIntrinsicHeight(IntrinsicSize.Min)
                        .padding(start = 12.dp, top = 8.dp, end = quoteEndPadding, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(resolvedAccentColor)
                            .testTag("conversation.reply.bar"),
                )
                Column(modifier = if (fillWidth) Modifier.weight(1f) else Modifier) {
                    EmojiLabel(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = resolvedSurfaceContentColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (mediaIcon != null) {
                            Icon(
                                mediaIcon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = resolvedContentColor,
                            )
                        }
                        MarkdownTimestampText(
                            projectedBody,
                            inlineContent = EmojiShortcodes.content(receivedEmoji),
                            style = MaterialTheme.typography.bodySmall.copy(color = resolvedContentColor),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    warning?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = warningColor ?: resolvedContentColor.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (onDismiss != null) {
                ComposerAccessoryRemoveButton(
                    onClick = onDismiss,
                    description = stringResource(R.string.cancel_reply),
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/** Combines a sanitized basename with the resolved attachment format label. */
internal fun replyAttachmentPreviewText(
    fileName: String,
    presentation: AttachmentPresentation,
): String? {
    val displayName = safeAttachmentDisplayName(fileName) ?: return presentation.formatLabel
    return presentation.formatLabel?.let { "$displayName · $it" } ?: displayName
}

/** Bounds the first-frame plaintext quote before asynchronous Markdown projection becomes available. */
internal fun replyFallbackPreviewText(
    body: String,
    mentionDisplayName: ((String) -> String?)?,
): String = resolveMentionsInPlaintext(body, mentionDisplayName).previewTake(MARKDOWN_PREVIEW_MAX_LENGTH)
