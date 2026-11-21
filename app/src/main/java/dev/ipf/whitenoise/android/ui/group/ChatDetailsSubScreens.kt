package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** The chat-details screens that live on the shell's route stack, one level above the details list. */
internal enum class ChatDetailsDestination {
    /** The chat's relay list. */
    Relays,

    /** The developer tools screen with the chat's group identifiers. */
    GroupInfo,

    /** The chat's sounds and notifications screen. */
    NotificationSettings,

    /** The chat's bubble colours. */
    BubbleColors,
}

/**
 * Which chat-details screen is open above the details list. Inside the shell it is an entry on the route stack, so
 * it survives Activity recreation and app lock with the conversation and is dropped by process death and every
 * account change. Outside the shell it is local state, as it was before.
 */
internal interface ChatDetailsSubScreens {
    /** The open screen, or null while the details list itself shows. */
    val current: ChatDetailsDestination?

    /** Opens [destination] above the details list, replacing any open one. */
    fun open(destination: ChatDetailsDestination)

    /** Closes the open screen, back to the details list. */
    fun close()
}

/** The shell's route-stack backed screens for the conversation being composed, or null outside the shell. */
internal val LocalChatDetailsSubScreens = staticCompositionLocalOf<ChatDetailsSubScreens?> { null }

/** The shell's screens when it provides them, else composition-local ones that reset with [key]. */
@Composable
internal fun rememberChatDetailsSubScreens(key: Any): ChatDetailsSubScreens =
    LocalChatDetailsSubScreens.current ?: remember(key) { LocalChatDetailsSubScreenState() }

/** Chat-details screens held in composition, for group details composed outside the shell. */
private class LocalChatDetailsSubScreenState : ChatDetailsSubScreens {
    override var current: ChatDetailsDestination? by mutableStateOf(null)
        private set

    /** Shows [destination]. */
    override fun open(destination: ChatDetailsDestination) {
        current = destination
    }

    /** Returns to the details list. */
    override fun close() {
        current = null
    }
}
