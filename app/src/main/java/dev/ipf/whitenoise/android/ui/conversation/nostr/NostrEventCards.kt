@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCopier
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick

/** Renders bounded event cards and their lifecycle-bound fullscreen viewers. */
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
    var videoSelection by remember(resolver) { mutableStateOf<Pair<NostrEventCardModel, Rect?>?>(null) }
    var readerSelection by remember(resolver) { mutableStateOf<NostrEventReaderSelection?>(null) }
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
                    mentionDisplayName = mentionDisplayName,
                    parseMarkdown = parseMarkdown,
                    onPlayVideo = { card, origin -> videoSelection = card to origin },
                    onReadEvent = { card, authoredReference, origin ->
                        readerSelection = NostrEventReaderSelection(card, authoredReference, origin)
                    },
                )
            }
        }
    }
    readerSelection?.let { selection ->
        NostrEventReaderDialog(
            card = selection.card,
            origin = selection.origin,
            authoredReference = selection.authoredReference,
            authorDisplayName = authorDisplayName,
            mentionDisplayName = mentionDisplayName,
            onNostrProfileTap = onNostrProfileTap,
            parseMarkdown = parseMarkdown,
            onDismiss = { readerSelection = null },
        )
    }
    videoSelection?.let { (card, origin) ->
        card.mediaUrl?.let { url ->
            NostrVideoPlayerDialog(url, origin = origin, onDismiss = { videoSelection = null })
        }
    }
}

private data class NostrEventReaderSelection(
    val card: NostrEventCardModel,
    val authoredReference: String?,
    val origin: Rect?,
)

