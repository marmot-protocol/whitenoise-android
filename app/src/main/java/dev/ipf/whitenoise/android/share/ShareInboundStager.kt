package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.ui.conversation.media.coerceResolvedMime

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

    fun stageToChats(
        context: Context,
        accountIdHex: String,
        groupIds: List<String>,
        payload: SharePayload,
        draftAccountRef: String = accountIdHex,
    ) {
        if (accountIdHex.isBlank() || groupIds.isEmpty()) return
        val streamStaging =
            if (payload.streamUris.isEmpty()) {
                null
            } else {
                classifyShareStreams(
                    uris = payload.streamUris,
                    resolveMime = { uri -> resolveMime(context, uri) },
                    intentMimeType = payload.intentMimeType,
                )
            }
        var textStaged = false
        var streamsStaged = false
        groupIds.forEach { groupIdHex ->
            if (groupIdHex.isBlank()) return@forEach
            payload.text?.takeIf { it.isNotBlank() }?.let { text ->
                stageText(draftAccountRef, groupIdHex, text)
                textStaged = true
            }
            streamStaging?.takeUnless { it.isEmpty() }?.let { staging ->
                shareStaging.stage(accountIdHex, groupIdHex, staging)
                streamsStaged = true
            }
        }
        if (textStaged && !streamsStaged) shareStaging.notifyTextStaged()
    }
}

internal fun shareResolveMime(
    context: Context,
    uri: Uri,
): String = coerceResolvedMime { context.contentResolver.getType(uri) }
