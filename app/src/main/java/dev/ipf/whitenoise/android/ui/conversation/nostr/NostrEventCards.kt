@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import dev.ipf.whitenoise.android.ui.common.Avatar
import kotlinx.coroutines.launch

/** Renders bounded event cards and owns their lifecycle-bound note/article/video viewers. */
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
    var readerSelection by remember(resolver) { mutableStateOf<NostrEventReaderSelection?>(null) }
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
                    onReadEvent = { card, authoredReference ->
                        readerSelection = NostrEventReaderSelection(card, authoredReference)
                    },
                    onPlayVideo = { videoCard = it },
                )
            }
        }
    }
    readerSelection?.let { selection ->
        NostrEventReaderDialog(
            card = selection.card,
            authoredReference = selection.authoredReference,
            authorDisplayName = authorDisplayName,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            parseMarkdown = parseMarkdown,
            onDismiss = { readerSelection = null },
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

private data class NostrEventReaderSelection(
    val card: NostrEventCardModel,
    val authoredReference: String?,
)

/** Connects one resolved card to its independent retry, copy, external-open, and reader actions. */
@Composable
private fun ResolvedNostrEventCard(
    occurrence: NostrEventReferenceOccurrence,
    resolver: NostrEventCardResolver,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    onReadEvent: (NostrEventCardModel, String?) -> Unit,
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
        onReadNote = { card -> onReadEvent(card, authoredReference) },
        onOpen = { card ->
            when {
                card?.kind == NostrEventCardKind.Article && !card.readerBody.isNullOrBlank() ->
                    onReadEvent(card, null)
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

/** Renders a resolved or fallback event-card state with independent semantic actions. */
@Composable
internal fun NostrEventCard(
    state: NostrEventCardState,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: (NostrEventCardModel?) -> Unit,
    onReadNote: (NostrEventCardModel) -> Unit = {},
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
            onReadNote = onReadNote,
        )
    }
}

/** Selects the loaded, retryable, or loading card presentation without changing fallback actions. */
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
    onReadNote: (NostrEventCardModel) -> Unit,
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
                onReadNote = { onReadNote(state.card) },
            )
    }
}

/** Renders the verified event summary and keeps its header actions separate from note reading. */
@Composable
private fun LoadedEventCard(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    referenceLabel: String?,
    contentColor: Color,
    copyDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onReadNote: () -> Unit,
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
        LoadedEventSummary(
            card = card,
            contentColor = contentColor,
            onReadNote = onReadNote,
        )
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

/** Makes the whole visible kind-1 preview a button while leaving other summaries inert. */
@Composable
private fun LoadedEventSummary(
    card: NostrEventCardModel,
    contentColor: Color,
    onReadNote: () -> Unit,
) {
    card.summary?.takeIf(String::isNotBlank)?.let { summary ->
        Spacer(Modifier.height(4.dp))
        val modifier =
            if (card.kind == NostrEventCardKind.Note && !card.readerBody.isNullOrBlank()) {
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(R.string.nostr_event_read_note),
                        role = Role.Button,
                        onClick = onReadNote,
                    ).minimumInteractiveComponentSize()
                    .testTag(NOSTR_NOTE_PREVIEW_ACTION_TAG)
            } else {
                Modifier
            }
        Text(
            text = summary,
            modifier = modifier,
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

internal fun compactEventReference(authoredReference: String): String {
    val trimmed = authoredReference.trim()
    val normalized = if (trimmed.startsWith("nostr:", ignoreCase = true)) trimmed.substring(6) else trimmed
    if (normalized.length <= COMPACT_REFERENCE_LENGTH) return normalized
    return "${normalized.take(COMPACT_REFERENCE_PREFIX)}…${normalized.takeLast(COMPACT_REFERENCE_SUFFIX)}"
}

internal const val NOSTR_NOTE_PREVIEW_ACTION_TAG = "nostr-note-preview-action"

private const val MAX_CARDS_PER_MESSAGE = 3
private const val COMPACT_REFERENCE_LENGTH = 28
private const val COMPACT_REFERENCE_PREFIX = 14
private const val COMPACT_REFERENCE_SUFFIX = 7
