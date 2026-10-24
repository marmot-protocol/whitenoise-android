package dev.ipf.whitenoise.android.ui.conversation

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
