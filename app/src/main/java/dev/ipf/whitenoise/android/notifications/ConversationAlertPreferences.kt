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

    /** Returns the saved device-local choice, or null to retain legacy behavior for this category. */
    fun choice(
        accountRef: String,
        groupIdHex: String,
        channel: NotificationChannelSpec,
    ): Boolean? = key(accountRef, groupIdHex, channel)?.let { _state.value[it] }

    /**
     * Saves a supported account/chat/category choice durably; call off the main thread.
     * Returns false for invalid scope or failed persistence, without publishing the rejected choice.
     */
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
            if (committed) {
                _state.value = updated
            } else {
                // commit can change SharedPreferences memory before its disk write fails.
                val rollback = preferences.edit()
                _state.value[key]?.let { rollback.putBoolean(key, it) } ?: rollback.remove(key)
                rollback.apply()
            }
            committed
        }

    private fun read(): Map<String, Boolean> =
        preferences.all
            .mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }
            .toMap()

    /** Prunes choices for absent accounts immediately in memory, with best-effort asynchronous persistence. */
    fun retainAccounts(accountRefs: Collection<String>): Boolean {
        val retained = accountRefs.map { sha256Hex(it) }.toSet()
        return removeChoices(durable = false) { key -> key.substringBefore(':') !in retained }
    }

    /**
     * Durably removes only this account's choices; call off the main thread.
     * Returns false on a failed commit and keeps the accepted state; account erasure must still proceed.
     */
    fun clearAccount(accountRef: String): Boolean {
        val prefix = sha256Hex(accountRef)
        return removeChoices { key -> key.substringBefore(':') == prefix }
    }

    private fun removeChoices(
        durable: Boolean = true,
        remove: (String) -> Boolean,
    ): Boolean =
        synchronized(mutationLock) {
            val updated = _state.value.filterKeys { !remove(it) }
            val editor = preferences.edit()
            (_state.value.keys - updated.keys).forEach(editor::remove)
            if (!durable) {
                editor.apply()
                _state.value = updated
                return@synchronized true
            }
            val committed = editor.commit()
            if (committed) {
                _state.value = updated
            } else {
                val rollback = preferences.edit()
                _state.value.forEach { (key, value) -> rollback.putBoolean(key, value) }
                rollback.apply()
            }
            committed
        }

    private fun key(
        accountRef: String,
        groupIdHex: String,
        channel: NotificationChannelSpec,
    ): String? =
        if (channel in supportedChannels) {
            conversationShortcutId(accountRef, groupIdHex)?.let { "${sha256Hex(accountRef)}:$it:${channel.id}" }
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
