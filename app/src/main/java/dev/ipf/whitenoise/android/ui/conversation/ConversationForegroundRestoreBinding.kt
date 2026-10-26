package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** A restore revision is meaningful only within its exact coordinator owner. */
internal class ConversationForegroundRestoreBinding private constructor(
    val owner: ConversationScrollCoordinator,
    val token: ConversationForegroundRestoreToken,
) {
    fun releaseMeasuredFrame(
        retainedPresentationSafe: Boolean,
        timelineMeasured: Boolean,
    ): Boolean {
        if (!retainedPresentationSafe || !timelineMeasured) return false
        owner.releaseForegroundRestoreGate(token)
        return true
    }

    fun resume(
        currentOwner: ConversationScrollCoordinator,
        snapshot: () -> ConversationForegroundSnapshot,
    ): ConversationForegroundRestoreBinding {
        if (owner === currentOwner) return this
        cancel()
        return begin(currentOwner, snapshot())
    }

    fun cancel() {
        owner.cancelForegroundRestore(token)
    }

    companion object {
        fun canPresentRetainedFrame(
            paused: ConversationForegroundRestoreBinding?,
            currentOwner: ConversationScrollCoordinator,
            currentPresentationSafe: Boolean,
        ): Boolean = currentPresentationSafe && (paused == null || paused.owner === currentOwner)

        fun begin(
            owner: ConversationScrollCoordinator,
            snapshot: ConversationForegroundSnapshot,
        ): ConversationForegroundRestoreBinding =
            ConversationForegroundRestoreBinding(
                owner,
                owner.beginForegroundRestore(snapshot),
            )
    }
}

/** Transfers only restoration when runtime recomposition follows the actual resume event. */
@Composable
internal fun conversationForegroundOwnerHandoffEffect(
    currentOwner: ConversationScrollCoordinator,
    retained: ConversationForegroundRestoreBinding?,
    lifecycleOwner: LifecycleOwner?,
    onHandoff: () -> Unit,
) {
    DisposableEffect(currentOwner) {
        if (
            retained != null &&
            retained.owner !== currentOwner &&
            lifecycleOwner?.lifecycle?.currentState == Lifecycle.State.RESUMED
        ) {
            onHandoff()
        }
        // The existing controller-owned lifecycle observer owns cancellation and true teardown.
        onDispose { }
    }
}
