package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Physical movement is stable across locale changes; Default alone follows the legacy reply direction. */
internal enum class SwipeBinding { MessageLeft, MessageRight, ChatLeft, ChatRight }

/** Only local interaction choices; commands continue to use their existing native eligibility. */
internal enum class SwipeAction { Default, Off, Reply, Forward, React, ReadUnread, MuteUnmute, PinUnpin }

/** Four app-wide interaction preferences, with no conversation or protocol data. */
internal data class SwipePreferenceState(
    val messageLeft: SwipeAction = SwipeAction.Default,
    val messageRight: SwipeAction = SwipeAction.Default,
    val chatLeft: SwipeAction = SwipeAction.Off,
    val chatRight: SwipeAction = SwipeAction.Off,
) {
    /** Returns the stored choice, before resolving the mirrored default. */
    fun choice(binding: SwipeBinding): SwipeAction =
        when (binding) {
            SwipeBinding.MessageLeft -> messageLeft
            SwipeBinding.MessageRight -> messageRight
            SwipeBinding.ChatLeft -> chatLeft
            SwipeBinding.ChatRight -> chatRight
        }

    /** Only unset/default message bindings mirror; explicit physical choices never swap. */
    fun resolved(
        binding: SwipeBinding,
        rtl: Boolean,
    ): SwipeAction {
        val action = choice(binding)
        if (action != SwipeAction.Default) return action
        val defaultReply = if (rtl) SwipeBinding.MessageLeft else SwipeBinding.MessageRight
        return if (binding == defaultReply) SwipeAction.Reply else SwipeAction.Off
    }
}

/** Observable platform preferences; unknown or wrong-domain saved values fall back independently. */
internal class SwipePreferences(
    private val preferences: SharedPreferences,
) {
    var state by mutableStateOf(read())
        private set

    /** Applies one binding without freezing the other side's locale-sensitive default. */
    fun set(
        binding: SwipeBinding,
        action: SwipeAction,
    ) {
        require(action in choices(binding))
        preferences.edit().putString(key(binding), action.name).apply()
        state = read()
    }

    /** Removes only these four UI settings and restores upgrade-compatible behavior. */
    fun reset() {
        preferences.edit().also { edit -> SwipeBinding.entries.forEach { edit.remove(key(it)) } }.apply()
        state = SwipePreferenceState()
    }

    /** Decodes each key independently so one corrupt or newer choice cannot disable other bindings. */
    private fun read(): SwipePreferenceState {
        /** Invalid or wrong-domain data follows the original default for this binding. */
        fun value(binding: SwipeBinding): SwipeAction {
            val stored = runCatching { preferences.getString(key(binding), null) }.getOrNull()
            return choices(binding).firstOrNull { it.name == stored } ?: choices(binding).first()
        }
        return SwipePreferenceState(
            value(SwipeBinding.MessageLeft),
            value(SwipeBinding.MessageRight),
            value(SwipeBinding.ChatLeft),
            value(SwipeBinding.ChatRight),
        )
    }

    companion object {
        /** Message and conversation choices remain separate; destructive chat commands are excluded. */
        fun choices(binding: SwipeBinding): List<SwipeAction> =
            when (binding) {
                SwipeBinding.MessageLeft, SwipeBinding.MessageRight ->
                    listOf(
                        SwipeAction.Default,
                        SwipeAction.Off,
                        SwipeAction.Reply,
                        SwipeAction.Forward,
                        SwipeAction.React,
                    )
                SwipeBinding.ChatLeft, SwipeBinding.ChatRight ->
                    listOf(SwipeAction.Off, SwipeAction.ReadUnread, SwipeAction.MuteUnmute, SwipeAction.PinUnpin)
            }

        /** Stable names survive app upgrades and keep the four settings independent. */
        private fun key(binding: SwipeBinding): String = "swipe_action_" + binding.name
    }
}
