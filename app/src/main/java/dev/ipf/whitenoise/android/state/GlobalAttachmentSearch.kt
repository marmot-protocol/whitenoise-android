package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.core.globalAttachmentMatchesSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

/** Chats scanned in parallel, matching the message-body search's bound on concurrent bridge calls. */
private const val LIBRARY_FANOUT = 4

/** Timeline rows read per page. Attachments are sparse, so pages are larger than a needle search's. */
private const val LIBRARY_PAGE_LIMIT = 60u

/** Pages walked back per chat before the library stops digging into that history. */
private const val LIBRARY_MAX_PAGES = 4

/** Whole-library ceiling, so a large account cannot build an unbounded list on the main state. */
private const val LIBRARY_ITEM_LIMIT = 400

/**
 * One chat of the library: the rows to scan and the title its cards should show.
 */
internal data class GlobalAttachmentSource(
    val groupIdHex: String,
    val title: String,
)

/**
 * Collects the attachments the chat-list search library should list, newest first.
 *
 * The library pages the plain timeline rather than the engine's needle query: a photo
 * whose file name matches the query carries no body text, so a text-narrowed query would
 * drop it. Needle and filter matching therefore happen client-side over each page, which
 * also keeps the library's rules identical to the message list's.
 */
internal suspend fun collectGlobalAttachments(
    appState: WhiteNoiseAppState,
    accountRef: String,
    sources: List<GlobalAttachmentSource>,
    rawQuery: String,
    constraints: MessageSearchConstraints?,
    kinds: Set<GlobalSearchContentKind>,
): List<GlobalAttachmentItem> {
    if (kinds.isEmpty() || sources.isEmpty()) return emptyList()
    val needle = rawQuery.trim().lowercase(Locale.ROOT)
    return withContext(Dispatchers.IO) {
        val semaphore = Semaphore(LIBRARY_FANOUT)
        coroutineScope {
            sources
                .map { source ->
                    async {
                        semaphore.withPermit {
                            collectOneChat(appState, accountRef, source, needle, constraints, kinds)
                        }
                    }
                }.awaitAll()
        }.flatten()
            .sortedWith(compareByDescending { it.timelineAt })
            .take(LIBRARY_ITEM_LIMIT)
    }
}

/** Walks one chat's timeline backwards, gathering every attachment the current mode and filters admit. */
private suspend fun collectOneChat(
    appState: WhiteNoiseAppState,
    accountRef: String,
    source: GlobalAttachmentSource,
    needle: String,
    constraints: MessageSearchConstraints?,
    kinds: Set<GlobalSearchContentKind>,
): List<GlobalAttachmentItem> {
    val collected = mutableListOf<GlobalAttachmentItem>()
    var cursorBefore: ULong? = null
    var cursorMessageId: String? = null
    var pagesScanned = 0
    var exhausted = false
    while (!exhausted && pagesScanned < LIBRARY_MAX_PAGES) {
        val page =
            runCatchingPage {
                appState.marmotIo(MarmotTraceSection.MESSAGE_SEARCH) {
                    timelineMessages(
                        accountRef,
                        TimelineMessageQueryFfi(
                            groupIdHex = source.groupIdHex,
                            search = null,
                            before = cursorBefore,
                            beforeMessageId = cursorMessageId,
                            after = null,
                            afterMessageId = null,
                            limit = LIBRARY_PAGE_LIMIT,
                        ),
                    )
                }
            }
        if (page == null) {
            exhausted = true
        } else {
            pagesScanned++
            page.messages.forEach { record ->
                collected += libraryItems(record, source, needle, constraints, kinds)
            }
            exhausted = !page.hasMoreBefore || page.messages.isEmpty()
            val oldest = page.messages.minWithOrNull(compareBy({ it.timelineAt }, { it.messageIdHex }))
            cursorBefore = oldest?.timelineAt
            cursorMessageId = oldest?.messageIdHex
        }
    }
    return collected
}

/** The eligible attachments of one timeline row, or nothing when the row itself does not qualify. */
private fun libraryItems(
    record: TimelineMessageRecordFfi,
    source: GlobalAttachmentSource,
    needle: String,
    constraints: MessageSearchConstraints?,
    kinds: Set<GlobalSearchContentKind>,
): List<GlobalAttachmentItem> {
    val eligible =
        !record.deleted &&
            record.media.isNotEmpty() &&
            matchesNeedle(record, needle) &&
            (constraints == null || constraints.matches(searchableTimelineRecord(record)))
    if (!eligible) return emptyList()
    return record.media.mapIndexedNotNull { index, reference ->
        if (!globalAttachmentMatchesSelection(reference.mediaType, kinds)) {
            null
        } else {
            GlobalAttachmentItem(
                groupIdHex = source.groupIdHex,
                chatTitle = source.title,
                messageIdHex = record.messageIdHex,
                attachmentIndex = index,
                mediaType = reference.mediaType,
                label = reference.fileName.ifBlank { reference.mediaType },
                timelineAt = record.timelineAt,
            )
        }
    }
}

/** An empty query admits every attachment; otherwise the body or a file name has to contain it. */
private fun matchesNeedle(
    record: TimelineMessageRecordFfi,
    needle: String,
): Boolean =
    needle.isEmpty() ||
        record.plaintext.lowercase(Locale.ROOT).contains(needle) ||
        record.media.any { it.fileName.lowercase(Locale.ROOT).contains(needle) }

/** Runs one page read, letting cancellation through and swallowing a single chat's failure. */
private inline fun <T> runCatchingPage(block: () -> T): T? =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception,
    ) {
        // One chat failing must not blank the whole library; drop just this chat. The
        // failure is per chat and recoverable, so it deliberately does not propagate.
        null
    }
