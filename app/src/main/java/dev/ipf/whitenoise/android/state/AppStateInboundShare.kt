package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.share.CappedShareStreamStaging
import dev.ipf.whitenoise.android.share.SHARE_STREAM_MAX_ITEMS
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.retainShareAtDestination
import dev.ipf.whitenoise.android.ui.conversation.presentAttachmentAdmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage an inbound Android share for the explicitly chosen local account.
 *
 * The system-share picker can enumerate a non-active account, so resolving
 * through [activeAccountRef] here would silently move the draft and stream
 * ownership to whichever account happened to be active at commit time.
 * Return false when the chosen account is no longer a signed-in signing
 * account; callers keep the picker/recovery path visible in that case.
 */
fun WhiteNoiseAppState.stageInboundShare(
    accountRef: String,
    targetGroupIds: List<String>,
    payload: SharePayload,
): Boolean {
    val target = validatedInboundShareTarget(accountRef, targetGroupIds) ?: return false
    shareInboundStager.stageToChats(
        context = appContext,
        accountIdHex = target.accountIdHex,
        groupIds = target.groupIds,
        payload = payload,
        draftAccountRef = accountRef,
    )
    return true
}

/**
 * Prepares provider MIME metadata on I/O for the automatic Direct Share
 * route, then applies draft/staging state on the calling Main coroutine.
 */
suspend fun WhiteNoiseAppState.stageInboundShareForFirstFrame(
    accountRef: String,
    targetGroupIds: List<String>,
    payload: SharePayload,
    shouldCommit: () -> Boolean = { true },
): Boolean {
    val initialTarget = validatedInboundShareTarget(accountRef, targetGroupIds)
    if (initialTarget == null || !shouldCommit()) return false
    val prepared = withContext(Dispatchers.IO) { shareInboundStager.prepare(appContext, payload) }
    return retainShareAtDestination(
        appContext,
        initialTarget.accountIdHex,
        initialTarget.groupIds,
        payload,
    ) { droppedCount ->
        if (!shouldCommit() || validatedInboundShareTarget(accountRef, targetGroupIds) != initialTarget) {
            false
        } else {
            shareInboundStager.stagePreparedToChats(
                initialTarget.accountIdHex,
                initialTarget.groupIds,
                if (payload.importReady) prepared.copy(streamStaging = null) else prepared,
                accountRef,
            )
            if (payload.importReady && payload.streamUris.isNotEmpty()) shareStaging.notifyTextStaged()
            val offered = payload.streamUris.size
            presentAttachmentAdmission(appContext, (offered - droppedCount).coerceAtLeast(0), offered)
            true
        }
    }
}

/** Revalidates account ownership and de-duplicates recipient ids at the commit boundary. */
private fun WhiteNoiseAppState.validatedInboundShareTarget(
    accountRef: String,
    targetGroupIds: List<String>,
): ValidatedInboundShareTarget? {
    val account =
        accounts.firstOrNull { summary ->
            summary.label == accountRef && summary.isSignedInSigningAccount()
        } ?: return null
    val validGroupIds = targetGroupIds.filter(String::isNotBlank).distinct()
    return validGroupIds
        .takeIf { it.isNotEmpty() }
        ?.let { ValidatedInboundShareTarget(account.accountIdHex, it) }
}

/** Account-bound destination resolved immediately before local staging. */
private data class ValidatedInboundShareTarget(
    val accountIdHex: String,
    val groupIds: List<String>,
)

fun WhiteNoiseAppState.consumeInboundShareStreamsCapped(
    groupIdHex: String,
    existingMediaCount: Int,
    existingDocumentCount: Int,
    maxItems: Int = SHARE_STREAM_MAX_ITEMS,
): CappedShareStreamStaging? {
    val accountIdHex = activeAccount?.accountIdHex ?: return null
    return shareStaging.consumeCapped(
        accountIdHex = accountIdHex,
        groupIdHex = groupIdHex,
        existingMediaCount = existingMediaCount,
        existingDocumentCount = existingDocumentCount,
        maxItems = maxItems,
    )
}
