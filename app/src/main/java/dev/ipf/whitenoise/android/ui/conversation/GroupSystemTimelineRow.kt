@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageAction
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageActions
import dev.ipf.whitenoise.android.ui.conversation.reactions.CompleteReactionDetailsSheet
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillFlow

/** Transient overlay state scoped to one mounted activity row, never a second reaction store. */
private class GroupSystemActionSurface {
    var pickerOpen by mutableStateOf(false)
    var detailsOpen by mutableStateOf(false)
    var previewText by mutableStateOf("")
    var sourceBounds by mutableStateOf<IntRect?>(null)
}

/** Renders native activity reactions through the ordinary controller and focused action surfaces. */
@Composable
internal fun GroupSystemTimelineRow(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    quickReactionEmojis: List<String>,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    isActionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
    readOnly: Boolean,
    onWave: (suspend (String, () -> Unit) -> Unit)?,
) {
    val owner =
        remember(controller, item.record.messageIdHex) {
            GroupSystemReactionOwner(controller.boundAccountRef, item.record.groupIdHex, item.record.messageIdHex)
        }
    val currentReadOnly by rememberUpdatedState(readOnly)
    val currentItem by rememberUpdatedState(item)
    val actions =
        remember(controller, owner) {
            GroupSystemRowActions(controller, appState, owner, { currentReadOnly }, { currentItem })
        }
    val surface = remember(owner) { GroupSystemActionSurface() }
    GroupSystemActionLifetime(owner, actions, surface, onActionMenuOpenChange)
    GroupSystemRow(
        record = item.record,
        appState = appState,
        groupSystem = item.projected?.groupSystem,
        onWave = onWave,
        waveAccountRef = owner.accountRef,
        onOpenProfile = { appState.presentProfile(appState.npub(it)) },
        onOpenActions =
            if (actions.canReact || actions.canDelete) {
                { text, bounds ->
                    surface.previewText = text
                    surface.sourceBounds = bounds
                    onActionMenuOpenChange(true)
                }
            } else {
                null
            },
        reactionContent = {
            if (actions.tallies.isNotEmpty()) {
                ReactionPillFlow(
                    actions.tallies,
                    actions::react.takeIf { actions.canReact },
                    { surface.detailsOpen = true },
                )
            }
        },
    )
    if (isActionMenuOpen && (actions.canReact || actions.canDelete)) {
        GroupSystemFocusedActions(actions, surface, quickReactionEmojis, onActionMenuOpenChange)
    }
    GroupSystemReactionSheets(item, actions, surface, recentEmojis, onEmojiUsed)
}

/** Retires row-owned overlays on disposal or when native action admission disappears. */
@Composable
private fun GroupSystemActionLifetime(
    owner: GroupSystemReactionOwner,
    actions: GroupSystemRowActions,
    surface: GroupSystemActionSurface,
    onMenuChange: (Boolean) -> Unit,
) {
    val currentMenuChange by rememberUpdatedState(onMenuChange)
    DisposableEffect(owner) { onDispose { currentMenuChange(false) } }
    LaunchedEffect(actions.canReact, actions.canDelete, actions.hidden) {
        if (!actions.canReact) surface.pickerOpen = false
        if (!actions.canReact && !actions.canDelete) onMenuChange(false)
        if (actions.hidden) surface.detailsOpen = false
    }
}

/** Lifts only the localized summary and the activity's supported reaction/delete controls. */
@Composable
private fun GroupSystemFocusedActions(
    actions: GroupSystemRowActions,
    surface: GroupSystemActionSurface,
    quickReactionEmojis: List<String>,
    onMenuChange: (Boolean) -> Unit,
) {
    val delete =
        FocusedMessageAction(
            label = stringResource(R.string.delete_for_me),
            supportingLabel = null,
            enabled = true,
            destructive = true,
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                onMenuChange(false)
                actions.deleteForMe()
            },
        )
    FocusedMessageActions(
        sourceBounds = surface.sourceBounds,
        touchY = null,
        mine = false,
        actions = if (actions.canDelete) listOf(delete) else emptyList(),
        quickReactions = quickReactionEmojis,
        canReact = actions.canReact,
        selectedReactions =
            actions.tallies
                .filter { it.mine }
                .map { it.emoji }
                .toSet(),
        previewDescription = surface.previewText,
        previewReady = true,
        preview = {
            Text(
                surface.previewText,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        onReact = {
            onMenuChange(false)
            actions.react(it)
        },
        onMoreReactions = {
            onMenuChange(false)
            surface.pickerOpen = true
        },
        onDismiss = { onMenuChange(false) },
    )
}

/** Uses the shared picker and live reactor details, keeping read-only history inspectable. */
@Composable
private fun GroupSystemReactionSheets(
    item: TimelineMessage,
    actions: GroupSystemRowActions,
    surface: GroupSystemActionSurface,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
) {
    if (surface.pickerOpen && actions.canReact) {
        EmojiPickerSheet(
            recentEmojis = recentEmojis,
            onEmojiUsed = onEmojiUsed,
            onDismissRequest = { surface.pickerOpen = false },
            onEmojiPicked = {
                surface.pickerOpen = false
                actions.react(it)
            },
        )
    }
    if (surface.detailsOpen && !actions.hidden) {
        CompleteReactionDetailsSheet(
            item = item,
            controller = actions.controller,
            appState = actions.appState,
            onRemoveOwnReaction = actions::react.takeIf { actions.canReact },
            onDismissRequest = { surface.detailsOpen = false },
        )
    }
}
