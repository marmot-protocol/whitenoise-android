@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import dev.ipf.whitenoise.android.ui.common.Avatar
import kotlinx.coroutines.launch

@Composable
internal fun NostrEventCards(
    references: List<NostrEventReferenceOccurrence>,
    resolver: NostrEventCardResolver,
    authorDisplayName: (String) -> String,
    mentionDisplayName: (String) -> String?,
    onNostrProfileTap: (String) -> Unit,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (references.isEmpty()) return
    var articleCard by remember(resolver) { mutableStateOf<NostrEventCardModel?>(null) }
    var videoCard by remember(resolver) { mutableStateOf<NostrEventCardModel?>(null) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.take(MAX_CARDS_PER_MESSAGE).forEach { occurrence ->
            key(occurrence.reference.stableId) {
                ResolvedNostrEventCard(
                    occurrence = occurrence,
                    resolver = resolver,
                    authorDisplayName = authorDisplayName,
                    contentColor = contentColor,
                    onReadArticle = { articleCard = it },
                    onPlayVideo = { videoCard = it },
                )
            }
        }
    }
    articleCard?.let { card ->
        NostrArticleReaderDialog(
            card = card,
            authorDisplayName = authorDisplayName,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            parseMarkdown = parseMarkdown,
            onDismiss = { articleCard = null },
        )
    }
    videoCard?.let { card ->
        NostrVideoPlayerDialog(
            mediaUrl = checkNotNull(card.mediaUrl),
            mediaMimeType = card.mediaMimeType,
            onDismiss = { videoCard = null },
        )
    }
}

@Composable
private fun ResolvedNostrEventCard(
    occurrence: NostrEventReferenceOccurrence,
    resolver: NostrEventCardResolver,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    onReadArticle: (NostrEventCardModel) -> Unit,
    onPlayVideo: (NostrEventCardModel) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val flow = remember(resolver, occurrence.reference.stableId) { resolver.state(occurrence.reference) }
    val state by flow.collectAsState()
    val authoredReference = occurrence.authoredReference
    NostrEventCard(
        state = state,
        authorDisplayName = authorDisplayName,
        referenceLabel = compactEventReference(authoredReference),
        contentColor = contentColor,
        onRetry = { resolver.retry(occurrence.reference, flow) },
        onCopy = {
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("Nostr event", "nostr:$authoredReference")),
                )
            }
        },
        onOpen = { card ->
            when {
                card?.kind == NostrEventCardKind.Article && !card.readerBody.isNullOrBlank() -> onReadArticle(card)
                card?.kind == NostrEventCardKind.Video && card.mediaUrl != null -> onPlayVideo(card)
                else ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$authoredReference"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
            }
        },
    )
}

@Composable
internal fun NostrEventCard(
    state: NostrEventCardState,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: (NostrEventCardModel?) -> Unit,
    referenceLabel: String? = null,
) {
    val openDescription = stringResource(R.string.nostr_event_open)
    val copyDescription = stringResource(R.string.nostr_event_copy)
    Surface(
        color = Color.Transparent,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        EventCardBody(
            state = state,
            authorDisplayName = authorDisplayName,
            referenceLabel = referenceLabel,
            contentColor = contentColor,
            copyDescription = copyDescription,
            openDescription = openDescription,
            onRetry = onRetry,
            onCopy = onCopy,
            onOpen = onOpen,
        )
    }
}

@Composable
private fun EventCardBody(
    state: NostrEventCardState,
    authorDisplayName: (String) -> String,
    referenceLabel: String?,
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: (NostrEventCardModel?) -> Unit,
) {
    when (state) {
        NostrEventCardState.Loading ->
            EventCardStatus(
                text = stringResource(R.string.nostr_event_loading),
                referenceLabel = referenceLabel,
                contentColor = contentColor,
                progress = true,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onCopy = onCopy,
                onOpen = { onOpen(null) },
            )
        NostrEventCardState.NotFound ->
            EventCardFailure(
                text = stringResource(R.string.nostr_event_not_found),
                referenceLabel = referenceLabel,
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onRetry = onRetry,
                onCopy = onCopy,
                onOpen = { onOpen(null) },
            )
        NostrEventCardState.Invalid ->
            EventCardFailure(
                text = stringResource(R.string.nostr_event_invalid),
                referenceLabel = referenceLabel,
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onRetry = onRetry,
                onCopy = onCopy,
                onOpen = { onOpen(null) },
            )
        NostrEventCardState.Failed ->
            EventCardFailure(
                text = stringResource(R.string.nostr_event_failed),
                referenceLabel = referenceLabel,
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onRetry = onRetry,
                onCopy = onCopy,
                onOpen = { onOpen(null) },
            )
        is NostrEventCardState.Loaded ->
            LoadedEventCard(
                card = state.card,
                authorDisplayName = authorDisplayName,
                referenceLabel = referenceLabel,
                contentColor = contentColor,
                copyDescription = copyDescription,
                onCopy = onCopy,
                onOpen = { onOpen(state.card) },
            )
    }
}

