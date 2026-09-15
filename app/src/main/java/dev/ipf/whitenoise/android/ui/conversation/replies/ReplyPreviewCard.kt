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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAccessoryRemoveButton
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.fileIconFor
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.safeAttachmentDisplayName
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
) {
    val title =
        when {
            originalUnavailable -> stringResource(R.string.reply)
            isOwn -> stringResource(R.string.reply_you)
            else -> senderTitle
        }
    val attachmentPresentation =
        remember(mediaKind, mediaFileName, mediaType) {
            if (
                mediaKind == dev.ipf.whitenoise.android.core.ReplyMediaKind.Document &&
                (!mediaFileName.isNullOrBlank() || !mediaType.isNullOrBlank())
            ) {
                resolveAttachmentPresentation(mediaType.orEmpty(), mediaFileName.orEmpty())
            } else {
                null
            }
        }
    val mediaLabel =
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
            mediaLabel ?: resolveMentionsInPlaintext(body, mentionDisplayName)
        }
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
                        .height(IntrinsicSize.Min)
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
                    Text(
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
                        Text(
                            bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = resolvedContentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    warning?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = resolvedContentColor.copy(alpha = 0.75f),
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
