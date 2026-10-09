@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ReactionDetailsState
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Owns an exact-message read for either a chat bubble or a group activity's mounted details sheet. */
@Composable
internal fun CompleteReactionDetailsSheet(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onRemoveOwnReaction: ((String) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    val messageId = item.record.messageIdHex
    key(controller, messageId) {
        val details = remember { ReactionDetailsState() }
        var retry by remember { mutableStateOf(0) }
        // Revision changes include identity swaps that leave chip counts and bounded previews unchanged.
        val revision = controller.window.frame?.revision
        val tallies = controller.reactions[messageId]
        LaunchedEffect(revision, item.projected?.reactions, tallies, retry) {
            details.refresh { controller.loadReactionParticipants(messageId) }
        }
        val participants = details.participants?.let { controller.reactionParticipantsFor(messageId, it) }.orEmpty()
        // Keep readiness and emptiness from the same composition. A fast read can finish before an older
        // effect runs; reading live readiness there would pair it with that effect's captured empty list.
        val shouldDismiss = details.ready && participants.isEmpty()
        LaunchedEffect(shouldDismiss) {
            if (shouldDismiss) onDismissRequest()
        }
        ReactionDetailsSheet(
            participants = participants,
            appState = appState,
            readState =
                ReactionDetailsReadState(
                    loading = details.loading,
                    failed = details.failed,
                    hasSnapshot = details.participants != null,
                    onRetry = { retry++ },
                    viewerAccountId = controller.boundAccountIdHex,
                ),
            onRemoveOwnReaction = onRemoveOwnReaction,
            onDismissRequest = onDismissRequest,
        )
    }
}
