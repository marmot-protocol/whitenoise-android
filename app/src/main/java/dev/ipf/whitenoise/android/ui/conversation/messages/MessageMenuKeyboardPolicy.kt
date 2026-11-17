package dev.ipf.whitenoise.android.ui.conversation.messages

internal enum class MessageMenuKeyboardOwner { Composer, Search }

/** One transient restore intent, owned by a single conversation/controller lifetime. */
internal class MessageMenuKeyboardPolicy {
    var pendingRestore = false
        private set
    var resumeRestoreCancelled = false
        private set
    private var owner = MessageMenuKeyboardOwner.Composer
    private var replyAtOpen: String? = null
    private var editAtOpen: String? = null

    fun open(
        imeOpen: Boolean,
        replyTarget: String?,
        editTarget: String?,
        focusOwner: MessageMenuKeyboardOwner = MessageMenuKeyboardOwner.Composer,
        restoreInFlight: Boolean = false,
    ): Boolean {
        pendingRestore = imeOpen || restoreInFlight
        owner = focusOwner
        replyAtOpen = replyTarget
        editAtOpen = editTarget
        return pendingRestore
    }

    /** Only a newly established target delegates the raise to Reply/Edit. Removal is a dismissal. */
    fun close(
        replyTarget: String?,
        editTarget: String?,
        selectionMode: Boolean = false,
    ): MessageMenuKeyboardOwner? {
        val takeover =
            (replyTarget != null && replyTarget != replyAtOpen) ||
                (editTarget != null && editTarget != editAtOpen)
        val restore = owner.takeIf { pendingRestore && !takeover && !selectionMode }
        cancel()
        return restore
    }

    fun pause() {
        resumeRestoreCancelled = true
        cancel()
    }

    fun resumeConsumed() {
        resumeRestoreCancelled = false
    }

    fun cancel() {
        pendingRestore = false
        replyAtOpen = null
        editAtOpen = null
    }
}
