package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.ui.conversation.ConversationSurfaceState
import dev.ipf.whitenoise.android.ui.group.ChatDetailsDestination
import dev.ipf.whitenoise.android.ui.group.ChatDetailsSubScreens

/**
 * Chat-details screens on the route stack, for the conversation of [accountRef] and [groupIdHex]. While that
 * conversation is the stack's root this reads and changes the stack. Once it is not, as while the conversation is
 * leaving the screen, it keeps answering the screen it last showed, so the outgoing content does not change mid-exit.
 */
internal class ShellChatDetailsSubScreens(
    private val holder: MainShellStateHolder,
    private val accountRef: String,
    private val groupIdHex: String,
) : ChatDetailsSubScreens {
    private var lastShown: ChatDetailsDestination? = null

    /** Whether this conversation is the stack's conversation root. */
    val ownsRoot: Boolean
        get() =
            conversationRoot(holder.shellNavigator.stack)?.isSameChat(accountRef, groupIdHex) ?: false

    /** The open chat-details screen of this conversation, or the one it last showed once it has left the root. */
    override val current: ChatDetailsDestination?
        get() {
            if (!ownsRoot) return lastShown
            val top = holder.shellNavigator.stack.last() as? ShellRoute.ChatDetailsScreen
            return top?.destination.also { lastShown = it }
        }

    /** Puts [destination] on the stack above this conversation. */
    override fun open(destination: ChatDetailsDestination) {
        if (ownsRoot) holder.dispatchShellNav(ShellNavEvent.OpenChatDetailsScreen(destination))
    }

    /** Pops the open screen, as system Back in the conversation's pane does. */
    override fun close() {
        if (ownsRoot && holder.shellNavigator.stack.last() is ShellRoute.ChatDetailsScreen) {
            holder.dispatchShellNav(ShellNavEvent.BackFrom(ShellPane.Content))
        }
    }
}

/**
 * The stack-backed chat-details screens for one composed conversation. When that conversation's details close by
 * any path (Back, a jump to a message, a rebuilt controller resetting its surface state) its screens are dropped
 * from the stack too, as master drops them with the details composition.
 */
@Composable
internal fun rememberShellChatDetailsSubScreens(
    holder: MainShellStateHolder,
    accountRef: String,
    groupIdHex: String,
    surfaceState: ConversationSurfaceState?,
): ShellChatDetailsSubScreens {
    val subScreens =
        remember(holder, accountRef, groupIdHex) {
            ShellChatDetailsSubScreens(holder = holder, accountRef = accountRef, groupIdHex = groupIdHex)
        }
    val detailsOpen = surfaceState?.showDetails?.value ?: true
    val hasScreen = subScreens.ownsRoot && holder.shellNavigator.stack.last() is ShellRoute.ChatDetailsScreen
    LaunchedEffect(subScreens, detailsOpen, hasScreen) {
        if (!detailsOpen && hasScreen && subScreens.ownsRoot) {
            holder.dispatchShellNav(ShellNavEvent.CloseChatDetailsScreens)
        }
    }
    return subScreens
}
