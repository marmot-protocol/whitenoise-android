@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.state.ConversationController

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
 * The disappearing-messages sheet for chat details. A pick that prunes history asks inside the same sheet;
 * everything else applies on tap. [onApply] runs the retention mutation; [resetKeys] drop the open sheet and
 * any pending confirmation on account/runtime change. [currentSecs] is the group's protocol value.
 */
@Composable
@Suppress("LongParameterList")
internal fun DisappearingTimerEditor(
    open: Boolean,
    currentSecs: ULong,
    editable: Boolean,
    identity: (@Composable () -> Unit)?,
    resetKeys: Array<out Any?>,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
) {
    val latestSecs by rememberUpdatedState(currentSecs)
    val currentEditable by rememberUpdatedState(editable)
    val currentOnApply by rememberUpdatedState(onApply)
    if (open) {
        // The raw protocol value keys a pending choice, since timers past Long.MAX_VALUE clamp to one shown value.
        key(resetKeys.toList(), currentSecs) {
            val shownSecs = currentSecs.clampedDisappearingSecs()
            DisappearingMessagesPickerSheet(
                currentSecs = shownSecs,
                onDismiss = onDismiss,
                onPick = { secs ->
                    // A changed timer invalidates any choice made against the previous value.
                    if (currentEditable && latestSecs == currentSecs && secs != shownSecs) {
                        currentOnApply(secs)
                    }
                },
                requiresConfirmation = { secs ->
                    disappearingPickOutcome(shownSecs, secs) == DisappearingPick.Confirm
                },
                confirmIdentity = identity,
                confirmEnabled = editable,
            )
        }
    }
}
