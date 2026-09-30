package dev.ipf.whitenoise.android.audio

/** Observes visibility without storing the private conversation identity in the diagnostic trace. */
internal class DictationDiagnosticLifecycle {
    private var originVisible: Boolean? = null

    fun originVisibility(
        controller: () -> ConversationDictationController,
        visible: (ConversationDictationTarget) -> Boolean,
    ) {
        if (DictationDiagnostics.activeSession == 0L) {
            originVisible = null
            return
        }
        val active = controller()
        val target = active.state.target ?: return
        val next = visible(target)
        if (next != originVisible) {
            originVisible = next
            conversationDictationDiagnostic(
                "event=origin_visibility visible=$next session=${active.state.sessionId}",
            )
        }
    }

    fun foreground(controller: () -> ConversationDictationController) {
        if (DictationDiagnostics.activeSession != 0L) {
            conversationDictationDiagnostic(
                "event=app_visibility foreground=true durable=${controller().hasDurableSession}",
            )
        }
    }
}
