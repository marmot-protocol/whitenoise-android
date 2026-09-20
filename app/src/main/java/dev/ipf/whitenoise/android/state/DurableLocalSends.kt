package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi

/** Runs synchronous status reads and local text admission off the UI thread for this bound conversation. */
internal suspend fun ConversationController.publishDurableComposerText(
    account: String,
    replyTarget: String?,
    text: String,
    token: String,
): SendSummaryFfi =
    appState.marmotIo(MarmotTraceSection.TEXT_SEND) {
        sendComposerTextWithToken(account, group.groupIdHex, replyTarget, text, token)
    }

/** Adapts local ownership to the existing pending UI without claiming relay delivery. */
internal fun pendingLocalSend(messageIds: List<String> = emptyList()): SendSummaryFfi =
    SendSummaryFfi(0u, messageIds, SendAcceptDispositionFfi.ACCEPTED_PENDING, SendMaintenanceDispositionFfi.READY)

/** Queries native ownership before a retry; no Android send ledger is introduced. */
internal fun MarmotInterface.recoveredLocalSend(
    account: String,
    group: String,
    token: String,
): SendSummaryFfi? =
    when (val status = localSendStatus(account, group, token)) {
        is LocalSendStatusFfi.Completed -> status.summary
        LocalSendStatusFfi.Queued, LocalSendStatusFfi.EngineOwned -> pendingLocalSend()
        LocalSendStatusFfi.Rejected -> error("local submission rejected; a new deliberate submission is required")
        null -> null
    }

/** Keeps the same logical token across interrupted admissions and connect-phase retries. */
internal suspend fun MarmotInterface.sendComposerTextWithToken(
    account: String,
    group: String,
    replyTarget: String?,
    text: String,
    token: String,
): SendSummaryFfi =
    admitLocalSend(account, group, token) {
        if (replyTarget == null) {
            sendTextWithClientToken(account, group, text, token)
        } else {
            replyToMessageWithClientToken(account, group, replyTarget, text, token)
        }
    }

/** Recovers an ambiguous admission without creating another semantic send or clearing a newer draft. */
internal suspend fun MarmotInterface.admitLocalSend(
    account: String,
    group: String,
    token: String,
    admit: suspend () -> LocalSendAcceptanceFfi,
): SendSummaryFfi {
    recoveredLocalSend(account, group, token)?.let { return it }
    return try {
        val acceptance = admit()
        check(acceptance.clientToken == token) { "local acceptance changed the caller token" }
        pendingLocalSend(listOf(acceptance.messageIdHex))
    } catch (failure: MarmotKitException) {
        recoveredLocalSend(account, group, token) ?: throw failure
    }
}

/** Upload-only preparation does not claim acceptance; publication still uses the captured draft revision. */
internal suspend fun MarmotInterface.uploadComposerMediaWithToken(
    account: String,
    group: String,
    request: MediaUploadRequestFfi,
    token: String,
): MediaUploadResultFfi {
    require(!request.send) { "composer preparation must not also publish" }
    // An already admitted submission must never re-upload non-idempotent blobs.
    check(localSendStatus(account, group, token) == null) { "local submission already owns this upload" }
    return uploadMediaWithClientToken(account, group, request, token).upload
}
