package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationFailure
import dev.ipf.whitenoise.android.diagnostics.DmCreationOutcome
import dev.ipf.whitenoise.android.diagnostics.DmCreationPhase
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.ChatListItem

/** Traces native lookup boundaries without changing authoritative direct-chat resolution or creation retry. */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal suspend fun attemptOpenOrStartProfileChat(
    npub: String,
    progressHex: String,
    recipientName: String?,
    retryGroupIdHex: String? = null,
    resolveDirectChat: suspend () -> NewMessageDirectChatResolution,
    createGroup: suspend (String) -> String,
    loadCreatedChatListItem: suspend (String) -> ChatListItem,
    displayName: (String) -> String,
    markCreateOpenStage: (String) -> Unit = {},
    abandonCreateOpenTiming: (String) -> Unit = {},
    directChatLookupAlreadyStarted: Boolean = false,
    diagnosticAttempt: DmCreationAttempt? = null,
): StartChatAttemptResult {
    val existingChatResult =
        if (retryGroupIdHex == null) {
            val resolution =
                tracedExistingDirectChatLookup(
                    resolveDirectChat,
                    markCreateOpenStage,
                    abandonCreateOpenTiming,
                    directChatLookupAlreadyStarted,
                    diagnosticAttempt,
                )
            when {
                resolution.item != null ->
                    StartChatAttemptResult.Open(
                        item = resolution.item,
                        newlyCreated = false,
                        diagnosticAttempt = diagnosticAttempt,
                    )
                !resolution.createRequired -> {
                    abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_FAILED)
                    StartChatAttemptResult.Failed(
                        StartChatErrorUiState(
                            npub = npub,
                            progressHex = progressHex,
                            detail = AppText.Resource(R.string.couldnt_load_chats),
                            diagnosticReport = null,
                            recipientName = recipientName,
                        ),
                    )
                }
                else -> null
            }
        } else {
            null
        }
    return existingChatResult ?: attemptStartProfileChat(
        npub = npub,
        progressHex = progressHex,
        recipientName = recipientName,
        retryGroupIdHex = retryGroupIdHex,
        createGroup = createGroup,
        loadCreatedChatListItem = loadCreatedChatListItem,
        displayName = displayName,
        markCreateOpenStage = markCreateOpenStage,
        abandonCreateOpenTiming = abandonCreateOpenTiming,
        diagnosticAttempt = diagnosticAttempt,
    )
}

/** Records only typed lookup boundaries while preserving native failures and existing timing ownership. */
@Suppress("TooGenericExceptionCaught")
private suspend fun tracedExistingDirectChatLookup(
    resolveDirectChat: suspend () -> NewMessageDirectChatResolution,
    markCreateOpenStage: (String) -> Unit,
    abandonCreateOpenTiming: (String) -> Unit,
    directChatLookupAlreadyStarted: Boolean,
    diagnosticAttempt: DmCreationAttempt?,
): NewMessageDirectChatResolution {
    diagnosticAttempt?.record(DmCreationPhase.EXISTING_LOOKUP, DmCreationOutcome.START)
    if (!directChatLookupAlreadyStarted) {
        markCreateOpenStage(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_START)
    }
    val resolution =
        try {
            resolveDirectChat()
        } catch (failure: Exception) {
            diagnosticAttempt?.failed(DmCreationPhase.EXISTING_LOOKUP, failure)
            if (failure is kotlinx.coroutines.CancellationException) {
                abandonCreateOpenTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
            }
            throw failure
        }
    if (!directChatLookupAlreadyStarted) {
        markCreateOpenStage(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN)
    }
    diagnosticAttempt?.record(
        DmCreationPhase.EXISTING_LOOKUP,
        if (resolution.item != null || resolution.createRequired) {
            DmCreationOutcome.SUCCESS
        } else {
            DmCreationOutcome.FAILURE
        },
        if (resolution.item != null || resolution.createRequired) {
            DmCreationFailure.NONE
        } else {
            DmCreationFailure.UNKNOWN
        },
    )
    return resolution
}
