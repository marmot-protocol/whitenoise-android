package dev.ipf.whitenoise.android.audio.tts

internal class TtsHistoryProjection(
    private val controller: TtsController,
) {
    data class Tail(
        val attached: Boolean,
        val knownTailId: String?,
    )

    /** Extends the queue with a current edge-walk projection and updates live-tail ownership. */
    fun apply(
        pager: TtsHistoryPager,
        direction: TtsHistoryDirection,
        targetSentence: TtsWindowSentenceTarget,
        entries: List<TtsSpeakableEntry>,
        liveTailAttached: Boolean,
    ): Tail? {
        // The requested target is the nearest speakable message beyond the
        // edge: last in window order for older paging, first for newer.
        val targetId =
            when (direction) {
                TtsHistoryDirection.Older -> entries.last().messageIdHex
                TtsHistoryDirection.Newer -> entries.first().messageIdHex
            }
        val tailBefore = controller.queuedMessageIds().lastOrNull()
        if (!controller.extendReadAloudWindow(direction, entries, targetId, targetSentence)) return null
        val tailAfter = controller.queuedMessageIds().lastOrNull()
        var knownTailId: String? = null
        val attached =
            when (direction) {
                // Evicting the newest edge detaches the session from the live tail.
                TtsHistoryDirection.Older -> liveTailAttached && tailAfter == tailBefore
                // Reattached only when the queue tail is the timeline's live
                // tail RIGHT NOW — a walk-time snapshot would miss an arrival
                // that landed between the walk and this apply.
                TtsHistoryDirection.Newer -> {
                    val timelineTailId = pager.timelineRecords().lastOrNull()?.messageIdHex
                    val attached = !pager.hasMoreAfter && timelineTailId != null && timelineTailId == tailAfter
                    if (attached) knownTailId = timelineTailId
                    attached
                }
            }
        return Tail(attached, knownTailId)
    }
}
