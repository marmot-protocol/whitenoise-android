package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local presentation choices, independent of MDK mute and Android channel settings. */
internal class ConversationAlertPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("whitenoise.conversation_alerts", Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Map<String, Boolean>> = _state.asStateFlow()

    fun choice(
        accountRef: String,
        groupIdHex: String,
        channel: NotificationChannelSpec,
    ): Boolean? = key(accountRef, groupIdHex, channel)?.let { _state.value[it] }

    fun setEnabled(
        accountRef: String,
        groupIdHex: String,
        channel: NotificationChannelSpec,
        enabled: Boolean,
    ): Boolean =
        synchronized(mutationLock) {
            val key = key(accountRef, groupIdHex, channel) ?: return@synchronized false
            val updated = _state.value + (key to enabled)
            val committed = preferences.edit().putBoolean(key, enabled).commit()
            if (committed) _state.value = updated
            committed
        }

    private fun read(): Map<String, Boolean> =
        preferences.all.mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }.toMap()

    private fun key(
        accountRef: String,
        groupIdHex: String,
        channel: NotificationChannelSpec,
    ): String? =
        if (channel in supportedChannels) {
            conversationShortcutId(accountRef, groupIdHex)?.let { "$it:${channel.id}" }
        } else {
            null
        }

    companion object {
        val supportedChannels =
            setOf(
                NotificationChannelSpec.DIRECT_MESSAGES,
                NotificationChannelSpec.GROUP_MESSAGES,
                NotificationChannelSpec.MENTIONS,
                NotificationChannelSpec.REACTIONS,
            )
    }
}
