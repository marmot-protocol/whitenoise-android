package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.confirmedReplyTarget
import dev.ipf.whitenoise.android.ui.common.ClipboardCopier
import dev.ipf.whitenoise.android.ui.conversation.PollMessageActionOwner
import dev.ipf.whitenoise.android.ui.conversation.currentPollActionTarget
import dev.ipf.whitenoise.android.ui.conversation.currentPollCopyText
import dev.ipf.whitenoise.android.ui.conversation.reactions.LocalReactionMotion

/**
 * One bubble's message actions: reply, Info, delete, react, copy, forward and edit, as the action menu, row
 * actions, reply swipe, double tap, emoji picker, reaction sheet and delete dialog invoke them.
 *
 * An instance lasts as long as the bubble keeps its [appState], [controller], [surfaces] and [pollOwner]. Every
 * other value an action needs comes from [latest], the most recent composition that used this instance. Each action
 * reads it once when it runs and uses that one set of values throughout, including inside the mutation it launches,
 * as the local functions it replaces did with the values they captured. Once a confirmed send's record id replaces
 * [pollOwner] and so the instance, an action still holding this instance keeps the values from its own lifetime,
 * except that Reply, Edit and a reaction target the confirmed message (see [actionRecord]).
 */
internal class MessageBubbleActions(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val surfaces: MessageBubbleSurfaceState,
    private val pollOwner: PollMessageActionOwner,
    private val latest: State<Inputs>,
) {
    /** The values one composition of the bubble computed for its actions. */
    internal data class Inputs(
        val item: TimelineMessage,
        val isPollRecord: Boolean,
        val deleted: Boolean,
        val actionsReadOnly: Boolean,
        val deleteCapability: MessageDeleteCapability,
        val forwardPayload: ForwardMessagePayload?,
        val visibleTranslation: String?,
        val displayedBody: String,
        val messageTextCopy: MessageTextCopy,
        val localReactionMotion: LocalReactionMotion,
        val clipboard: ClipboardCopier,
        val onActionMenuOpenChange: (Boolean) -> Unit,
        val onForwardMenuHandoff: () -> Unit,
    ) {
        val record: AppMessageRecordFfi get() = item.record
    }

    /**
     * The message Reply, Edit and a reaction act on. Usually [Inputs.record]. A menu action queued behind the exit
     * animation can run on a replaced instance after the pending send it was opened for confirmed; that send's
     * row is then gone, and its client token resolves through the controller's local-send mapping to the confirmed
     * row, so Edit opens the text the timeline now shows. When nothing resolves, the record is used unchanged.
     */
    private fun Inputs.actionRecord(): AppMessageRecordFfi {
        if (controller.currentActionTimelineItem(record.messageIdHex) != null) return record
        val confirmedId = controller.confirmedReplyTarget(record.messageIdHex).takeIf { it != record.messageIdHex }
        return confirmedId?.let(controller::currentActionTimelineItem)?.record ?: record
    }

    /** Starts a reply to this message, or to the current poll target, unless it is deleted or read-only. */
    fun beginReply() {
        with(latest.value) {
            if (deleted || actionsReadOnly) return
            val target =
                if (isPollRecord) currentPollActionTarget(controller, pollOwner)?.record ?: return else actionRecord()
            controller.replyingTo = target
            onActionMenuOpenChange(false)
        }
    }

    /** Closes the action menu and shows Info, unless the message is already deleted. */
    fun openInfoSheet() {
        with(latest.value) {
            surfaces.infoOrigin = null
            if (deleted) return
            onActionMenuOpenChange(false)
            surfaces.infoSheetOpen = true
        }
    }

    /** Closes the action menu and asks which delete scope to use, when any scope is offered. */
    fun requestDelete() {
        with(latest.value) {
            if (!deleteCapability.canDeleteAtAll) return
            onActionMenuOpenChange(false)
            surfaces.deleteDialogOpen = true
        }
    }

    /** Hides the message on this device only, closing the dialog once the controller confirms the removal. */
    fun performDeleteForMe() {
        with(latest.value) {
            if (surfaces.deleteInFlight) return
            surfaces.deleteInFlight = true
            appState.launchMutation {
                try {
                    val removed = controller.hideMessageForMe(record.messageIdHex)
                    if (removed) surfaces.deleteDialogOpen = false
                } finally {
                    surfaces.deleteInFlight = false
                }
            }
        }
    }

    /** Deletes the message for every member, keeping the dialog open until the outcome is known. */
    fun performDeleteForEveryone() {
        with(latest.value) {
            // The in-flight flag is the repeated-tap guard on top of the
            // controller's own idempotency (a second deleteMessage on an already
            // tombstoned id is a no-op).
            if (surfaces.deleteInFlight) return
            surfaces.deleteInFlight = true
            // launchMutation so the MLS commit + Nostr publish survive navigating
            // away from the conversation. The dialog stays open with its options
            // disabled until the outcome is known, and stays open on failure so
            // the error toast never explains a surface that silently vanished;
            // the optimistic tombstone rollback restores the bubble either way.
            appState.launchMutation {
                try {
                    val removed = controller.deleteMessage(record, optimisticKeyOverride = item.id)
                    if (removed) surfaces.deleteDialogOpen = false
                } finally {
                    // Cancellation must not leave the flag stuck true, which would
                    // disable deletion for this bubble's remember scope.
                    surfaces.deleteInFlight = false
                }
            }
        }
    }

    fun reactWithEmoji(emoji: String) {
        with(latest.value) {
            // Chokepoint guard: never react to a deleted message, whatever path
            // (menu, emoji picker) called in — even if that surface was open when
            // the delete landed.
            if (deleted || actionsReadOnly) return
            // Route via launchMutation: same survives-navigation rationale as delete/send.
            val reactionOrigin = localReactionMotion.takeOrigin(emoji)
            val actionRecord = if (isPollRecord) record else actionRecord()
            appState.launchMutation {
                val target =
                    if (isPollRecord) {
                        currentPollActionTarget(controller, pollOwner)?.record ?: return@launchMutation
                    } else {
                        actionRecord
                    }
                controller.toggleReaction(emoji, target, onLocalIntent = { added ->
                    localReactionMotion.accept(emoji, added, reactionOrigin)
                }) {
                    !isPollRecord || currentPollActionTarget(controller, pollOwner) != null
                }
            }
        }
    }

    /** Copies the whole message as plain text, a poll as its question and options from the current row. */
    fun copyMessageText() {
        with(latest.value) {
            if (deleted) return
            // Polls use the current question/options; ordinary messages copy the visible rendition.
            val text =
                if (isPollRecord) {
                    currentPollCopyText(controller, pollOwner, messageTextCopy)
                } else {
                    visibleTranslation ?: displayedBody
                }
            text?.let { clipboard.copy(it) }
            onActionMenuOpenChange(false)
        }
    }

    /** Copies a link destination to the clipboard and closes the action menu. */
    fun copyMarkdownLink(url: String) {
        with(latest.value) {
            if (deleted) return
            clipboard.copy(url)
            onActionMenuOpenChange(false)
        }
    }

    /** Opens the forward sheet on a snapshot of the message's forwardable payload. */
    fun beginForward() {
        with(latest.value) {
            surfaces.forwardOrigin = null
            if (deleted || actionsReadOnly || forwardPayload == null) return
            // Keep the dimmed menu below the picker until its window is dismissed.
            onForwardMenuHandoff()
            surfaces.forwardSnapshot = forwardPayload
            surfaces.forwardSheetOpen = true
        }
    }

    /** Opens this message in the edit composer; reply and edit modes are mutually exclusive in the composer banner. */
    fun beginEdit() {
        with(latest.value) {
            if (deleted || actionsReadOnly) return
            onActionMenuOpenChange(false)
            controller.replyingTo = null
            controller.beginMessageEdit(actionRecord().messageIdHex)
        }
    }
}

/**
 * Remembers [MessageBubbleActions] for the bubble's owner: its app state, controller, surfaces and poll owner, the
 * values the old local functions captured that stay fixed for a mounted message. [inputs] is this composition's
 * snapshot of everything else and is only read when an action runs, so it adds no snapshot read to composition and
 * a new row, menu callback or delete state never needs a new instance. It is updated on every composition like
 * `rememberUpdatedState`, but in a state remembered on the same keys as the instance, so a replaced instance, such
 * as one a menu action queued behind the exit animation still holds after a pending send confirms, never sees the
 * confirmed message's values.
 */
@Composable
internal fun rememberMessageBubbleActions(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    surfaces: MessageBubbleSurfaceState,
    pollOwner: PollMessageActionOwner,
    inputs: MessageBubbleActions.Inputs,
): MessageBubbleActions {
    val latest = remember(appState, controller, surfaces, pollOwner) { mutableStateOf(inputs) }
    latest.value = inputs
    return remember(appState, controller, surfaces, pollOwner) {
        MessageBubbleActions(
            appState = appState,
            controller = controller,
            surfaces = surfaces,
            pollOwner = pollOwner,
            latest = latest,
        )
    }
}
