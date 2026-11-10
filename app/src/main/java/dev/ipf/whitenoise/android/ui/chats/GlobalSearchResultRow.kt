@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.common.PersonAvatar
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val GlobalSearchResultAvatarSize = 40.dp
private val GlobalSearchResultMinHeight = 72.dp
private val GlobalSearchResultAttachmentIconSize = 16.dp
private const val GLOBAL_SEARCH_RESULT_SNIPPET_LINES = 2

/** Font scale from which the result title stacks above its time. */
private const val GLOBAL_SEARCH_RESULT_STACKED_FONT_SCALE = 1.5f

/** Test tag of one result row, from its stable key. */
internal fun globalSearchResultTag(result: GlobalSearchResult): String = "global.search.result.${result.key}"

/**
 * What one result row shows beyond the result itself: resolved names and the sender's picture. A null
 * [timeLabel] shows the live relative time; previews and tests pass a fixed one.
 */
internal data class GlobalSearchResultPresentation(
    val result: GlobalSearchResult,
    val senderLabel: String,
    val chatTitle: String,
    val avatarUrl: String? = null,
    val timeLabel: String? = null,
)

/**
 * One matching message (#1944): the sender's avatar, "sender in chat" with the time at the end, the snippet with
 * the match highlighted, then the attachment it carries. The whole row is one button at least 72dp tall that
 * opens the chat at this message; TalkBack reads its lines as one item, and [selected] marks the row a return
 * from that chat landed on.
 */
@Composable
internal fun GlobalSearchResultRow(
    presentation: GlobalSearchResultPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val result = presentation.result
    val highlight = MaterialTheme.colorScheme.primary
    val snippet =
        remember(result.snippet, highlight) { EmojiShortcodes.annotate(highlightedSnippet(result.snippet, highlight)) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = GlobalSearchResultMinHeight)
                .testTag(globalSearchResultTag(result))
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                ).clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.go_to_message),
                    onClick = onOpen,
                ).semantics(mergeDescendants = true) { this.selected = selected }
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        PersonAvatar(presentation.senderLabel, result.senderIdHex, GlobalSearchResultAvatarSize, presentation.avatarUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GlobalSearchResultOrigin(presentation)
            Text(
                text = snippet,
                inlineContent = EmojiShortcodes.content(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = GLOBAL_SEARCH_RESULT_SNIPPET_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            result.attachment?.let { GlobalSearchResultAttachmentLine(it) }
        }
    }
}

/**
 * "Sender in Chat" on the start side and the time on the end side. At large text the title gets two lines and the
 * time moves beneath it, so neither is squeezed to a few characters.
 */
@Composable
private fun GlobalSearchResultOrigin(presentation: GlobalSearchResultPresentation) {
    val title = stringResource(R.string.library_sender_in_chat, presentation.senderLabel, presentation.chatTitle)
    val time = presentation.timeLabel ?: rememberedRelativeTime(presentation.result.timelineAt)
    if (LocalDensity.current.fontScale >= GLOBAL_SEARCH_RESULT_STACKED_FONT_SCALE) {
        Column {
            GlobalSearchResultTitle(title, Modifier, maxLines = 2)
            GlobalSearchResultTime(time)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlobalSearchResultTitle(title, Modifier.weight(1f), maxLines = 1)
            GlobalSearchResultTime(time)
        }
    }
}

/** The "Sender in Chat" line. */
@Composable
private fun GlobalSearchResultTitle(
    title: String,
    modifier: Modifier,
    maxLines: Int,
) {
    EmojiLabel(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The match's time. */
@Composable
private fun GlobalSearchResultTime(time: String) {
    Text(
        text = time,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** The attachment's type glyph and name, so a media match shows what it holds without its pixels. */
@Composable
private fun GlobalSearchResultAttachmentLine(attachment: GlobalSearchResultAttachment) {
    val (icon, type) =
        when (attachment.kind) {
            GlobalSearchResultAttachmentKind.IMAGE -> R.drawable.ic_image to R.string.attachment_type_image
            GlobalSearchResultAttachmentKind.VIDEO -> R.drawable.ic_play_arrow to R.string.attachment_type_video
            GlobalSearchResultAttachmentKind.AUDIO -> R.drawable.ic_mic to R.string.attachment_type_audio
            GlobalSearchResultAttachmentKind.FILE -> R.drawable.ic_description to R.string.attachment_type_file
        }
    val typeLabel = stringResource(type)
    val label =
        attachment.fileName.takeIf { it.isNotBlank() }?.let { "$typeLabel · $it" } ?: typeLabel
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(GlobalSearchResultAttachmentIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** The snippet with its match in bold primary; a filter-only snippet has an empty range and no highlight. */
internal fun highlightedSnippet(
    snippet: SnippetHighlight,
    highlight: Color,
): AnnotatedString =
    buildAnnotatedString {
        val start = snippet.highlightStart.coerceIn(0, snippet.text.length)
        val end = snippet.highlightEnd.coerceIn(start, snippet.text.length)
        append(snippet.text.substring(0, start))
        val emphasis = SpanStyle(fontWeight = FontWeight.Bold, color = highlight)
        withStyle(emphasis) { append(snippet.text.substring(start, end)) }
        append(snippet.text.substring(end))
    }
