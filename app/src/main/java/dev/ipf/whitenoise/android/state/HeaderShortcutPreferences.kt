package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.WeakHashMap

/** Optional conversation header shortcuts; each one reuses the chat details action of the same name. */
internal enum class HeaderShortcut { Search, MuteUnmute, ReadAloud }

/** Device-wide header shortcut choice: ordered, at most [MAX] entries, empty by default. */
internal class HeaderShortcutPreferences(
    private val preferences: SharedPreferences,
) {
    var shortcuts by mutableStateOf(read())
        private set

    /** Adds or removes one shortcut; adding past [MAX] is ignored rather than replacing an existing one. */
    fun toggle(shortcut: HeaderShortcut) {
        val next = if (shortcut in shortcuts) shortcuts - shortcut else shortcuts + shortcut
        if (next.size > MAX) return
        write(next)
    }

    /** Saves [next] in order and republishes it through the same filter as a cold read. */
    private fun write(next: List<HeaderShortcut>) {
        preferences.edit().putString(KEY, next.joinToString(",") { it.name }).apply()
        shortcuts = read()
    }

    /** Unknown, duplicate or surplus saved names are dropped so a corrupt value cannot crowd the header. */
    private fun read(): List<HeaderShortcut> {
        val stored = runCatching { preferences.getString(KEY, null) }.getOrNull().orEmpty()
        return stored
            .split(',')
            .mapNotNull { name -> HeaderShortcut.entries.firstOrNull { it.name == name } }
            .distinct()
            .take(MAX)
    }

    companion object {
        const val MAX = 2
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
