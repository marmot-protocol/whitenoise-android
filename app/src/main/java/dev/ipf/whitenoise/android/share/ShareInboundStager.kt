package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.ui.conversation.media.coerceResolvedMime

/** MIME-resolved share content that can be applied to one or more local drafts on Main. */
internal data class PreparedInboundShare(
    val text: String?,
    val streamStaging: ShareStreamStaging?,
)

/**
 * Stage an inbound share into one or more chats without invoking any send,
 * publish, or upload path. Text is merged through the MDK draft boundary; streams land in
 * [ShareStagingStore] for the conversation media preview flow.
 */
class ShareInboundStager(
    private val stageText: (accountRef: String, groupIdHex: String, text: String) -> Unit,
    private val shareStaging: ShareStagingStore,
    private val resolveMime: (Context, Uri) -> String,
) {
    internal constructor(
        draftStore: DraftStore,
        shareStaging: ShareStagingStore,
        resolveMime: (Context, Uri) -> String,
    ) : this(
        stageText = draftStore::mergeText,
        shareStaging = shareStaging,
        resolveMime = resolveMime,
    )

    /** Resolves provider MIME metadata; callers choose the appropriate I/O dispatcher. */
    internal fun prepare(
        context: Context,
        payload: SharePayload,
    ): PreparedInboundShare =
        PreparedInboundShare(
            text = payload.text?.takeIf { it.isNotBlank() },
            streamStaging =
                payload.streamUris
                    .takeIf { it.isNotEmpty() }
                    ?.let { uris ->
                        classifyShareStreams(
                            uris = uris,
                            resolveMime = { uri -> resolveMime(context, uri) },
                            intentMimeType = payload.intentMimeType,
                        )
                    },
        )

    /** Applies already-prepared content without content-provider calls. */
    internal fun stagePreparedToChats(
        accountIdHex: String,
        groupIds: List<String>,
        prepared: PreparedInboundShare,
        draftAccountRef: String,
    ) {
        if (accountIdHex.isBlank() || groupIds.isEmpty()) return
        var textStaged = false
        var streamsStaged = false
        groupIds.forEach { groupIdHex ->
            if (groupIdHex.isBlank()) return@forEach
            prepared.text?.let { text ->
                stageText(draftAccountRef, groupIdHex, text)
                textStaged = true
            }
            prepared.streamStaging?.takeUnless { it.isEmpty() }?.let { staging ->
                shareStaging.stage(accountIdHex, groupIdHex, staging)
                streamsStaged = true
            }
        }
        if (textStaged && !streamsStaged) shareStaging.notifyTextStaged()
    }

    /** Convenience path for non-first-frame callers that may prepare synchronously. */
    fun stageToChats(
        context: Context,
        accountIdHex: String,
        groupIds: List<String>,
        payload: SharePayload,
        draftAccountRef: String,
    ) {
        stagePreparedToChats(
            accountIdHex = accountIdHex,
            groupIds = groupIds,
            prepared = prepare(context, payload),
            draftAccountRef = draftAccountRef,
        )
    }
}

internal fun shareResolveMime(
    context: Context,
    uri: Uri,
): String = coerceResolvedMime { context.contentResolver.getType(uri) }
