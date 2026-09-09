package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal class TtsSentenceSeekLoader(
    private val resolvePager: () -> TtsHistoryPager?,
    private val isCurrent: () -> Boolean,
) {
    suspend fun load(
        messageIdHex: String,
        timelineAt: ULong,
    ): TtsSpeakableEntry? =
        try {
            withTimeoutOrNull(SEEK_TIMEOUT_MS) {
                resolvePager()?.let { find(it, messageIdHex, timelineAt) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun find(
        pager: TtsHistoryPager,
        messageIdHex: String,
        timelineAt: ULong,
    ): TtsSpeakableEntry? {
        var record = pager.timelineRecords().firstOrNull { it.messageIdHex == messageIdHex }
        var pages = 0
        while (record == null && pages < MAX_SEEK_PAGES && isCurrent()) {
            pages++
            if (!advance(pager, timelineAt)) break
            record = pager.timelineRecords().firstOrNull { it.messageIdHex == messageIdHex }
        }
        return if (record != null && isCurrent()) pager.projectSpeakable(record) else null
    }

    private suspend fun advance(
        pager: TtsHistoryPager,
        timelineAt: ULong,
    ): Boolean {
        val records = pager.timelineRecords()
        val oldest = records.firstOrNull()?.recordedAt
        val newest = records.lastOrNull()?.recordedAt
        return when {
            oldest != null && timelineAt < oldest && pager.hasMoreBefore -> pager.loadOlder()
            newest != null && timelineAt > newest && pager.hasMoreAfter -> pager.loadNewer()
            else -> false
        }
    }

    private companion object {
        const val SEEK_TIMEOUT_MS = 5_000L
        const val MAX_SEEK_PAGES = 6
    }
}
