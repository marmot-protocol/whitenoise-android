package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination

internal data class TtsDestinationNavigationRequest(
    val requestId: Long,
    val accountRef: String,
    val groupIdHex: String,
    val sessionId: Long,
    val accountSwitchRequested: Boolean = false,
)

internal data class TtsDestinationAccountSwitchOwnership(
    val requestId: Long,
    val sourceAccountRef: String?,
    val targetAccountRef: String,
)

internal fun TtsDestinationNavigationRequest?.ownsCompletion(requestId: Long): Boolean = this?.requestId == requestId

internal fun TtsDestinationAccountSwitchOwnership?.ownsAccountChange(
    previousAccountRef: String?,
    currentAccountRef: String?,
    request: TtsDestinationNavigationRequest?,
): Boolean =
    this != null &&
        request.ownsCompletion(requestId) &&
        previousAccountRef == sourceAccountRef &&
        currentAccountRef == targetAccountRef

internal sealed interface TtsDestinationNavigationStep {
    data object Cancelled : TtsDestinationNavigationStep

    data object MissingAccount : TtsDestinationNavigationStep

    data object AwaitAccountSwitch : TtsDestinationNavigationStep

    data class SwitchAccount(
        val accountRef: String,
    ) : TtsDestinationNavigationStep

    data class OpenConversation(
        val groupIdHex: String,
        val messageIdHex: String,
        val sessionId: Long,
        val requestId: Long,
    ) : TtsDestinationNavigationStep

    data class LoadConversationDirectly(
        val accountRef: String,
        val groupIdHex: String,
        val messageIdHex: String,
        val sessionId: Long,
        val requestId: Long,
    ) : TtsDestinationNavigationStep
}

/** Pure fail-closed routing decision for a transport-body tap. */
internal fun resolveTtsDestinationNavigation(
    request: TtsDestinationNavigationRequest,
    currentDestination: TtsConversationDestination?,
    knownAccountRefs: Set<String>,
    activeAccountRef: String?,
    availableGroupIds: Set<String>,
): TtsDestinationNavigationStep {
    val destination =
        currentDestination?.takeIf {
            it.sessionId == request.sessionId &&
                it.accountRef == request.accountRef &&
                it.groupIdHex.equals(request.groupIdHex, ignoreCase = true)
        }
    val groupAvailable = availableGroupIds.any { it.equals(request.groupIdHex, ignoreCase = true) }
    return when {
        destination == null -> TtsDestinationNavigationStep.Cancelled
        request.accountRef !in knownAccountRefs -> TtsDestinationNavigationStep.MissingAccount
        activeAccountRef != request.accountRef && request.accountSwitchRequested ->
            TtsDestinationNavigationStep.AwaitAccountSwitch
        activeAccountRef != request.accountRef ->
            TtsDestinationNavigationStep.SwitchAccount(request.accountRef)
        groupAvailable ->
            TtsDestinationNavigationStep.OpenConversation(
                groupIdHex = request.groupIdHex,
                messageIdHex = destination.passage.messageIdHex,
                sessionId = destination.sessionId,
                requestId = request.requestId,
            )
        else ->
            TtsDestinationNavigationStep.LoadConversationDirectly(
                accountRef = request.accountRef,
                groupIdHex = request.groupIdHex,
                messageIdHex = destination.passage.messageIdHex,
                sessionId = destination.sessionId,
                requestId = request.requestId,
            )
    }
}
