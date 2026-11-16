package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/**
 * Walk legacy chat rows whose MDK projection did not include a Markdown
 * document and start the `parseMarkdown` fallback off-main. Current MDK
 * rows bypass this path and publish their projected document on the first
 * frame. A legacy row stays responsive with plaintext until its fallback
 * parse completes; failures cache an empty document to avoid retry storms.
 * The cache is pruned to texts still on screen so live-update churn cannot
 * grow it without bound.
 */
internal fun ChatsController.schedulePendingPreviewParses() {
    if (accountRef == null) return
    val epoch = bindEpoch
    val liveTexts = chatRows.mapNotNullTo(mutableSetOf(), ::chatRowPreviewMarkdownFallbackSource)
    if (previewTokensByText.keys.any { it !in liveTexts }) {
        previewTokensByText = previewTokensByText.filterKeys { it in liveTexts }
    }
    val pending =
        liveTexts
            .filterNot { it in previewTokensByText }
            .filterNot { it in inFlightPreviewParses }
    if (pending.isEmpty()) return
    inFlightPreviewParses.addAll(pending)
    pending.forEach { text ->
        recomputeScope.launch {
            try {
                val tokens =
                    previewParseGate.withPermit {
                        if (!isActiveBindEpoch(epoch)) return@withPermit null
                        appState.parseMarkdownOrEmpty(text)
                    } ?: return@launch
                if (!isActiveBindEpoch(epoch)) return@launch
                previewTokensByText = previewTokensByText + (text to tokens)
                // Coalesce: a burst of preview-parse completions on account
                // open/switch would otherwise drive N un-debounced full
                // recomputes. Defer into one.
                scheduleRecompute()
            } finally {
                // Same epoch discipline as the member fetches: a later
                // bind() already cleared the set, so only the owning
                // epoch may mutate it.
                if (isActiveBindEpoch(epoch)) inFlightPreviewParses.remove(text)
            }
        }
    }
}

/**
 * Walk the current chat rows and, for any blank kind-9 preview without a
 * cached media fallback or an in-flight resolve, read the latest local
 * timeline record off-main so [projectedPreviewText] can preserve media
 * filename and typed/generic label fallbacks. The chat-list projection
 * stores plaintext only, so imeta tags must be read from the message store
 * (source of truth) on demand.
 */
internal fun ChatsController.schedulePendingMediaKindResolves() {
    if (accountRef == null) return
    val epoch = bindEpoch
    val account = accountRef!!
    val liveMessageIds = chatRows.mapNotNull(::chatRowNeedsMediaKindResolve).toSet()
    if (mediaPreviewFallbackByMessageId.keys.any { it !in liveMessageIds }) {
        mediaPreviewFallbackByMessageId = mediaPreviewFallbackByMessageId.filterKeys { it in liveMessageIds }
    }
    val pendingRows =
        chatRows.filter { row ->
            val messageId = chatRowNeedsMediaKindResolve(row) ?: return@filter false
            messageId !in mediaPreviewFallbackByMessageId && messageId !in inFlightMediaKindResolves
        }
    if (pendingRows.isEmpty()) return
    pendingRows.forEach { row ->
        val messageId = chatRowNeedsMediaKindResolve(row) ?: return@forEach
        inFlightMediaKindResolves.add(messageId)
        val groupIdHex = row.groupIdHex
        recomputeScope.launch {
            try {
                val page =
                    mediaKindResolveGate.withPermit {
                        if (!isActiveBindEpoch(epoch)) return@withPermit null
                        try {
                            appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
                                timelineMessages(
                                    account,
                                    TimelineMessageQueryFfi.page(
                                        groupIdHex = groupIdHex,
                                        search = null,
                                        before = null,
                                        beforeMessageId = null,
                                        after = null,
                                        afterMessageId = null,
                                        limit = 1u,
                                    ),
                                )
                            }
                        } catch (throwable: Throwable) {
                            rethrowIfCancellation(throwable)
                            null
                        }
                    }
                if (!isActiveBindEpoch(epoch)) return@launch
                val fallback =
                    page
                        ?.messages
                        ?.firstOrNull()
                        ?.takeIf { it.messageIdHex == messageId }
                        ?.let { MessageProjector.mediaPreviewFallback(TimelineProjector.toAppMessageRecord(it)) }
                        ?: return@launch
                if (!isActiveBindEpoch(epoch)) return@launch
                mediaPreviewFallbackByMessageId = mediaPreviewFallbackByMessageId + (messageId to fallback)
                scheduleRecompute()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort. Leave the fallback absent so the next bind
                // can retry; the row keeps the generic media preview.
            } finally {
                if (isActiveBindEpoch(epoch)) inFlightMediaKindResolves.remove(messageId)
            }
        }
    }
}
