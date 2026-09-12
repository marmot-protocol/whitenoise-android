package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.ManualUnreadDot
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.common.selectionRowIcon
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

internal const val CHAT_ROW_SELECTION_INDICATOR_TAG = "chat-row-selection-indicator"

private val ChatRowContentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/** Native prototype list item with the production selection and metadata visibility contract. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun ChatRowLayout(
    title: String,
    timestampAt: ULong,
    // Retained for existing row-layout callers; the prototype timestamp uses a uniform color.
    @Suppress("UnusedParameter", "UNUSED_PARAMETER") rowHasUnread: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    leadingContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit,
    supportingMetadata: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    titleMetadata: @Composable () -> Unit = {},
    onClick: () -> Unit = {},
    interactionsEnabled: Boolean = true,
    consumeSelectionLongPress: Boolean = selectionMode,
    menuHighlighted: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val selectedColor = if (isAmoledSurfaceTheme()) Color.White.copy(alpha = 0.16f) else scheme.surfaceContainerHigh
    ListItem(
        onClick = onClick,
        enabled = interactionsEnabled,
        // A selection-mode hold must not turn into a tap on release. The range gesture, when active,
        // is owned by AnchoredDragSelection on the modifier and must remain the sole long-press owner.
        onLongClick = if (consumeSelectionLongPress) ({}) else null,
        modifier =
            modifier.semantics {
                this.selected = selected
                if (selectionMode) {
                    role = Role.Checkbox
                    toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                }
            },
        shapes = WhiteNoiseListItemDefaults.shapes(),
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected || menuHighlighted) selectedColor else scheme.surface,
            ),
        contentPadding = ChatRowContentPadding,
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) ChatRowSelectionIndicator(selected)
                leadingContent()
            }
        },
        content = {
            ChatRowTextLayout(
                title = {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                metadata = titleMetadata,
                timestamp = {
                    if (!selectionMode) {
                        Text(
                            rememberedRelativeTime(timestampAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                preview = {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { supportingContent() }
                    }
                },
                status = { if (!selectionMode) supportingMetadata?.invoke() },
            )
        },
    )
}

/**
 * Adapted from pinned prototype ChatListRow.kt. One content slot avoids Material's inherited supporting
 * baseline query during lazy reuse. Only the direct title/time Text baselines are read. Production's
 * preview can include a delivery Row; its measured height, rather than that container's inherited
 * baseline, selects the 72/88 dp minimum and leaves large fonts free to grow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ChatRowTextLayout(
    title: @Composable () -> Unit,
    metadata: @Composable () -> Unit,
    timestamp: @Composable () -> Unit,
    preview: @Composable () -> Unit,
    status: @Composable () -> Unit,
) {
    val verticalAlignment = ListItemDefaults.verticalAlignment()
    val padding = ChatRowContentPadding
    Layout(
        contents =
            listOf(
                title,
                { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { metadata() } },
                timestamp,
                preview,
                status,
            ),
    ) { slots, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailingGap = 8.dp.roundToPx()
        val time = slots[2].firstOrNull()?.measure(loose)
        val timeSpace = if (time == null) 0 else time.width + trailingGap
        val icons = slots[1].single().measure(loose.offset(horizontal = -timeSpace))
        val iconGap = if (icons.width > 0) 4.dp.roundToPx() else 0
        val name = slots[0].single().measure(loose.offset(horizontal = -timeSpace - icons.width - iconGap))
        val nameGroupHeight = maxOf(name.height, icons.height)
        val nameInset = (nameGroupHeight - name.height) / 2
        val baseline = maxOf(nameInset + name[FirstBaseline], time?.get(FirstBaseline) ?: 0)
        val groupY = baseline - nameInset - name[FirstBaseline]
        val timeY = time?.let { baseline - it[FirstBaseline] } ?: 0
        val headlineHeight = maxOf(groupY + nameGroupHeight, timeY + (time?.height ?: 0))
        val badge = slots[4].firstOrNull()?.measure(loose.offset(vertical = -headlineHeight))
        val badgeSpace = if (badge == null) 0 else badge.width + trailingGap
        val message = slots[3].single().measure(loose.offset(horizontal = -badgeSpace, vertical = -headlineHeight))
        val textHeight = headlineHeight + maxOf(message.height, badge?.height ?: 0)
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                constraints.constrainWidth(
                    maxOf(name.width + iconGap + icons.width + timeSpace, message.width + badgeSpace),
                )
            }
        val minimumHeight = if (message.height > name.height) 88.dp else 72.dp
        val contentMinimum =
            (minimumHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()).roundToPx()
        val height = constraints.constrainHeight(maxOf(textHeight, contentMinimum))
        val contentY = verticalAlignment.align(textHeight, height)
        layout(width, height) {
            name.placeRelative(0, contentY + groupY + nameInset)
            icons.placeRelative(name.width + iconGap, contentY + groupY + (nameGroupHeight - icons.height) / 2)
            time?.placeRelative(width - time.width, contentY + timeY)
            message.placeRelative(0, contentY + headlineHeight)
            badge?.placeRelative(width - badge.width, contentY + headlineHeight)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun ChatRowSupportingMetadata(
    pendingConfirmation: Boolean,
    rowHasUnread: Boolean,
    rowUnreadCount: ULong,
    unreadMention: Boolean,
    actionColors: AccountActionColors?,
    pinned: Boolean,
    evicted: Boolean = false,
) {
    if (pendingConfirmation) {
        Badge { Text(stringResource(R.string.invited)) }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (pinned) PinnedBadge()
            if (evicted) EvictedLabel()
            if (rowHasUnread) {
                if (unreadMention) MentionBadge()
                if (rowUnreadCount > 0uL) {
                    UnreadCountBadge(rowUnreadCount, actionColors = actionColors)
                } else {
                    ManualUnreadDot(actionColors = actionColors)
                }
            }
        }
    }
}

/** The row owns toggle semantics; its leading native checkbox is decorative and never a second target. */
@Suppress("FunctionNaming")
@Composable
private fun ChatRowSelectionIndicator(selected: Boolean) {
    Checkbox(
        checked = selected,
        onCheckedChange = null,
        modifier = Modifier.size(24.dp).clearAndSetSemantics { testTag = CHAT_ROW_SELECTION_INDICATOR_TAG },
    )
}

internal fun chatRowSelectionIcon(selected: Boolean): ImageVector = selectionRowIcon(selected)
