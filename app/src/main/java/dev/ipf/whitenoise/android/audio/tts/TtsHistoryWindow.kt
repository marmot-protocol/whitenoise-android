package dev.ipf.whitenoise.android.audio.tts

/** Which side of the loaded read-aloud window a history request extends. */
enum class TtsHistoryDirection {
    Older,
    Newer,
}

// Upper bound on queued messages a paged read-aloud session may hold. Keeps
// the decrypted-text projection bounded no matter how far the user pages —
// evicted messages reload from the canonical timeline on demand. Sized as the
// auto-read start cap (50) plus one edge fill (10), so the first extension of
// a maximal backlog never evicts.
internal const val TTS_HISTORY_WINDOW_MAX_MESSAGES = 60

/** Pure merge of a loaded history page into the bounded read-aloud window. */
internal object TtsHistoryWindow {
    /**
     * Joins [incoming] onto the [direction] side of [existing], dropping
     * duplicates by message id, then evicts overflow from the far edge. The
     * far edge is the only safe side to trim: the near edge holds the
     * navigation target, which must survive every merge.
     */
    fun merge(
        existing: List<TtsQueuedMessage>,
        incoming: List<TtsQueuedMessage>,
        direction: TtsHistoryDirection,
        targetMessageIdHex: String,
        maxMessages: Int = TTS_HISTORY_WINDOW_MAX_MESSAGES,
    ): List<TtsQueuedMessage> {
        val seen = existing.mapNotNullTo(hashSetOf()) { it.messageIdHex.takeIf(String::isNotEmpty) }
        val deduped = incoming.filter { it.messageIdHex.isNotEmpty() && seen.add(it.messageIdHex) }
        val combined =
            when (direction) {
                TtsHistoryDirection.Older -> deduped + existing
                TtsHistoryDirection.Newer -> existing + deduped
            }
        val targetIndex = combined.indexOfFirst { it.messageIdHex == targetMessageIdHex }
        return when {
            combined.size <= maxMessages || targetIndex < 0 -> combined
            direction == TtsHistoryDirection.Older -> combined.take(maxOf(maxMessages, targetIndex + 1))
            else -> combined.takeLast(maxOf(maxMessages, combined.size - targetIndex))
        }
    }
}
