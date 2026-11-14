@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.common.selectionRowIcon
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val StoredAttachmentPreviewSize = 40.dp

/** The list section: a heading, the rows largest first, then loading, error or empty copy. */
internal fun LazyListScope.storageAttachmentItems(
    appState: WhiteNoiseAppState,
    list: StoredAttachmentsUiState,
    showChat: Boolean,
    busy: Boolean,
    actions: StoredAttachmentsActions,
) {
    item(key = "storage.attachments.heading") {
        Box(Modifier.padding(bottom = WhiteNoiseSpacing.Related)) {
            SettingsSection(stringResource(R.string.storage_largest_first))
        }
    }
    val count = list.items.size
    list.items.forEachIndexed { index, attachment ->
        item(key = "storage.attachment.${attachment.key}") {
            // Rows are separate lazy items for paging, so each carries the group margin and segmented gap itself.
            Box(
                Modifier
                    .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                    .padding(bottom = if (index < count - 1) WhiteNoiseListItemDefaults.segmentedGap else 0.dp),
            ) {
                StoredAttachmentRow(
                    appState = appState,
                    attachment = attachment,
                    context = settingsRowContext(index, count),
                    showChat = showChat,
                    selected = attachment.key in list.selected,
                    enabled = !busy,
                    onToggle = { actions.onToggle(attachment.key) },
                )
            }
        }
    }
    item(key = "storage.attachments.footer") {
        Box(Modifier.padding(top = WhiteNoiseSpacing.Related))
        when {
            list.failed ->
                StorageListRetry(R.string.storage_attachments_failed, enabled = !busy, onRetry = actions.onRetry)
            list.complete && count == 0 -> SettingsExplainer(stringResource(R.string.storage_no_attachments))
            !list.complete -> {
                // The footer composes only once scrolled into view, so the next page loads on approach.
                LaunchedEffect(count) { actions.onLoadMore() }
                StorageLoadingRow()
            }
        }
    }
}

/**
 * One attachment: its preview or kind glyph, kind, origin and size. Peer-supplied file names are never shown.
 * Tapping selects it, and the row reports its selected state so TalkBack announces it.
 */
@Composable
@Suppress("LongParameterList")
private fun StoredAttachmentRow(
    appState: WhiteNoiseAppState,
    attachment: StoredAttachment,
    context: SettingsRowContext,
    showChat: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val kind = attachment.kind
    val time = attachment.receivedAtSeconds.takeIf { it > 0 }?.let { rememberedRelativeTime(it.toULong()) }
    val origin = if (showChat) storageChatTitle(appState, attachment.groupIdHex) else null
    SettingsLink(
        context = context,
        title = stringResource(kind.labelRes),
        subtitle = listOfNotNull(origin, time).joinToString(" · ").ifEmpty { null },
        onClick = onToggle,
        enabled = enabled,
        modifier = Modifier.testTag("storage.attachment").semantics { this.selected = selected },
        explanation = stringResource(R.string.explanation_privacy_storage_attachment),
        leading = { StoredAttachmentPreview(appState, attachment, kind) },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                Text(
                    storageBytes(attachment.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    selectionRowIcon(selected),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
        },
    )
}

/** A decoded preview already held this session, otherwise the kind glyph, nothing is downloaded or decoded here. */
@Composable
private fun StoredAttachmentPreview(
    appState: WhiteNoiseAppState,
    attachment: StoredAttachment,
    kind: StoredAttachmentKind,
) {
    val account = appState.activeAccountRef
    val bitmap =
        account
            ?.takeIf { kind == StoredAttachmentKind.Photo || kind == StoredAttachmentKind.Video }
            ?.let { mediaCacheKey(it, attachment.groupIdHex, attachment.messageIdHex, attachment.attachmentIndex) }
            ?.let(appState::cachedMediaThumbnail)
    if (bitmap == null) {
        StoredAttachmentGlyph(kind)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(StoredAttachmentPreviewSize).clip(MaterialTheme.shapes.small),
        )
    }
}
