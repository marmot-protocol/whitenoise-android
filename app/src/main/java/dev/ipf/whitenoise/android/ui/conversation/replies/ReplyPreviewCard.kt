package dev.ipf.whitenoise.android.ui.conversation.replies

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.resolveMentionsInPlaintext
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

internal fun senderTitleForReply(
    senderPubkey: String,
    appState: WhiteNoiseAppState,
): String = appState.displayName(senderPubkey)

internal fun isOwnReplySender(
    senderPubkey: String,
    appState: WhiteNoiseAppState,
): Boolean {
    val active = appState.activeAccount?.accountIdHex ?: return false
    return senderPubkey.equals(active, ignoreCase = true)
}

@Composable
internal fun ReplyPreviewCard(
    senderTitle: String,
    isOwn: Boolean,
    body: String,
    mediaKind: dev.ipf.whitenoise.android.core.ReplyMediaKind,
    onClick: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    // The composer banner spans the input row, so it fills its width. The
    // in-bubble quote (#208) must instead hug its content: forcing
    // fillMaxWidth there expands the enclosing bubble Column to its max
    // width even when the quote and reply text are both short.
    fillWidth: Boolean = true,
    mentionDisplayName: ((String) -> String?)? = null,
) {
    val title = if (isOwn) stringResource(R.string.reply_you) else senderTitle
    val mediaLabel =
        when (mediaKind) {
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Photo -> stringResource(R.string.reply_media_photo)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Video -> stringResource(R.string.reply_media_video)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Voice -> stringResource(R.string.reply_media_voice)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Document -> stringResource(R.string.reply_media_document)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Sticker -> stringResource(R.string.sticker)
            dev.ipf.whitenoise.android.core.ReplyMediaKind.None -> null
        }
    val mediaIcon =
        when (mediaKind) {
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Photo -> Icons.Default.Image
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Video -> Icons.Default.Movie
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Voice -> Icons.Default.Mic
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Document -> Icons.Default.Description
            dev.ipf.whitenoise.android.core.ReplyMediaKind.Sticker -> Icons.Default.Image
            dev.ipf.whitenoise.android.core.ReplyMediaKind.None -> null
        }
    // Media path shows a label; only the plaintext body carries raw profile
    // mention runs, so resolve them to match the bubble's rendering (#615/#1090).
    val bodyText =
        remember(body, mediaLabel, mentionDisplayName) {
            mediaLabel ?: resolveMentionsInPlaintext(body, mentionDisplayName)
        }
    val accent =
        if (isOwn) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        border = amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent),
            )
            Row(
                // weight(1f) forces this Row to fill the parent's width, which
                // re-expands the bubble (#208). Only claim it when the card is
                // meant to span its container (composer banner). When hugging,
                // wrap to content so a short quote keeps the bubble narrow.
                modifier =
                    (if (fillWidth) Modifier.weight(1f) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = if (fillWidth) Modifier.weight(1f) else Modifier) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_reply),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
