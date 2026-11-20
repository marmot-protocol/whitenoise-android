package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.ConversationTranscriptTimelineReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exports the current conversation to a local transcript file. */
internal suspend fun ConversationController.exportConversationTranscriptFile(cacheDir: java.io.File): java.io.File? {
    val account = conversationAccountRef ?: return null
    // One timestamp for the whole export so the JSON `exported_at` and the
    // file name stamp match instead of drifting across two now() reads.
    val exportedAt = java.time.Instant.now()
    return runCatchingCancellable {
        val messages =
            withContext(Dispatchers.Default) {
                ConversationTranscriptExport.fetchAllMessages(
                    timelineReader =
                        object : ConversationTranscriptTimelineReader {
                            override suspend fun timelineMessages(
                                accountRef: String,
                                query: TimelineMessageQueryFfi,
                            ): TimelinePageFfi =
                                appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
                                    timelineMessages(accountRef, query)
                                }
                        },
                    accountRef = account,
                    groupIdHex = group.groupIdHex,
                )
            }
        val data =
            withContext(Dispatchers.Default) {
                val document = ConversationTranscriptExport.makeDocument(group, messages, exportedAt)
                ConversationTranscriptExport.encodeJson(document)
            }
        withContext(Dispatchers.IO) {
            ConversationTranscriptExport.writeTemporaryFile(
                cacheDir = cacheDir,
                data = data,
                groupIdHex = group.groupIdHex,
                exportedAt = exportedAt,
            )
        }
    }.onFailure {
        appState.presentFailure(R.string.toast_couldnt_export_transcript, "CONVERSATION_TRANSCRIPT_EXPORT", it)
    }.getOrNull()
}
