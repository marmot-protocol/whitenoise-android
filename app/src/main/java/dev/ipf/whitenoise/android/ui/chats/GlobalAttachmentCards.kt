@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.core.GlobalAttachmentPresentation
import dev.ipf.whitenoise.android.core.globalAttachmentIsVideo
import dev.ipf.whitenoise.android.core.globalAttachmentPresentation
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

private val LibraryPlaceholderIconSize = 32.dp
private val LibraryPlayBadgeIconSize = 20.dp
private val LibraryRowIconSize = 40.dp

/**
 * One library card: a square tile for photos and videos, otherwise a row naming the file, its sender
 * and chat. TalkBack reads one sentence with the type, name, sender, chat and time, and offers
 * Go to Message as a custom action beside the default open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GlobalAttachmentCard(
    item: GlobalAttachmentItem,
    actions: GlobalAttachmentCardActions,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val presentation = globalAttachmentPresentation(item.mediaType)
    val origin = stringResource(R.string.library_sender_in_chat, actions.senderLabel(item), item.chatTitle)
    val liveTime = rememberedRelativeTime(item.timelineAt)
    val time = actions.timeLabel?.invoke(item) ?: liveTime
    val description =
        stringResource(
            R.string.library_item_description,
            libraryTypeLabel(item, presentation),
            item.label,
            actions.senderLabel(item),
            item.chatTitle,
            time,
        )
    val goToMessage = stringResource(R.string.go_to_message)
    val semantics =
        Modifier.clearAndSetSemantics {
            contentDescription = description
            this.selected = selected
            onClick { actions.onOpen(item).let { true } }
            customActions = listOf(CustomAccessibilityAction(goToMessage) { actions.onViewInChat(item).let { true } })
        }
    val clickable =
        Modifier.combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { actions.onViewInChat(item) })
    val base = modifier.fillMaxWidth().testTag(globalLibraryItemTag(item)).then(semantics)
    if (presentation == GlobalAttachmentPresentation.VISUAL) {
        GlobalAttachmentTile(item, actions.thumbnail(item), base.clip(MaterialTheme.shapes.small).then(clickable))
    } else {
        GlobalAttachmentRow(item, presentation, "$origin · $time", base.then(clickable), actions)
    }
}

/** A square face for visual media: decoded pixels when held, else the sender's thumbhash, else a glyph. */
@Composable
private fun GlobalAttachmentTile(
    item: GlobalAttachmentItem,
    thumbnail: ImageBitmap?,
    modifier: Modifier,
) {
    val video = globalAttachmentIsVideo(item.mediaType)
    val hash = item.reference?.thumbhash
    val placeholder = remember(hash) { hash?.let { Thumbhash.decodeToBitmap(it)?.asImageBitmap() } }
    Surface(
        modifier = modifier.aspectRatio(1f),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = amoledOutlineBorder(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val face = thumbnail ?: placeholder
            if (face != null) {
                Image(
                    bitmap = face,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painter = painterResource(if (video) R.drawable.ic_play_arrow else R.drawable.ic_image),
                    contentDescription = null,
                    modifier = Modifier.size(LibraryPlaceholderIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (video && face != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = PLAY_BADGE_ALPHA),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_arrow),
                        contentDescription = null,
                        modifier = Modifier.padding(WhiteNoiseSpacing.Related).size(LibraryPlayBadgeIconSize),
                    )
                }
            }
        }
    }
}

/** A file or audio row: type glyph, name, then "sender in chat · time", with Go to Message trailing. */
@Composable
private fun GlobalAttachmentRow(
    item: GlobalAttachmentItem,
    presentation: GlobalAttachmentPresentation,
    detail: String,
    modifier: Modifier,
    actions: GlobalAttachmentCardActions,
) {
    Row(
        modifier = modifier.padding(vertical = WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = amoledOutlineBorder(),
            modifier = Modifier.size(LibraryRowIconSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val audio = presentation == GlobalAttachmentPresentation.AUDIO
                val icon = if (audio) R.drawable.ic_mic else R.drawable.ic_description
                Icon(painterResource(icon), contentDescription = null)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            EmojiLabel(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = { actions.onViewInChat(item) }) {
            Icon(painterResource(R.drawable.ic_settings_chat_bubble_outline), contentDescription = null)
        }
    }
}

/** The spoken type of an item, from the strings the attachment surfaces already use. */
@Composable
private fun libraryTypeLabel(
    item: GlobalAttachmentItem,
    presentation: GlobalAttachmentPresentation,
): String =
    when {
        presentation == GlobalAttachmentPresentation.AUDIO -> stringResource(R.string.attachment_type_audio)
        presentation == GlobalAttachmentPresentation.FILE -> stringResource(R.string.attachment_type_file)
        globalAttachmentIsVideo(item.mediaType) -> stringResource(R.string.attachment_type_video)
        else -> stringResource(R.string.reply_media_photo)
    }

private const val PLAY_BADGE_ALPHA = 0.72f

/**
 * The decoded thumbnail this account already holds for a library item, or null when the bytes are
 * still encrypted. Browsing never starts a download: an unopened item shows its thumbhash or glyph.
 */
internal fun libraryThumbnail(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    item: GlobalAttachmentItem,
): ImageBitmap? {
    val account = accountRef ?: return null
    val key = mediaCacheKey(account, item.groupIdHex, item.messageIdHex, item.attachmentIndex)
    return appState.cachedMediaThumbnail(key)?.asImageBitmap()
}
