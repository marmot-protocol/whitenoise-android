@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.WebLinkConfirmation
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.nativePageBody
import dev.ipf.whitenoise.android.ui.common.nativePageHeader
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.isOpenableMarkdownLink
import dev.ipf.whitenoise.android.ui.openMarkdownLink
import dev.ipf.whitenoise.android.ui.settings.rememberMeasuredControlClick
import kotlinx.coroutines.launch

/** Parses a verified event body and presents it without starting another event-resolution layer. */
@Composable
internal fun NostrEventReaderDialog(
    card: NostrEventCardModel,
    authoredReference: String?,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    onDismiss: () -> Unit,
    origin: Rect? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val eventUri = authoredReference?.let(::nostrEventUri)
    val prepared = rememberNostrReaderPreparation(card, parseMarkdown)
    var playing by remember(card.eventIdHex) { mutableStateOf<Pair<String, Rect?>?>(null) }
    var linkedImage by remember(card.eventIdHex) { mutableStateOf<String?>(null) }
    MeasuredPlaybackDialog(
        origin = origin,
        ownerKey = card.eventIdHex,
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        NostrEventReaderScreen(
            card = card,
            document = prepared.document,
            parsing = prepared.parsing,
            authorDisplayName = authorDisplayName,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            onCopyReference =
                eventUri?.let { uri ->
                    {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Nostr event", uri)))
                        }
                    }
                },
            onOpenExternal =
                eventUri?.let { uri ->
                    { openNostrEvent(context, uri) }
                },
            onDismiss = dismiss,
            onPlayVideo = { url -> playing = url to null },
            onPlayVideoMeasured = { url, bounds -> playing = url to bounds },
            onViewImage = { url -> linkedImage = url },
            preparation = prepared,
        )
        playing?.let { (url, bounds) ->
            NostrVideoPlayerDialog(
                mediaUrl = url,
                origin = bounds,
                onDismiss = { playing = null },
            )
        }
        linkedImage?.let { url -> NostrLinkedImageDialog(url, onDismiss = { linkedImage = null }) }
    }
}

/** Renders the complete verified note or article with its immutable event context. */
@Composable
internal fun NostrEventReaderScreen(
    card: NostrEventCardModel,
    document: MarkdownDocumentFfi?,
    parsing: Boolean,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    onCopyReference: (() -> Unit)? = null,
    onOpenExternal: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayVideo: ((String) -> Unit)? = null,
    onPlayVideoMeasured: ((String, Rect?) -> Unit)? = null,
    onViewImage: ((String) -> Unit)? = null,
    preparation: NostrReaderPreparation? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().nativePageBody()) {
            NostrEventReaderHeader(
                card = card,
                onDismiss = onDismiss,
                onCopyReference = onCopyReference,
                onOpenExternal = onOpenExternal,
            )
            AppDivider()
            NostrEventReaderBody(
                card = card,
                document = document,
                parsing = parsing,
                authorDisplayName = authorDisplayName,
                mentionDisplayName = mentionDisplayName,
                onNostrProfileTap = onNostrProfileTap,
                onPlayVideo = onPlayVideo,
                onPlayVideoMeasured = onPlayVideoMeasured,
                preparation = preparation,
                onViewImage = onViewImage,
            )
        }
    }
}

/** Keeps Back, copy, and external-open controls distinct from links in the reader body. */
@Composable
private fun NostrEventReaderHeader(
    card: NostrEventCardModel,
    onDismiss: () -> Unit,
    onCopyReference: (() -> Unit)?,
    onOpenExternal: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().nativePageHeader().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.close),
            )
        }
        Text(
            text = card.kind.label(card.eventKind),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (onCopyReference != null && onOpenExternal != null) {
            EventCardActions(
                contentColor = MaterialTheme.colorScheme.onSurface,
                copyDescription = stringResource(R.string.nostr_event_copy),
                openDescription = stringResource(R.string.nostr_event_open),
                onCopy = onCopyReference,
                onOpen = onOpenExternal,
            )
        }
    }
}

