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

/** Runs local text admission off the UI thread, probing existing ownership only for retained tokens. */
internal suspend fun ConversationController.publishDurableComposerText(
    account: String,
    replyTarget: String?,
    text: String,
    token: String,
    probeExistingAdmission: Boolean = true,
): SendSummaryFfi =
    appState.marmotIo(MarmotTraceSection.TEXT_SEND) {
        sendComposerTextWithToken(
            account,
            group.groupIdHex,
            replyTarget,
            text,
            token,
            probeExistingAdmission,
        )
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

/** Captures a status-probe failure without intercepting VM errors or replacing the admission failure. */
private fun MarmotInterface.recoverLocalSendResult(
    account: String,
    group: String,
    token: String,
): Result<SendSummaryFfi?> =
    try {
        Result.success(recoveredLocalSend(account, group, token))
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
        Result.failure(failure)
    }

/** Keeps one logical token across interrupted admissions and optionally probes ownership before retrying. */
internal suspend fun MarmotInterface.sendComposerTextWithToken(
    account: String,
    group: String,
    replyTarget: String?,
    text: String,
    token: String,
    probeExistingAdmission: Boolean = true,
): SendSummaryFfi =
    admitLocalSend(account, group, token, probeExistingAdmission) {
        if (replyTarget == null) {
            sendTextWithClientToken(account, group, text, token)
        } else {
            replyToMessageWithClientToken(account, group, replyTarget, text, token)
        }
    }

/** Optionally recovers retained ownership, then resolves ambiguous admission without duplicating a send. */
internal suspend fun MarmotInterface.admitLocalSend(
    account: String,
    group: String,
    token: String,
    probeExistingAdmission: Boolean = true,
    admit: suspend () -> LocalSendAcceptanceFfi,
): SendSummaryFfi {
    if (probeExistingAdmission) recoveredLocalSend(account, group, token)?.let { return it }
    return try {
        val acceptance = admit()
        check(acceptance.clientToken == token) { "local acceptance changed the caller token" }
        pendingLocalSend(listOf(acceptance.messageIdHex))
    } catch (failure: MarmotKitException) {
        val recovery = recoverLocalSendResult(account, group, token)
        recovery.getOrNull()
            ?: run {
                recovery.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
                throw failure
            }
    }
}

/** Upload outcome plus any token-bound admission completed by the same native call. */
internal data class DurableComposerMediaUpload(
    val upload: MediaUploadResultFfi,
    val acceptance: SendSummaryFfi?,
    val recoveredWithoutUpload: Boolean,
)

/**
 * Uses draft admission when MDK owns matching staged bytes, otherwise asks the
 * token-aware upload call to admit draft-less voice notes and contact cards.
 */
internal suspend fun MarmotInterface.uploadOrAdmitComposerMediaWithToken(
    account: String,
    group: String,
    request: MediaUploadRequestFfi,
    token: String,
): DurableComposerMediaUpload {
    require(!request.send) { "controller request must begin as upload-only" }
    recoveredLocalSend(account, group, token)?.let { recovered ->
        return DurableComposerMediaUpload(
            upload = MediaUploadResultFfi(emptyList(), null),
            // The completed native send owns its projection, but an interrupted
            // upload return did not give Android the references needed to build
            // a truthful sent bridge. Keep the bubble pending until that echo.
            acceptance = pendingLocalSend(recovered.messageIds),
            recoveredWithoutUpload = true,
        )
    }
    val draftBacked =
        selectedDraftOrNull(account, group)
            ?.draft
            ?.let { draftDescribesUpload(it, request.attachments) } == true
    val submission = uploadMediaWithClientToken(account, group, request.copy(send = !draftBacked), token)
    val acceptance =
        submission.acceptance?.also {
            check(it.clientToken == token) { "media acceptance changed the caller token" }
        }
    check(draftBacked || acceptance != null) { "draft-less media upload was not admitted" }
    check(!draftBacked || acceptance == null) { "upload-only draft preparation unexpectedly admitted a message" }
    return DurableComposerMediaUpload(
        upload = submission.upload,
        acceptance = acceptance?.let { pendingLocalSend(listOf(it.messageIdHex)) },
        recoveredWithoutUpload = false,
    )
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
