@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.updateMessageRetention
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/** Whether this person may change the group's settings, including the disappearing-messages timer. */
internal val ConversationController.canEditGroupSettings: Boolean
    get() =
        !group.pendingConfirmation &&
            isSelfMember &&
            isSelfAdmin &&
            !group.disbanding &&
            !group.disbanded

/** The timer opens its picker only for an editor whose mutations are not blocked; otherwise it is read-only. */
internal fun disappearingTimerEditable(
    canEdit: Boolean,
    mutationsBlocked: Boolean,
): Boolean = canEdit && !mutationsBlocked

internal enum class DisappearingPick { Skip, Confirm, Apply }

/**
 * Turning the timer on or shortening it prunes existing history, so only those picks ask first (#674 review).
 * An unchanged pick does nothing; turning it off or lengthening it applies directly.
 */
internal fun disappearingPickOutcome(
    currentSecs: Long,
    secs: Long,
): DisappearingPick =
    when {
        secs == currentSecs -> DisappearingPick.Skip
        secs > 0L && (currentSecs == 0L || secs < currentSecs) -> DisappearingPick.Confirm
        else -> DisappearingPick.Apply
    }

/**
 * The disappearing-messages picker and its destructive confirmation, shared by chat details and the header
 * timer. [onApply] runs the retention mutation; [resetKeys] drop a pending confirmation on account/runtime change.
 */
@Composable
@Suppress("LongParameterList")
internal fun DisappearingTimerEditor(
    open: Boolean,
    currentSecs: Long,
    editable: Boolean,
    identity: (@Composable () -> Unit)?,
    resetKeys: Array<out Any?>,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
) {
    var pendingSecs by remember(*resetKeys) { mutableStateOf<Long?>(null) }
    if (open) {
        DisappearingMessagesPickerDialog(
            currentSecs = currentSecs,
            onDismiss = onDismiss,
            onPick = { secs ->
                onDismiss()
                when (disappearingPickOutcome(currentSecs, secs)) {
                    DisappearingPick.Skip -> Unit
                    DisappearingPick.Confirm -> pendingSecs = secs
                    DisappearingPick.Apply -> onApply(secs)
                }
            },
        )
    }
    pendingSecs?.let { secs ->
        ConfirmDialog(
            title = stringResource(R.string.disappearing_confirm_title),
            message = stringResource(R.string.disappearing_confirm_message, disappearingMessagesLabel(secs)),
            confirmLabel = stringResource(R.string.disappearing_confirm_button),
            onConfirm = {
                pendingSecs = null
                onApply(secs)
            },
            onDismiss = { pendingSecs = null },
            destructive = true,
            identity = identity,
            confirmEnabled = editable,
        )
    }
}

/**
 * Header timer apply path: the same retention command details runs, behind the same lock and owner fences.
 * Does nothing when another mutation or an account/runtime change makes the pick stale.
 */
internal fun ConversationController.applyDisappearingTimer(
    appState: WhiteNoiseAppState,
    reviewedRuntime: Int,
    secs: Long,
) {
    if (mutationInFlight || !isCurrentGroupMutationOwner(appState, reviewedRuntime)) return
    clearLastMutationError()
    val controller = this
    appState.launchMutation { controller.updateMessageRetention(secs.toULong()) }
}
