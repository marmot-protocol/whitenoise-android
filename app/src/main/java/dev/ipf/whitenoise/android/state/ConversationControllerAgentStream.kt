package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.StreamDebugEventFormatter
import dev.ipf.whitenoise.android.state.ConversationController.Companion.MAX_STREAM_DEBUG_ROWS
import dev.ipf.whitenoise.android.state.ConversationController.Companion.STREAM_DEBUG_DIRECTION
import dev.ipf.whitenoise.android.state.ConversationController.Companion.STREAM_DEBUG_ID_PREFIX
import dev.ipf.whitenoise.android.state.ConversationController.Companion.STREAM_DEBUG_KIND
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal suspend fun ConversationController.watchAgentTextStream(
    account: String,
    streamId: String,
) {
    val text = StringBuilder()
    var subscription: AgentStreamSubscription? = null
    try {
        runCatchingCancellable {
            val streamSubscription =
                appState.marmotIo {
                    watchAgentTextStream(
                        accountRef = account,
                        groupIdHex = group.groupIdHex,
                        streamIdHex = streamId,
                        serverCertDer = null,
                        insecureLocal = false,
                    )
                }
            subscription = streamSubscription
            while (true) {
                val update =
                    withContext(Dispatchers.IO) {
                        streamSubscription.next()
                    } ?: break
                if (streamId in removedStreamIds) {
                    break
                }
                // When the developer toggle is on, surface every live
                // agent-stream update as a transient inline debug row. No-op
                // (and allocation-free past the boolean read) when off.
                appendStreamDebugEvent(streamId, update)
                when (update) {
                    is AgentStreamUpdateFfi.Chunk -> {
                        appendCappedAgentStreamPreview(text, update.text)
                        updateStreamPreview(streamId, text.toString(), MessageStatus.Streaming)
                    }
                    is AgentStreamUpdateFfi.Finished -> {
                        text.clear()
                        text.append(update.text)
                        // Parse once on completion only — per-chunk parsing
                        // would be an FFI round-trip per token batch for a
                        // document that's still mutating. Chunks render as
                        // plain text; the finished message gets markdown.
                        updateStreamPreview(
                            streamId,
                            text.toString(),
                            MessageStatus.Sent,
                            tokens = appState.parseMarkdownOrEmpty(update.text),
                        )
                    }
                    is AgentStreamUpdateFfi.Failed -> {
                        updateStreamPreview(streamId, copy.streamFailed(), MessageStatus.Failed)
                    }
                    // Typed Hermes-agent variants (Progress / Record / Status)
                    // are surfaced only through the streaming-debug rows above;
                    // they carry no user-visible preview text, so drop them here
                    // and let the loop keep consuming the next chunk.
                    is AgentStreamUpdateFfi.Progress,
                    is AgentStreamUpdateFfi.Record,
                    is AgentStreamUpdateFfi.Status,
                    -> Unit
                }
            }
        }.onFailure { throwable ->
            updateStreamPreview(
                streamId,
                agentStreamFailureText(throwable, copy),
                MessageStatus.Failed,
            )
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { subscription?.close() }
        }
        activeStreamIds.remove(streamId)
    }
}

/**
 * Surface one live agent-stream update as a transient inline streaming-debug
 * row. Each row is a display-only synthetic [TimelineMessage] tagged
 * [STREAM_DEBUG_DIRECTION] so it never inflates unread counts, marks-read, or
 * reacts; the conversation renders it as a [StreamDebugEventRow] keyed off the
 * [STREAM_DEBUG_ID_PREFIX] id. Gated entirely on
 * [WhiteNoiseAppState.streamingDebugEnabled]: a no-op (past a single boolean
 * read) when the toggle is off, so the timeline is byte-identical to today.
 * Surfaces every live Chunk / Status / Progress / Record / Finished / Failed.
 */
