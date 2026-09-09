package dev.ipf.whitenoise.android.audio.tts

internal class TtsHistoryNavigation(
    private val conversation: () -> TtsConversationSource?,
    private val beforeNavigate: () -> Boolean,
    private val startEdgeLoad: (TtsConversationSource, TtsHistoryDirection, TtsWindowSentenceTarget) -> Unit,
    private val clearEdge: () -> Unit,
) {
    fun navigate(
        targetSentence: TtsWindowSentenceTarget,
        skip: (Boolean) -> TtsNavigationOutcome,
    ) {
        if (!beforeNavigate()) return
        val convo = conversation()
        if (convo == null) {
            skip(false)
            return
        }
        // The edge decision happens inside the controller lock, so a racing
        // engine callback can never turn "try to load" into an early
        // completion or an interior move into a bogus page request.
        when (skip(true)) {
            TtsNavigationOutcome.AtOlderEdge ->
                startEdgeLoad(convo, TtsHistoryDirection.Older, targetSentence)

            TtsNavigationOutcome.AtNewerEdge ->
                startEdgeLoad(convo, TtsHistoryDirection.Newer, targetSentence)

            else -> clearEdge()
        }
    }
}