/** Connects one resolved card to its independent retry, copy, external-open, and reader actions. */
@Composable
private fun ResolvedNostrEventCard(
    occurrence: NostrEventReferenceOccurrence,
    resolver: NostrEventCardResolver,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    mentionDisplayName: (String) -> String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    onReadEvent: (NostrEventCardModel, String?, Rect?) -> Unit,
    onPlayVideo: (NostrEventCardModel, Rect?) -> Unit,
) {
    var launchOrigin: Rect? = null
    val context = LocalContext.current
    val clipboard = rememberClipboardCopier()
    val flow =
        remember(resolver, occurrence.reference.stableId) {
            resolver.state(occurrence.reference, occurrence.authoredReference)
        }
    val state by flow.collectAsState()
    val presentedState = rememberNostrEventPreviewState(state, mentionDisplayName, parseMarkdown)
    val authoredReference = occurrence.authoredReference
    NostrEventCard(
        state = presentedState,
        authorDisplayName = authorDisplayName,
        referenceLabel = compactEventReference(authoredReference),
        contentColor = contentColor,
        onRetry = { resolver.retry(occurrence.reference, flow, occurrence.authoredReference) },
        onCopy = {
            clipboard.copy("nostr:$authoredReference", "Nostr event")
        },
        onReadNote = { card -> onReadEvent(card, authoredReference, launchOrigin) },
        onPlayVideo = { card -> onPlayVideo(card, launchOrigin) },
        onLaunchOrigin = { launchOrigin = it },
        onOpen = { card ->
            when {
                card != null -> onReadEvent(card, authoredReference, launchOrigin)
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
    onPlayVideo: (NostrEventCardModel) -> Unit = {},
    onLaunchOrigin: (Rect?) -> Unit = {},
) {
    val openDescription = stringResource(R.string.nostr_event_open)
    val copyDescription = stringResource(R.string.nostr_event_copy)
    Surface(
        color = Color.Transparent,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth().testTag(NOSTR_EVENT_CARD_BOUNDS_TAG),
    ) {
        NostrEventCardLayout(
            maximumHeight = nostrEventPreviewMaximumHeight(),
            onLaunchOrigin = onLaunchOrigin,
            onExpand = { onOpen((state as? NostrEventCardState.Loaded)?.card) },
        ) {
            EventCardBody(
                onLaunchOrigin = onLaunchOrigin,
                onPlayVideo = onPlayVideo,
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
    onPlayVideo: (NostrEventCardModel) -> Unit,
    onLaunchOrigin: (Rect?) -> Unit,
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
                contentColor = contentColor,
                copyDescription = copyDescription,
                onCopy = onCopy,
                onOpen = { onOpen(state.card) },
                onReadNote = { onReadNote(state.card) },
                onPlayVideo = { onPlayVideo(state.card) },
                onLaunchOrigin = onLaunchOrigin,
            )
    }
}

/**
 * Renders the verified event summary and keeps its header actions separate from note reading. The card represents
 * its event, so the encoded reference is not repeated beneath it (#3317); Copy reference still returns it in full.
 */
@Composable
private fun LoadedEventCard(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    copyDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onReadNote: () -> Unit,
    onPlayVideo: () -> Unit,
    onLaunchOrigin: (Rect?) -> Unit,
) {
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp)) {
        LoadedEventHeader(
            card = card,
            authorDisplayName = authorDisplayName,
            contentColor = contentColor,
            copyDescription = copyDescription,
            openDescription = card.openDescription(),
            onLaunchOrigin = onLaunchOrigin,
            onCopy = onCopy,
            onOpen = onOpen,
        )
        card.title?.takeIf(String::isNotBlank)?.let { title ->
            Spacer(Modifier.height(3.dp))
            EmojiLabel(
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
            onLaunchOrigin = onLaunchOrigin,
        )
        LoadedEventMetadata(card.previewMetadata(), contentColor)
        card.imageUrls.firstOrNull()?.let { url ->
            NostrEventImagePane(url = url, compact = true)
        }
        if (card.mediaUrl != null) {
            val (playModifier, play) =
                rememberMeasuredControlClick {
                    onLaunchOrigin(it)
                    onPlayVideo()
                }
            TextButton(
                onClick = play,
                modifier = playModifier,
                colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
            ) {
                Text(stringResource(R.string.nostr_event_play_video))
            }
        }
    }
}

/** Keeps author and kind scannable beside the same copy and open controls for every loaded event. */
@Composable
private fun LoadedEventHeader(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onLaunchOrigin: (Rect?) -> Unit,
) {
    val (openModifier, open) =
        rememberMeasuredControlClick {
            onLaunchOrigin(it)
            onOpen()
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painter = painterResource(card.kind.iconRes(card.eventKind)),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor,
                )
                Text(
                    text = card.kind.label(card.eventKind),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EmojiLabel(
                text = eventAuthorLabel(card, authorDisplayName),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_content_copy),
                contentDescription = copyDescription,
                modifier = Modifier.size(18.dp),
                tint = contentColor.copy(alpha = 0.8f),
            )
        }
        IconButton(onClick = open, modifier = openModifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = openDescription,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        }
    }
}

private fun NostrEventCardModel.previewMetadata(): List<String> =
    when (kind) {
        NostrEventCardKind.Git -> metadata.mapNotNull(::gitPreviewField).take(2)
        NostrEventCardKind.Video -> metadata.filter { it.matches(PREVIEW_DURATION) }.take(1)
        NostrEventCardKind.Release,
        NostrEventCardKind.File,
        -> metadata.take(2)
        else -> emptyList()
    }

private val PREVIEW_DURATION = Regex("[0-9]+(?:s|:[0-9]{2})")

/** Makes readable verified previews expand while retaining distinct copy and open actions. */
@Composable
private fun LoadedEventSummary(
    card: NostrEventCardModel,
    contentColor: Color,
    onReadNote: () -> Unit,
    onLaunchOrigin: (Rect?) -> Unit,
) {
    card.summary?.takeIf(String::isNotBlank)?.let { summary ->
        Spacer(Modifier.height(4.dp))
        val (readModifier, read) =
            rememberMeasuredControlClick {
                onLaunchOrigin(it)
                onReadNote()
            }
        val modifier =
            if (!card.readerBody.isNullOrBlank()) {
                readModifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel =
                            stringResource(
                                if (card.kind == NostrEventCardKind.Note) {
                                    R.string.nostr_event_read_note
                                } else {
                                    R.string.nostr_event_expand
                                },
                            ),
                        role = Role.Button,
                        onClick = read,
                    ).minimumInteractiveComponentSize()
                    .testTag(NOSTR_NOTE_PREVIEW_ACTION_TAG)
            } else {
                Modifier
            }
        EmojiLabel(
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
        EmojiLabel(
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
internal const val NOSTR_EVENT_CARD_BOUNDS_TAG = "nostr-event-card-bounds"

/** Cards one message shows; references past this stay in the message prose (#3317). */
internal const val MAX_CARDS_PER_MESSAGE = 3
private const val COMPACT_REFERENCE_LENGTH = 28
private const val COMPACT_REFERENCE_PREFIX = 14
private const val COMPACT_REFERENCE_SUFFIX = 7