@Composable
private fun EventCardActions(
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    openIcon: ImageVector = Icons.AutoMirrored.Outlined.OpenInNew,
) {
    Row {
        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = copyDescription,
                modifier = Modifier.size(18.dp),
                tint = contentColor.copy(alpha = 0.8f),
            )
        }
        IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
            Icon(
                openIcon,
                contentDescription = openDescription,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun LoadedEventCard(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    referenceLabel: String?,
    contentColor: Color,
    copyDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    val primaryAction = card.primaryAction()
    Column(Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 7.dp)) {
        LoadedEventHeader(
            card = card,
            authorDisplayName = authorDisplayName,
            contentColor = contentColor,
            copyDescription = copyDescription,
            openDescription = primaryAction.description,
            openIcon = primaryAction.icon,
            onCopy = onCopy,
            onOpen = onOpen,
        )
        card.title?.takeIf(String::isNotBlank)?.let { title ->
            Spacer(Modifier.height(3.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LoadedEventSummary(card.summary, contentColor)
        LoadedEventMetadata(card.metadata, contentColor)
        EventReferenceLabel(referenceLabel, contentColor)
    }
}

@Composable
private fun LoadedEventHeader(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    openIcon: ImageVector,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    val author = eventAuthorLabel(card, authorDisplayName)
    val kindAndDate = listOf(card.kind.label(card.eventKind), eventDateLabel(card.createdAt)).filter(String::isNotBlank)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = author,
            seed = card.authorPubkeyHex,
            size = 32.dp,
            pictureUrl = card.authorMetadata?.pictureUrl,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = author,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = card.kind.icon(),
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.66f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = kindAndDate.joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        EventCardActions(
            contentColor = contentColor,
            copyDescription = copyDescription,
            openDescription = openDescription,
            onCopy = onCopy,
            onOpen = onOpen,
            openIcon = openIcon,
        )
    }
}

@Composable
private fun LoadedEventSummary(
    summary: String?,
    contentColor: Color,
) {
    summary?.takeIf(String::isNotBlank)?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadedEventMetadata(
    metadata: List<String>,
    contentColor: Color,
) {
    if (metadata.isNotEmpty()) {
        Spacer(Modifier.height(3.dp))
        Text(
            text = metadata.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EventReferenceLabel(
    referenceLabel: String?,
    contentColor: Color,
) {
    referenceLabel?.takeIf(String::isNotBlank)?.let { reference ->
        Spacer(Modifier.height(3.dp))
        Text(
            text = reference,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.52f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EventCardStatus(
    text: String,
    referenceLabel: String?,
    contentColor: Color,
    progress: Boolean,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp, bottom = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            EventCardActions(contentColor, copyDescription, openDescription, onCopy, onOpen)
        }
        EventReferenceLabel(referenceLabel, contentColor)
    }
}

@Composable
private fun EventCardFailure(
    text: String,
    referenceLabel: String?,
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onRetry,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            }
        }
        EventReferenceLabel(referenceLabel, contentColor)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EventCardActions(contentColor, copyDescription, openDescription, onCopy, onOpen)
        }
    }
}

internal fun compactEventReference(authoredReference: String): String {
    val trimmed = authoredReference.trim()
    val normalized = if (trimmed.startsWith("nostr:", ignoreCase = true)) trimmed.substring(6) else trimmed
    if (normalized.length <= COMPACT_REFERENCE_LENGTH) return normalized
    return "${normalized.take(COMPACT_REFERENCE_PREFIX)}…${normalized.takeLast(COMPACT_REFERENCE_SUFFIX)}"
}

private const val MAX_CARDS_PER_MESSAGE = 3
private const val COMPACT_REFERENCE_LENGTH = 28
private const val COMPACT_REFERENCE_PREFIX = 14
private const val COMPACT_REFERENCE_SUFFIX = 7
