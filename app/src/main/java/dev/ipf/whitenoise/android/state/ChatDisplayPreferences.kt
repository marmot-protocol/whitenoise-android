package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

internal object ChatScreenshotPreferences {
    private const val KEY_ALLOW_CHAT_SCREENSHOTS = "allow_chat_screenshots"

    fun readAllowChatScreenshots(preferences: SharedPreferences): Boolean = preferences.getBoolean(KEY_ALLOW_CHAT_SCREENSHOTS, true)

    fun readAllowChatScreenshots(context: Context): Boolean =
        readAllowChatScreenshots(
            context.applicationContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
        )

    fun writeAllowChatScreenshots(
        preferences: SharedPreferences,
        enabled: Boolean,
    ) {
        editAllowChatScreenshots(preferences.edit(), enabled).apply()
    }

    /** Adds the screenshot preference to a caller-owned durable editor transaction. */
    fun editAllowChatScreenshots(
        editor: SharedPreferences.Editor,
        enabled: Boolean,
    ): SharedPreferences.Editor = editor.putBoolean(KEY_ALLOW_CHAT_SCREENSHOTS, enabled)
}

internal object LongMessageCollapsePreferences {
    private const val KEY_PREFIX = "collapse_long_messages:"

    fun normalizedAccountRef(accountRef: String?): String? =
        accountRef
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun normalizedGroupId(groupIdHex: String): String? =
        groupIdHex
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

    fun preferenceKey(
        accountRef: String?,
        groupIdHex: String,
    ): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        val group = normalizedGroupId(groupIdHex) ?: return null
        return "$KEY_PREFIX$account:$group"
    }

    fun readCollapseLongMessages(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
    ): Boolean {
        val key = preferenceKey(accountRef, groupIdHex) ?: return true
        return readCollapseLongMessagesByKey(preferences, key)
    }

    fun readCollapseLongMessagesByKey(
        preferences: SharedPreferences,
        key: String,
    ): Boolean = preferences.getBoolean(key, true)

    fun writeCollapseLongMessages(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
        enabled: Boolean,
    ) {
        val key = preferenceKey(accountRef, groupIdHex) ?: return
        writeCollapseLongMessagesByKey(preferences, key, enabled)
    }

    fun writeCollapseLongMessagesByKey(
        preferences: SharedPreferences,
        key: String,
        enabled: Boolean,
    ) {
        val edit = preferences.edit()
        if (enabled) {
            edit.remove(key)
        } else {
            edit.putBoolean(key, false)
        }
        edit.apply()
    }
}

/** Observable, account-scoped view of the persisted long-message preference. */
internal class LongMessageCollapseState(
    registry: ScopedCacheRegistry,
    private val preferences: SharedPreferences,
    maxEntries: Int,
) {
    private val values =
        ScopedCache<String, MutableState<Boolean>>(
            registry = registry,
            name = "collapse-long-messages",
            maxEntries = maxEntries,
            observable = true,
        )

    fun collapseLongMessages(
        accountRef: String?,
        groupIdHex: String,
    ): Boolean {
        val key = LongMessageCollapsePreferences.preferenceKey(accountRef, groupIdHex) ?: return true
        return values
            .getOrPut(key) {
                mutableStateOf(LongMessageCollapsePreferences.readCollapseLongMessagesByKey(preferences, key))
            }.value
    }

    fun updateCollapseLongMessages(
        accountRef: String?,
        groupIdHex: String,
        enabled: Boolean,
    ) {
        val key = LongMessageCollapsePreferences.preferenceKey(accountRef, groupIdHex) ?: return
        values.getOrPut(key) { mutableStateOf(enabled) }.value = enabled
        LongMessageCollapsePreferences.writeCollapseLongMessagesByKey(preferences, key, enabled)
    }
}
