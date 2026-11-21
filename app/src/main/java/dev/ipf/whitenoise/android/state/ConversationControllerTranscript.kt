package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.ConversationTranscriptTimelineReader
import dev.ipf.whitenoise.android.media.captureSharedPlaintextWrite
import dev.ipf.whitenoise.android.media.legacySharedPlaintextPrefix
import dev.ipf.whitenoise.android.media.sharedPlaintextPrefix
import dev.ipf.whitenoise.android.media.withSharedPlaintextWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Exports a transcript to a temporary file owned by the originating account. */
internal suspend fun ConversationController.exportConversationTranscriptFile(cacheDir: File): File? {
    val account = conversationAccountRef?.takeIf { ownsRetainedForegroundPresentation } ?: return null
    // One timestamp for the whole export so the JSON `exported_at` and the
    // file name stamp match instead of drifting across two now() reads.
    val exportedAt = Instant.now()
    val writePermit = captureSharedPlaintextWrite()
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
        withSharedPlaintextWrite(writePermit) {
            ConversationTranscriptExport.writeTemporaryFile(
                cacheDir = cacheDir,
                prefixes =
                    ConversationTranscriptExport.FilePrefixes(
                        current = sharedPlaintextPrefix(appState.appContext, account),
                        preview = legacySharedPlaintextPrefix(account),
                    ),
                data = data,
                groupIdHex = group.groupIdHex,
                exportedAt = exportedAt,
            )
        }
    }.onFailure {
        appState.presentFailure(R.string.toast_couldnt_export_transcript, "CONVERSATION_TRANSCRIPT_EXPORT", it)
    }.getOrNull()
}
