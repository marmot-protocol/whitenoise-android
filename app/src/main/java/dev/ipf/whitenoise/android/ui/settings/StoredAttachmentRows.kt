@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextOverflow.Companion.MiddleEllipsis
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DateTimeFormatState
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.selectionRowIcon
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

private val StoredAttachmentPreviewSize = 40.dp

/** A video thumbnail's play badge: a small translucent disc that leaves most of the frame visible. */
private val StoredAttachmentBadgeSize = 20.dp
private val StoredAttachmentBadgeInset = 2.dp
private const val STORED_ATTACHMENT_BADGE_ALPHA = 0.72f

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
                    // While a selection is open a tap adds to it, as in the chat list; otherwise it opens the
                    // attachment.
                    onClick = {
                        if (list.selecting) actions.onToggle(attachment.key) else actions.onOpen(attachment)
                    },
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
 * attachment so it can be reviewed before removal, or adds it to an open selection; the check mark, or holding the row,
 * selects it. The check mark is
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
    onClick: () -> Unit,
) {
    val kind = attachment.kind
    val kindLabel = stringResource(kind.labelRes)
    val name = remember(attachment.fileName) { storageDisplayName(attachment.fileName) }
    val title = name ?: kindLabel
    val time = attachment.receivedAtSeconds.takeIf { it > 0 }?.let { storageReceivedDate(it) }
    val origin = if (showChat) storageChatTitle(appState, attachment.groupIdHex) else null
    // Size leads the details, then the date and the chat, wrapping rather than clipping at large text sizes. The kind
    // shows in the leading glyph and, for an unnamed attachment, as the title.
    val details = listOfNotNull(storageBytes(attachment.bytes), time, origin).joinToString(" · ")
    ListItem(
        onClick = onClick,
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
            )
        },
        trailingContent = { StoredAttachmentCheck(title, selected, enabled, onToggle) },
        colors = storedAttachmentRowColors(context, selected),
        content = {
            Text(
                // Its own paragraph, laid out by the name's direction, so a name in another script cannot reorder the
                // row; the sanitized name holds no direction controls.
                text = name ?: kindLabel,
                color = MaterialTheme.colorScheme.onSurface.dimmedUnless(enabled),
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                // The middle ellipsis keeps a name's extension, but Compose clips the run it keeps at the far end of a
                // right-to-left name, so such a name ellipsizes at its end instead.
                overflow = if (name?.startsRightToLeft() == true) TextOverflow.Ellipsis else MiddleEllipsis,
            )
        },
    )
}

/** Whether the first letter with a direction reads right to left, as the paragraph laying out the name decides. */
private fun String.startsRightToLeft(): Boolean {
    val strong =
        codePoints()
            .map { Character.getDirectionality(it).toInt() }
            .filter { it in StrongDirections }
            .findFirst()
    return strong.isPresent && strong.asInt != Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt()
}

/** Bidirectional classes that set a paragraph's direction. */
private val StrongDirections =
    setOf(
        Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt(),
        Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(),
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt(),
    )

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
 * The day an attachment arrived in the user's date format, else the locale's medium form, such as "14 Mar 2025", or
 * null for a stamp too far out to show. A full date, unlike the chat list's relative times, keeps an old file's age
 * readable when deciding what to remove.
 */
@Composable
private fun storageReceivedDate(epochSeconds: Long): String? {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateTimeFormatState.dateFormat
    return remember(epochSeconds, locale, dateFormat) {
        IdentityFormatter.localDate(epochSeconds.toULong())?.let { IdentityFormatter.date(it, locale, dateFormat) }
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

/**
 * The attachment's thumbnail from this device, with a play badge on a video, or its kind glyph while none can be drawn.
 * See [rememberStoredAttachmentThumbnail] for where the pixels come from; nothing is downloaded.
 */
@Composable
private fun StoredAttachmentPreview(
    appState: WhiteNoiseAppState,
    attachment: StoredAttachment,
    kind: StoredAttachmentKind,
) {
    val thumbnail = rememberStoredAttachmentThumbnail(appState, attachment, kind)
    if (thumbnail == null) return StoredAttachmentGlyph(kind)
    Box(
        Modifier
            .size(StoredAttachmentPreviewSize)
            .clip(MaterialTheme.shapes.small)
            .testTag("storage.attachment.thumbnail"),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (kind == StoredAttachmentKind.Video) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = STORED_ATTACHMENT_BADGE_ALPHA),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(StoredAttachmentBadgeSize),
            ) {
                Icon(
                    painterResource(R.drawable.ic_play_arrow),
                    contentDescription = null,
                    modifier = Modifier.padding(StoredAttachmentBadgeInset),
                )
            }
        }
    }
}
