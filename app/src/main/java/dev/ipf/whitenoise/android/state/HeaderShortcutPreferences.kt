package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.WeakHashMap

/** Optional conversation header shortcuts; each one reuses the chat details action of the same name. */
internal enum class HeaderShortcut { Search, MuteUnmute, ReadAloud, Members, AddMembers }

/** Device-wide header shortcut choice: ordered, empty by default. */
internal class HeaderShortcutPreferences(
    private val preferences: SharedPreferences,
) {
    var shortcuts by mutableStateOf(read())
        private set

    /** Adds a shortcut at the end or removes it. */
    fun toggle(shortcut: HeaderShortcut) {
        write(if (shortcut in shortcuts) shortcuts - shortcut else shortcuts + shortcut)
    }

    /** Saves [next] in order and republishes it through the same filter as a cold read. */
    private fun write(next: List<HeaderShortcut>) {
        preferences.edit().putString(KEY, next.joinToString(",") { it.name }).apply()
        shortcuts = read()
    }

    /** Unknown or duplicate saved names are dropped so a corrupt value cannot repeat or invent a button. */
    private fun read(): List<HeaderShortcut> {
        val stored = runCatching { preferences.getString(KEY, null) }.getOrNull().orEmpty()
        return stored
            .split(',')
            .mapNotNull { name -> HeaderShortcut.entries.firstOrNull { it.name == name } }
            .distinct()
    }

    companion object {
        private const val KEY = "conversation_header_shortcuts"
    }
}

private val headerShortcutHolders = WeakHashMap<WhiteNoiseAppState, HeaderShortcutPreferences>()

/** One shared header shortcut choice per app state, so the chat header and its settings list stay in step. */
internal val WhiteNoiseAppState.headerShortcutPreferences: HeaderShortcutPreferences
    get() =
        synchronized(headerShortcutHolders) {
            headerShortcutHolders.getOrPut(this) { HeaderShortcutPreferences(preferences) }
        }
