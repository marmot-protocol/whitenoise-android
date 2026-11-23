@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.selectionRowIcon
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val StoredAttachmentPreviewSize = 40.dp

/** The check mark's touch target, Material's minimum. */
private val StoredAttachmentCheckTarget = 48.dp

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
                    onOpen = { actions.onOpen(attachment) },
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
 * One attachment: its preview or kind glyph, the sender's file name, then its size, the day it arrived and its chat,
 * with a check mark at the end. A missing or unreadable name falls back to the kind. Tapping the row opens the
 * attachment so it can be reviewed before removal; the check mark, or holding the row, selects it. The check mark is
 * its own checkbox for TalkBack, named after the attachment, and a selected row takes a tonal fill.
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
    onOpen: () -> Unit,
) {
    val kind = attachment.kind
    val kindLabel = stringResource(kind.labelRes)
    val name = remember(attachment.fileName) { storageDisplayName(attachment.fileName) }
    val title = name ?: kindLabel
    val time = attachment.receivedAtSeconds.takeIf { it > 0 }?.let { storageReceivedDate(it) }
    val origin = if (showChat) storageChatTitle(appState, attachment.groupIdHex) else null
    // Size leads the details, then the date, so the chat name is what shortens on a narrow row. The kind shows in the
    // leading glyph and, for an unnamed attachment, as the title.
    val details = listOfNotNull(storageBytes(attachment.bytes), time, origin).joinToString(" · ")
    ListItem(
        onClick = onOpen,
        onLongClick = onToggle,
        onLongClickLabel = stringResource(if (selected) R.string.storage_deselect else R.string.storage_select),
        shapes = context.shapes,
        modifier =
            Modifier
                .fillMaxWidth()
                .settingsRowBorder(context)
                .testTag("storage.attachment")
                .explainOnLongPress(
                    Explanation(title, stringResource(R.string.explanation_privacy_storage_attachment_open)),
                    holdToExplain = false,
                ),
        enabled = enabled,
        leadingContent = { StoredAttachmentPreview(appState, attachment, kind) },
        supportingContent = {
            Text(
                details,
                color = MaterialTheme.colorScheme.onSurfaceVariant.dimmedUnless(enabled),
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { StoredAttachmentCheck(title, selected, enabled, onToggle) },
        colors = storedAttachmentRowColors(context, selected),
        content = {
            Text(
                // Isolated and laid out by its own direction, so a name in another script cannot reorder the row.
                text = name?.let { "\u2068$it\u2069" } ?: kindLabel,
                color = MaterialTheme.colorScheme.onSurface.dimmedUnless(enabled),
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
    )
}

/** The row's check mark: a full-size checkbox target of its own, so selecting never opens the attachment. */
@Composable
private fun StoredAttachmentCheck(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(R.string.storage_select_named, title)
    Box(
        Modifier
            .size(StoredAttachmentCheckTarget)
            .testTag("storage.attachment.select")
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            selectionRowIcon(selected),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * The day an attachment arrived in the locale's medium date form, such as "14 Mar 2025". A full date, unlike the chat
 * list's relative times, keeps an old file's age readable when deciding what to remove.
 */
@Composable
private fun storageReceivedDate(epochSeconds: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochSeconds, locale) {
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
    }
}

/** The group fill at rest, and the picker groups' tonal highlight once selected. */
@Composable
private fun storedAttachmentRowColors(
    context: SettingsRowContext,
    selected: Boolean,
): ListItemColors {
    val selectedContainer =
        if (isAmoledSurfaceTheme()) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = SettingsRowDefaults.AmoledSelectionAlpha)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val container = if (selected) selectedContainer else context.containerColor
    return ListItemDefaults.colors(containerColor = container, disabledContainerColor = container)
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
