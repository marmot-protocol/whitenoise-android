package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteBreakdown
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteScope

@Composable
@Suppress("FunctionNaming")
internal fun BatchMessageDeleteConfirmation(
    selectedCount: Int,
    breakdown: BatchDeleteBreakdown,
    deleteInFlight: Boolean,
    onDelete: (BatchDeleteScope) -> Unit,
    onDismiss: () -> Unit,
) {
    if (selectedCount == 0) return
    BatchMessageDeleteDialog(
        selectedCount = selectedCount,
        breakdown = breakdown,
        deleteInFlight = deleteInFlight,
        onDeleteForEveryone = { onDelete(BatchDeleteScope.EVERYONE) },
        onDeleteForMe = { onDelete(BatchDeleteScope.LOCAL_ONLY) },
        onDismissRequest = onDismiss,
    )
}

@Composable
@Suppress("FunctionNaming")
internal fun MessageBubbleDeleteConfirmation(
    surfaces: MessageBubbleSurfaceState,
    capability: MessageDeleteCapability,
    mine: Boolean,
    senderDisplayName: String,
    actions: MessageBubbleActions,
) {
    MessageDeleteDialog(
        capability = capability,
        mine = mine,
        senderDisplayName = senderDisplayName,
        deleteInFlight = surfaces.deleteInFlight,
        onDeleteForEveryone = actions::performDeleteForEveryone,
        onDeleteForMe = actions::performDeleteForMe,
        onDismissRequest = { surfaces.deleteDialogOpen = false },
    )
}
