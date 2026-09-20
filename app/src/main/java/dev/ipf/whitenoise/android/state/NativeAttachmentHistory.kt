package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.AttachmentHistoryCursor
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import java.io.IOException

internal const val NATIVE_ATTACHMENT_HISTORY_PAGE_LIMIT = 100u

internal data class NativeAttachmentHistoryMatch(
    val target: NativeAttachmentTarget,
    val reference: MediaAttachmentReferenceFfi,
)

/** Returns an accepted history row only when its display, source, and original index agree. */
@Suppress("ReturnCount") // Each identity guard fails closed before exposing a reference.
internal fun AttachmentEntryFfi.matchingAttachment(
    request: AttachmentTransferRequest,
): NativeAttachmentHistoryMatch? {
    if (!messageIdHex.equals(request.messageIdHex, ignoreCase = true)) return null
    if (
        request.sourceMessageIdHex != null &&
        !sourceMessageIdHex.equals(request.sourceMessageIdHex, ignoreCase = true)
    ) {
        return null
    }
    val accepted = attachment as? MediaAttachmentOutcomeFfi.Accepted ?: return null
    if (accepted.attachmentIndex.toInt() != request.attachmentIndex) return null
    return NativeAttachmentHistoryMatch(
        target = NativeAttachmentTarget(messageIdHex, sourceMessageIdHex, accepted.attachmentIndex.toInt()),
        reference = accepted.reference,
    )
}

/**
 * Searches one stable native history generation. Any mutation or cursor
 * invalidation restarts at the head without mixing generations.
 */
@Suppress("ReturnCount") // The bounded restart loop returns each terminal native outcome directly.
internal suspend fun WhiteNoiseAppState.findNativeAttachment(
    request: AttachmentTransferRequest,
    restartBudget: Int = 1,
): NativeAttachmentHistoryMatch? {
    require(restartBudget >= 0)
    repeat(restartBudget + 1) { attempt ->
        when (val result = findNativeAttachmentGeneration(request)) {
            is NativeAttachmentHistoryRead.Found -> return result.match
            NativeAttachmentHistoryRead.Complete -> return null
            NativeAttachmentHistoryRead.Restart -> if (attempt == restartBudget) return null
        }
    }
    return null
}

private sealed interface NativeAttachmentHistoryRead {
    data class Found(
        val match: NativeAttachmentHistoryMatch,
    ) : NativeAttachmentHistoryRead

    data object Complete : NativeAttachmentHistoryRead

    data object Restart : NativeAttachmentHistoryRead
}

/** Reads pages while retaining the original version handle as the only baseline. */
// Native handles require lexical close scopes around every outcome.
@Suppress("LongMethod", "NestedBlockDepth", "ReturnCount")
private suspend fun WhiteNoiseAppState.findNativeAttachmentGeneration(
    request: AttachmentTransferRequest,
): NativeAttachmentHistoryRead {
    val baseline = marmotIo { attachmentHistoryVersion(request.accountRef, request.groupIdHex) }
    var cursor: AttachmentHistoryCursor? = null
    try {
        while (true) {
            val previousCursor = cursor
            val read =
                try {
                    marmotIo {
                        attachmentHistoryPage(
                            request.accountRef,
                            request.groupIdHex,
                            NATIVE_ATTACHMENT_HISTORY_PAGE_LIMIT,
                            previousCursor,
                        )
                    }
                } finally {
                    previousCursor?.close()
                }
            cursor = null
            when (read) {
                is AttachmentPageReadFfi.Page -> {
                    val page = read.page
                    try {
                        if (page.version.changeSince(baseline) != AttachmentHistoryChangeFfi.UNCHANGED) {
                            page.nextCursor?.close()
                            return NativeAttachmentHistoryRead.Restart
                        }
                        page.entries.firstNotNullOfOrNull { it.matchingAttachment(request) }?.let { match ->
                            page.nextCursor?.close()
                            return if (attachmentHistoryStillUnchanged(request, baseline)) {
                                NativeAttachmentHistoryRead.Found(match)
                            } else {
                                NativeAttachmentHistoryRead.Restart
                            }
                        }
                        if (!page.hasMore) {
                            page.nextCursor?.close()
                            return if (attachmentHistoryStillUnchanged(request, baseline)) {
                                NativeAttachmentHistoryRead.Complete
                            } else {
                                NativeAttachmentHistoryRead.Restart
                            }
                        }
                        cursor =
                            page.nextCursor
                                ?: throw IOException("native attachment history omitted its next cursor")
                    } finally {
                        page.version.close()
                    }
                }
                AttachmentPageReadFfi.RestartRequired,
                AttachmentPageReadFfi.CursorMismatch,
                -> return NativeAttachmentHistoryRead.Restart
                AttachmentPageReadFfi.InvalidLimit -> throw IOException("native attachment history rejected limit 100")
            }
        }
    } finally {
        cursor?.close()
        baseline.close()
    }
}

/** Compares a fresh native version with the retained generation baseline. */
private suspend fun WhiteNoiseAppState.attachmentHistoryStillUnchanged(
    request: AttachmentTransferRequest,
    baseline: dev.ipf.marmotkit.AttachmentHistoryVersion,
): Boolean {
    val current = marmotIo { attachmentHistoryVersion(request.accountRef, request.groupIdHex) }
    return try {
        current.changeSince(baseline) == AttachmentHistoryChangeFfi.UNCHANGED
    } finally {
        current.close()
    }
}
