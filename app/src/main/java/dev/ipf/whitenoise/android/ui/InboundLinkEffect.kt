package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatLink
import dev.ipf.whitenoise.android.core.ChatLinkParse
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ProductObservation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.recordProductObservation

/** Settles the Activity's current link after startup and reports unsupported links once. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun InboundLinkEffect(
    inboundProfilePayload: String?,
    appState: WhiteNoiseAppState,
    profileEntryTicket: Long?,
    onProfilePayloadHandled: (String) -> Unit,
    onChatLinkResolved: (String?, NotificationTarget?) -> Unit,
    showChatLinkOutcome: (ChatLinkOutcome) -> Unit,
) {
    LaunchedEffect(
        inboundProfilePayload,
        appState.phase,
        appState.appLock.screenVisible,
        appState.appLock.evaluationPending,
    ) {
        val payload = inboundProfilePayload ?: return@LaunchedEffect
        if (appState.phase != AppPhase.Ready) return@LaunchedEffect
        val chatLink = ChatLink.parse(payload)
        if (chatLink != ChatLinkParse.NotChatLink) {
            // A chat link reads which accounts hold the chat and may say so; neither happens under app lock (#3658).
            // A key change cancels this read, so a late result cannot act on a newer link or a locked app.
            if (appState.appLock.screenVisible || appState.appLock.evaluationPending) return@LaunchedEffect
            val outcome = resolveChatLinkOutcome(chatLink, appState) ?: return@LaunchedEffect
            showChatLinkOutcome(outcome)
            onProfilePayloadHandled(payload)
            return@LaunchedEffect onChatLinkResolved(payload, (outcome as? ChatLinkOutcome.Open)?.target)
        }
        if (appState.presentProfilePayload(payload)) {
            appState.recordProductObservation(ProductObservation.PROFILE_ENTRY, profileEntryTicket)
        } else {
            appState.present(R.string.toast_unsupported_link)
        }
        onProfilePayloadHandled(payload)
    }
}
