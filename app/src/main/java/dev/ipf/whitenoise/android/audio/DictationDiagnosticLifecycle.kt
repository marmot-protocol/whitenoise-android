package dev.ipf.whitenoise.android.audio

/** Observes visibility without storing the private conversation identity in the diagnostic trace. */
internal class DictationDiagnosticLifecycle {
    private var originVisible: Boolean? = null

    fun originVisibility(
        controller: ConversationDictationController,
        visible: (ConversationDictationTarget) -> Boolean,
    ) {
        val target = if (DictationDiagnostics.activeSession != 0L) controller.state.target else null
        if (target == null) {
            originVisible = null
            return
        }
        val next = visible(target)
        if (next != originVisible) {
            originVisible = next
            conversationDictationDiagnostic(
                "event=origin_visibility visible=$next session=${controller.state.sessionId}",
            )
        }
    }

    fun foreground(controller: ConversationDictationController) {
        if (DictationDiagnostics.activeSession != 0L) {
            conversationDictationDiagnostic(
                "event=app_visibility foreground=true durable=${controller.hasDurableSession}",
            )
        }
    }
}
