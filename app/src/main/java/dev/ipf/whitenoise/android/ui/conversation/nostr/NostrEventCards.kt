@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import kotlinx.coroutines.launch

@Composable
internal fun NostrEventCards(
    references: List<NostrEventReferenceOccurrence>,
    resolver: NostrEventCardResolver,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (references.isEmpty()) return
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.widthIn(min = 220.dp, max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        references.take(MAX_CARDS_PER_MESSAGE).forEach { occurrence ->
            key(occurrence.reference.stableId) {
                val flow = remember(resolver, occurrence.reference.stableId) { resolver.state(occurrence.reference) }
                val state by flow.collectAsState()
                val authoredReference = occurrence.authoredReference
                NostrEventCard(
                    state = state,
                    authorDisplayName = authorDisplayName,
                    contentColor = contentColor,
                    onRetry = { resolver.retry(occurrence.reference, flow) },
                    onCopy = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("Nostr event", "nostr:$authoredReference")),
                            )
                        }
                    },
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$authoredReference"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun NostrEventCard(
    state: NostrEventCardState,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
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
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    when (state) {
        NostrEventCardState.Loading ->
            EventCardStatus(
                text = stringResource(R.string.nostr_event_loading),
                contentColor = contentColor,
                progress = true,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onCopy = onCopy,
                onOpen = onOpen,
            )
        NostrEventCardState.NotFound ->
            EventCardFailure(
                text = stringResource(R.string.nostr_event_not_found),
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onRetry = onRetry,
                onCopy = onCopy,
                onOpen = onOpen,
            )
        NostrEventCardState.Invalid ->
            EventCardFailure(
                text = stringResource(R.string.nostr_event_invalid),
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onRetry = onRetry,
                onCopy = onCopy,
                onOpen = onOpen,
            )
        NostrEventCardState.Failed ->
            EventCardFailure(
                text = stringResource(R.string.nostr_event_failed),
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onRetry = onRetry,
                onCopy = onCopy,
                onOpen = onOpen,
            )
        is NostrEventCardState.Loaded ->
            LoadedEventCard(
                card = state.card,
                authorDisplayName = authorDisplayName,
                contentColor = contentColor,
                copyDescription = copyDescription,
                openDescription = openDescription,
                onCopy = onCopy,
                onOpen = onOpen,
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
                Icons.AutoMirrored.Outlined.OpenInNew,
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
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
        LoadedEventHeader(card, authorDisplayName, contentColor, copyDescription, openDescription, onCopy, onOpen)
        LoadedEventSummary(card.summary, contentColor)
        LoadedEventMetadata(card.metadata, contentColor)
    }
}

@Composable
private fun LoadedEventHeader(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(contentColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = card.kind.icon(),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = card.kind.label(card.eventKind),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.title?.takeIf(String::isNotBlank)?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = eventByline(card, authorDisplayName),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EventCardActions(contentColor, copyDescription, openDescription, onCopy, onOpen)
            }
        }
    }
}

@Composable
private fun LoadedEventSummary(
    summary: String?,
    contentColor: Color,
) {
    summary?.takeIf(String::isNotBlank)?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 4,
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
        Spacer(Modifier.height(6.dp))
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
private fun EventCardStatus(
    text: String,
    contentColor: Color,
    progress: Boolean,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
}

@Composable
private fun EventCardFailure(
    text: String,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            EventCardActions(contentColor, copyDescription, openDescription, onCopy, onOpen)
        }
    }
}

private const val MAX_CARDS_PER_MESSAGE = 3