private fun ConversationController.appendStreamDebugEvent(
    streamId: String,
    update: AgentStreamUpdateFfi,
) {
    if (!appState.streamingDebugEnabled) return
    val event = StreamDebugEventFormatter.of(update)
    streamDebugEventSequence += 1uL
    val now = nowSeconds()
    // Zero-pad the sequence so same-second rows keep insertion order under
    // the `id` tiebreak in `compareTimelineMessages`.
    val id = "$STREAM_DEBUG_ID_PREFIX$streamId:$now:${streamDebugEventSequence.toString().padStart(20, '0')}"
    val record =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = STREAM_DEBUG_DIRECTION,
            groupIdHex = group.groupIdHex,
            sender = inferStreamSender(streamId),
            plaintext = event.detail,
            contentTokens = EMPTY_MARKDOWN_DOCUMENT,
            kind = STREAM_DEBUG_KIND,
            tags = listOf(MessageProjector.streamTag(streamId), MessageTagFfi(listOf("dbg", event.eventKind))),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = now,
            receivedAt = now,
            sticker = null,
        )
    val item =
        TimelineMessage(
            id = id,
            record = record,
            status = MessageStatus.Sent,
            timelineOrder = nextOptimisticTimelineOrder(),
        )
    streamDebugTimelineItems[id] = item
    // Bound the retained rows: a long-lived agent-heavy conversation could
    // otherwise accrete debug rows without limit while the toggle stays on.
    var evicted = false
    while (streamDebugTimelineItems.size > MAX_STREAM_DEBUG_ROWS) {
        streamDebugTimelineItems.remove(streamDebugTimelineItems.keys.first())
        evicted = true
    }
    if (evicted) {
        // A row dropped out of the middle of the published list — only a full
        // rebuild can drop it from the timeline.
        publishTimelineFromIndexes()
    } else {
        // The new row sorts to the tail (highest recordedAt + timelineOrder),
        // so append it in place rather than re-sorting the whole timeline on
        // every stream update — the slot trick updateStreamPreview uses.
        timeline = timeline + item.withOptimisticEditStatus(optimisticEdits[item.record.messageIdHex])
    }
}

/** Creates or updates the timeline row that shows an agent stream's latest text and status. */
private fun ConversationController.updateStreamPreview(
    streamId: String,
    plaintext: String,
    status: MessageStatus,
    tokens: MarkdownDocumentFfi? = null,
) {
    if (streamId in removedStreamIds) return
    val id = "stream:$streamId"
    val existingItem = optimisticMessages[id] ?: timelineItemsById[id]
    val existing = existingItem?.record
    val record =
        (
            existing ?: AppMessageRecordFfi(
                messageIdHex = "stream-$streamId",
                direction = "received",
                groupIdHex = group.groupIdHex,
                sender = inferStreamSender(streamId),
                plaintext = "",
                contentTokens = EMPTY_MARKDOWN_DOCUMENT,
                kind = 1200uL,
                tags = listOf(MessageProjector.streamTag(streamId)),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = nowSeconds(),
                receivedAt = nowSeconds(),
                sticker = null,
            )
        ).copy(
            plaintext = plaintext,
            // Tokens must always describe the plaintext beside them.
            // When the caller didn't parse this revision (streaming
            // chunks, failure copy), reset to empty — carrying forward a
            // previous revision's tokens would render stale markdown
            // against the new text. Empty falls back to plain rendering.
            contentTokens = tokens ?: EMPTY_MARKDOWN_DOCUMENT,
        )
    val updated =
        TimelineMessage(
            id,
            record,
            status,
            timelineOrder = existingItem?.timelineOrder ?: nextOptimisticTimelineOrder(),
        )
    optimisticMessages[id] = updated
    // A streaming chunk only mutates this one item's text, and none of its
    // sort keys (recordedAt, timelineOrder, id) change across chunks — so
    // its timeline slot is fixed. Replace the slot in place instead of
    // rebuilding + re-sorting the whole timeline per chunk on the Main
    // thread. Finished/Failed (and the first chunk, which has no slot yet)
    // take the full publish so status-driven reconciliation stays on the
    // canonical path. See #145.
    val slot = if (status == MessageStatus.Streaming) timeline.indexOfFirst { it.id == id } else -1
    if (slot >= 0) {
        timeline = timeline.toMutableList().apply { set(slot, updated) }
    } else {
        publishTimelineFromIndexes()
    }
}

// The authoritative sender for a stream comes from the projected timeline
// record (the kind:1200 event that introduced the streamId). If we're
// synthesizing a preview record because no projection has landed yet,
// walk timelineRecords for a record carrying the same stream tag — that
// covers the common case where the kind:1200 event arrived before the
// first chunk. Falls back to empty for the genuine cold-start window,
// which the projection will overwrite as soon as it lands.
private fun ConversationController.inferStreamSender(streamId: String): String {
    val tagValues = MessageProjector.streamTag(streamId).values
    return timelineRecords.values
        .firstOrNull { record -> record.tags.any { it.values == tagValues } }
        ?.sender
        .orEmpty()
}

/** Current wall-clock seconds from the controller's lifecycle-stable clock. */
internal fun ConversationController.nowSeconds(): ULong = (clockMillis() / 1000L).toULong()