/** Renders event metadata and the bounded full Markdown body. */
@Composable
private fun NostrEventReaderBody(
    card: NostrEventCardModel,
    document: MarkdownDocumentFfi?,
    parsing: Boolean,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    onPlayVideo: ((String) -> Unit)?,
    onPlayVideoMeasured: ((String, Rect?) -> Unit)?,
    preparation: NostrReaderPreparation?,
    onViewImage: ((String) -> Unit)?,
) {
    val blocks =
        preparation?.blocks ?: remember(document) {
            document
                ?.takeUnless { card.literalReaderBody }
                ?.takeIf(::nostrReaderCanFormat)
                ?.let(::nostrReaderBlocks)
                .orEmpty()
        }
    val textChunks =
        preparation?.textChunks ?: remember(card.readerBody, card.summary) {
            nostrReaderTextChunks(card.readerBody ?: card.summary.orEmpty())
        }
    val media =
        preparation?.media ?: remember(card.imageUrls, card.mediaUrl, document) {
            nostrEventMediaCandidates(card.imageUrls, listOfNotNull(card.mediaUrl), document)
        }
    val onMediaLink: (String) -> Boolean =
        remember(media, onViewImage, onPlayVideo) {
            { raw -> activateNostrMediaLink(raw, media, onViewImage, onPlayVideo) }
        }
    WhiteNoiseLazyColumn(
        modifier = Modifier.fillMaxSize().testTag(NOSTR_EVENT_READER_BODY_TAG),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("event-context") {
            NostrEventReaderContext(card, authorDisplayName, onPlayVideo, media.videoUrls, onPlayVideoMeasured)
        }
        itemsIndexed(media.imageUrls, key = { _, url -> "image:$url" }) { _, url ->
            NostrEventImagePane(url = url)
        }
        if (parsing) {
            item("parsing") { CircularProgressIndicator(Modifier.testTag(NOSTR_EVENT_READER_LOADING_TAG)) }
        } else if (blocks.isNotEmpty()) {
            itemsIndexed(blocks, key = { index, _ -> "body:$index" }) { _, block ->
                NostrEventReaderBlock(
                    block = block,
                    onWebLinkActivated = onMediaLink,
                    mentionDisplayName = mentionDisplayName,
                    onNostrProfileTap = onNostrProfileTap,
                )
            }
        } else {
            itemsIndexed(textChunks) { _, chunk ->
                val style = MaterialTheme.typography.bodyLarge
                EmojiLabel(
                    chunk,
                    style = if (card.literalReaderBody) style.copy(fontFamily = FontFamily.Monospace) else style,
                )
            }
        }
    }
}

/**
 * Keeps the signed event context and explicit media actions above the complete body. The reader stands for its
 * event, so the encoded reference is not printed here (#3317); its Copy reference action keeps the full value.
 */
@Composable
private fun NostrEventReaderContext(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    onPlayVideo: ((String) -> Unit)?,
    videoUrls: List<String>,
    onPlayVideoMeasured: ((String, Rect?) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        card.title?.takeIf(String::isNotBlank)?.let { title ->
            EmojiLabel(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        EmojiLabel(
            eventByline(card, authorDisplayName),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        card.metadata.forEach { detail -> EmojiLabel(detail, style = MaterialTheme.typography.bodyMedium) }
        if (card.gitLinks.isNotEmpty()) {
            val clipboard = LocalClipboard.current
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var pendingOpenLink by remember { mutableStateOf<String?>(null) }
            pendingOpenLink?.let { link ->
                WebLinkConfirmation(
                    url = link,
                    onDismiss = { pendingOpenLink = null },
                    onConfirm = {
                        pendingOpenLink = null
                        openMarkdownLink(context, link)
                    },
                )
            }
            card.gitLinks.forEach { link ->
                TextButton(
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(link, link))) } },
                ) {
                    Text(stringResource(R.string.nostr_git_copy_url, link))
                }
                if (Uri.parse(link).scheme.equals("https", ignoreCase = true) && isOpenableMarkdownLink(link)) {
                    TextButton(onClick = { pendingOpenLink = link }) {
                        Text(stringResource(R.string.nostr_git_open_url, link))
                    }
                }
            }
        }
        if (onPlayVideo != null) {
            videoUrls.forEach { url ->
                val (playModifier, play) =
                    rememberMeasuredControlClick { bounds ->
                        if (onPlayVideoMeasured != null) onPlayVideoMeasured(url, bounds) else onPlayVideo(url)
                    }
                TextButton(
                    onClick = play,
                    modifier = playModifier,
                ) { Text(stringResource(R.string.nostr_event_play_video)) }
            }
        }
    }
}

/** Normalizes a parser-authored reference for copy and external-open actions. */
private fun nostrEventUri(authoredReference: String): String =
    if (authoredReference.startsWith("nostr:", ignoreCase = true)) authoredReference else "nostr:$authoredReference"

/** Sends an event reference to an installed Nostr handler without failing the in-app reader. */
private fun openNostrEvent(
    context: Context,
    eventUri: String,
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(eventUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal const val NOSTR_EVENT_READER_BODY_TAG = "nostr-event-reader-body"

internal const val NOSTR_EVENT_READER_LOADING_TAG = "nostr-event-reader-loading"
