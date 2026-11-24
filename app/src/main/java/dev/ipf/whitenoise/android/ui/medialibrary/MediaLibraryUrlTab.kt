@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaLaunchFailure
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeSurface
import dev.ipf.whitenoise.android.ui.settings.settingsCardMorph
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import java.net.URI

/** Links tab of the library. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UrlLibraryTab(
    tiles: SharedMediaTiles,
    listState: androidx.compose.foundation.lazy.LazyListState,
    appState: WhiteNoiseAppState,
    onJumpToMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    MonthSectionedColumn(
        sections = tiles.urlSections,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_urls),
        keyOf = { "${it.index}#${it.value.messageIdHex}#${it.value.url}" },
        messageIdOf = { it.value.messageIdHex },
        onJumpToMessage = onJumpToMessage,
    ) { indexed ->
        val entry = indexed.value
        UrlLibraryRow(
            entry = entry,
            appState = appState,
            onOpen = {
                runCatchingCancellable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure { error ->
                    appState.presentMediaLaunchFailure(
                        R.string.media_couldnt_open,
                        "MEDIA_LIBRARY_URL_OPEN",
                        error,
                    )
                }
            },
            onCopy = {
                clipboard.setText(AnnotatedString(entry.url))
            },
        )
    }
}

/** One link row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UrlLibraryRow(
    entry: MediaInventory.UrlEntry,
    appState: WhiteNoiseAppState,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    // No OpenGraph / link-preview cache exists in the app today, so this is the
    // URL-only v1: the host stands in for the title, with a globe glyph for the
    // favicon and no description. follow-up: when an in-conversation link-preview
    // cache lands, render the row immediately and populate title/desc/favicon
    // from it opportunistically; any Android-owned favicon/metadata fetch must
    // use SafeHttpsGet so safety is enforced at the network boundary.
    val host = remember(entry.url) { hostOf(entry.url) }
    val recordedAtLabel = rememberRelativeTimestamp(entry.recordedAt)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(rememberMediaLibraryRowSurface())
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .amoledSurfaceBorder(MaterialTheme.shapes.large)
                .combinedClickable(onClick = onOpen, onLongClick = onCopy)
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .amoledSurfaceBorder(RoundedCornerShape(Radii.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_language),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                host,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            EmojiLabel(
                "${appState.displayName(entry.sender)} · $recordedAtLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Host of an absolute URL for the URL-row title, falling back to the raw URL
// when it can't be parsed (the inventory already guarantees an http(s) prefix).
@Suppress("MaxLineLength")
private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

/** Registers the same measured outline for link and voice rows. */
@Composable
internal fun rememberMediaLibraryRowSurface(): Modifier =
    rememberSettingsNativeSurface(
        destination = null,
        fill = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        strokeColor =
            amoledSurfaceBorderStroke()?.let { MaterialTheme.colorScheme.outlineVariant }
                ?: Color.Unspecified,
    ).first.settingsCardMorph()
