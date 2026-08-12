package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Pure shell navigation state for deciding whether an in-flight group-create
 * completion may open its conversation. Each [ShellNavigationEvent.CreateSubmitted]
 * mints an immutable [pendingCreateRequestToken]; explicit navigation clears it.
 */
internal data class ShellNavigationState(
    val navigationGeneration: Long = 0L,
    val pendingCreateRequestToken: Long? = null,
    /** Generation captured when [ShellNavigationEvent.NotificationRequestReceived] arms routing. */
    val notificationArmedGeneration: Long? = null,
)

/**
 * Persists only the lightweight shell ownership generations. A restored
 * NewGroupSetupScreen retains its create request token, so the matching shell
 * token must survive the same Activity/process recreation for its eventual
 * authoritative-read completion to remain eligible to navigate.
 */
internal val ShellNavigationStateSaver: Saver<ShellNavigationState, Any> =
    listSaver<ShellNavigationState, Long>(
        save = { state ->
            listOf(
                state.navigationGeneration,
                state.pendingCreateRequestToken ?: NO_SHELL_NAVIGATION_TOKEN,
                state.notificationArmedGeneration ?: NO_SHELL_NAVIGATION_TOKEN,
            )
        },
        restore = { saved ->
            ShellNavigationState(
                navigationGeneration = saved[0],
                pendingCreateRequestToken = saved[1].takeUnless { it == NO_SHELL_NAVIGATION_TOKEN },
                notificationArmedGeneration = saved[2].takeUnless { it == NO_SHELL_NAVIGATION_TOKEN },
            )
        },
    )

private const val NO_SHELL_NAVIGATION_TOKEN = 0L

internal data class ShellNavigationTransition(
    val state: ShellNavigationState,
    val createOpenAccepted: Boolean = false,
    val createRequestTokenMinted: Long? = null,
)

internal sealed interface ShellNavigationEvent {
    /** Group create was submitted and may open its conversation on completion. */
    data object CreateSubmitted : ShellNavigationEvent

    /** User opened a conversation explicitly (chat list, profile, share, …). */
    data class ExplicitConversationOpened(
        val chatId: String,
    ) : ShellNavigationEvent

    /** Delayed notification routing opened a conversation after arming. */
    data class NotificationRoutedConversationOpened(
        val chatId: String,
    ) : ShellNavigationEvent

    /** Async group create finished and wants to open the new conversation. */
    data class CreateCompleted(
        val chatId: String,
        val requestToken: Long,
    ) : ShellNavigationEvent

    /** Create flow was dismissed or superseded without opening the new group. */
    data object CreateFlowSuperseded : ShellNavigationEvent

    /** A profile sheet or deep link became the foreground destination. */
    data object ProfileForegroundOpened : ShellNavigationEvent

    /** A notification tap was received and routing has begun. */
    data object NotificationRequestReceived : ShellNavigationEvent

    /** User backed out of the open conversation to the chat list. */
    data object ConversationBackedOut : ShellNavigationEvent

    /** Active account changed; shell conversation selection resets. */
    data object AccountSwitched : ShellNavigationEvent
}

internal fun reduceShellNavigation(
    state: ShellNavigationState,
    event: ShellNavigationEvent,
): ShellNavigationTransition =
    when (event) {
        ShellNavigationEvent.CreateSubmitted -> reduceCreateSubmitted(state)
        is ShellNavigationEvent.ExplicitConversationOpened -> revokePendingCreate(state)
        is ShellNavigationEvent.NotificationRoutedConversationOpened ->
            reduceNotificationRoutedConversationOpened(state)
        is ShellNavigationEvent.CreateCompleted -> reduceCreateCompleted(state, event)
        ShellNavigationEvent.CreateFlowSuperseded,
        ShellNavigationEvent.ProfileForegroundOpened,
        ShellNavigationEvent.ConversationBackedOut,
        ShellNavigationEvent.AccountSwitched,
        -> revokePendingCreate(state)
        ShellNavigationEvent.NotificationRequestReceived -> armNotificationRequest(state)
    }

private fun reduceCreateSubmitted(state: ShellNavigationState): ShellNavigationTransition {
    val token = state.navigationGeneration + 1
    return ShellNavigationTransition(
        state =
            state.copy(
                navigationGeneration = token,
                pendingCreateRequestToken = token,
            ),
        createRequestTokenMinted = token,
    )
}

private fun revokePendingCreate(state: ShellNavigationState): ShellNavigationTransition =
    ShellNavigationTransition(
        state =
            state.copy(
                navigationGeneration = state.navigationGeneration + 1,
                pendingCreateRequestToken = null,
                notificationArmedGeneration = null,
            ),
    )

private fun armNotificationRequest(state: ShellNavigationState): ShellNavigationTransition {
    val armedGeneration = state.navigationGeneration + 1
    return ShellNavigationTransition(
        state =
            state.copy(
                navigationGeneration = armedGeneration,
                pendingCreateRequestToken = null,
                notificationArmedGeneration = armedGeneration,
            ),
    )
}

/**
 * Delayed notification commit revokes stale create ownership only when no newer
 * [ShellNavigationEvent.CreateSubmitted] advanced [ShellNavigationState.navigationGeneration]
 * after the request was armed (issue #1953).
 */
private fun reduceNotificationRoutedConversationOpened(state: ShellNavigationState): ShellNavigationTransition {
    val armedGeneration = state.notificationArmedGeneration
    return if (armedGeneration != null && state.navigationGeneration == armedGeneration) {
        revokePendingCreate(state)
    } else {
        ShellNavigationTransition(
            state = state.copy(notificationArmedGeneration = null),
        )
    }
}

private fun reduceCreateCompleted(
    state: ShellNavigationState,
    event: ShellNavigationEvent.CreateCompleted,
): ShellNavigationTransition {
    val accepted = state.pendingCreateRequestToken == event.requestToken
    return if (accepted) {
        ShellNavigationTransition(
            state =
                state.copy(
                    navigationGeneration = state.navigationGeneration + 1,
                    pendingCreateRequestToken = null,
                ),
            createOpenAccepted = true,
        )
    } else {
        ShellNavigationTransition(state = state)
    }
}

/** Revoke create ownership when a notification request is first observed. */
internal fun armShellNotificationRequest(
    shellNavState: ShellNavigationState,
    profileForeground: ProfileGroupForegroundState,
): ShellNavigationState {
    profileForeground.close()
    return reduceShellNavigation(shellNavState, ShellNavigationEvent.NotificationRequestReceived).state
}

/** Revoke create ownership when a profile becomes the foreground destination. */
internal fun armShellProfileForeground(
    shellNavState: ShellNavigationState,
    profileForeground: ProfileGroupForegroundState,
): ShellNavigationState {
    profileForeground.close()
    return reduceShellNavigation(shellNavState, ShellNavigationEvent.ProfileForegroundOpened).state
}
