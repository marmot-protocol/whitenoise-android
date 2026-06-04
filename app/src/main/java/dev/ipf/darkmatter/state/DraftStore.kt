package dev.ipf.darkmatter.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Persistence layer for unsent conversation drafts. The storage map is keyed
 * by `"<accountIdHex> <groupIdHex>"`; values are the raw draft text.
 *
 * Split out from [DraftStore] so the in-memory cache can be unit-tested
 * without an Android `SharedPreferences` instance.
 */
internal interface DraftPersistence {
    fun read(): Map<String, String>
    fun write(key: String, value: String?)
}

/**
 * Holds unsent draft text per `(accountIdHex, groupIdHex)`. Reads return the
 * in-memory cache; writes update the cache and the backing persistence layer.
 *
 * Reads expose a Compose state read via [revision] so any composable that
 * called [get] re-composes when any draft changes. Following the same
 * pattern that `AppState.profileRevision` uses for the profile cache.
 */
class DraftStore internal constructor(
    private val persistence: DraftPersistence,
) {
    private val cache = mutableMapOf<String, String>()
    private var revision by mutableStateOf(0)

    init {
        cache.putAll(persistence.read())
    }

    fun get(accountIdHex: String, groupIdHex: String): String? {
        // Read the revision so Compose registers a snapshot dependency.
        revision
        return cache[key(accountIdHex, groupIdHex)]
    }

    /**
     * Sets the draft. Empty or whitespace-only text clears the draft so we
     * don't store noise.
     */
    fun set(accountIdHex: String, groupIdHex: String, text: String) {
        val k = key(accountIdHex, groupIdHex)
        val changed = if (text.isBlank()) {
            val existed = cache.remove(k) != null
            if (existed) persistence.write(k, null)
            existed
        } else {
            val previous = cache.put(k, text)
            val different = previous != text
            if (different) persistence.write(k, text)
            different
        }
        if (changed) revision++
    }

    fun clearAllForAccount(accountIdHex: String) {
        val prefix = "$accountIdHex "
        val keys = cache.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        keys.forEach { k ->
            cache.remove(k)
            persistence.write(k, null)
        }
        revision++
    }

    private fun key(accountIdHex: String, groupIdHex: String): String = "$accountIdHex $groupIdHex"

    companion object {
        fun forContext(context: Context): DraftStore {
            return DraftStore(SharedPreferencesDraftPersistence(context.applicationContext))
        }
    }
}

internal class SharedPreferencesDraftPersistence(context: Context) : DraftPersistence {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("darkmatter.drafts", Context.MODE_PRIVATE)

    override fun read(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefs.all.filterValues { it is String } as Map<String, String>
    }

    override fun write(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
