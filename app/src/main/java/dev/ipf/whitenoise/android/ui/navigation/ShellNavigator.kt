package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import dev.ipf.whitenoise.android.state.ChatListItem

/**
 * The shell's single route owner. It is process-retained through [MainShellProcessState], so like the selected
 * conversation on master it survives Activity recreation and app lock, and process death starts it empty. Only
 * [dispatch] changes the stack. Every other view of the route, the selected chat, its open context and the saved
 * section fields, is read from it.
 */
internal class ShellNavigator {
    private val stackState = mutableStateOf(CHATS_ROOT)
    private var stackListener: ((List<ShellRoute>) -> Unit)? = null

    /** The current route stack, read-only. Never empty, rooted at [ShellRoute.Chats]. */
    val stack: List<ShellRoute>
        get() = stackState.value

    /** The open conversation's chat-list row, or null while no conversation is open. */
    val selectedChat: State<ChatListItem?> = derivedStateOf { conversationRoot(stackState.value)?.item }

    /** The open conversation's open context, or the empty context while no conversation is open. */
    val selectedChatOpenContext: State<ConversationOpenContext> =
        derivedStateOf { conversationRoot(stackState.value)?.open ?: ConversationOpenContext() }

    /** Advances on every applied event, so a caller can tell whether its call path sent one. */
    var revision: Int = 0
        private set

    /** The name of the most recently applied event, for the route audit. */
    var lastEventName: String = "none"
        private set

    /** Applies [event] through the pure reducer, then hands the new stack to the attached persistence. */
    fun dispatch(event: ShellNavEvent) {
        stackState.value = reduceShellRoute(stackState.value, event)
        revision += 1
        lastEventName = event.eventName
        stackListener?.invoke(stackState.value)
    }

    /**
     * Makes [listener] the one persistence that sees every new stack. The newest Activity's section state replaces
     * the previous one, because only the live Activity's saved state can outlive this process.
     */
    fun attachPersistence(listener: (List<ShellRoute>) -> Unit) {
        stackListener = listener
    }

    /**
     * Detaches [listener] when it is still the attached persistence, as when its Activity's holder is cleared. A
     * newer holder's persistence is left in place, so a cleared holder can never take the stack's saving with it.
     */
    fun detachPersistence(listener: (List<ShellRoute>) -> Unit) {
        if (stackListener === listener) stackListener = null
    }
}
