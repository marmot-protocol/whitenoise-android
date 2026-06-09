package dev.ipf.darkmatter.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Persistence layer for unsent conversation drafts. The storage map is keyed
 * by `"<accountIdHex> <groupIdHex>"`; values are the raw draft text.
 *
 * Split out from [DraftStore] so the in-memory cache can be unit-tested
 * without an Android `SharedPreferences` instance.
 */
internal interface DraftPersistence {
    fun read(): Map<String, String>

    fun write(
        key: String,
        value: String?,
    )
}

/**
 * Holds unsent draft text per `(accountIdHex, groupIdHex)`. Reads return the
 * in-memory cache; writes update the cache and the backing persistence layer.
 *
 * Each draft is held in its own Compose [MutableState] keyed by
 * `(accountIdHex, groupIdHex)`, so a composable that called [get] re-composes
 * only when *that* draft changes. (A single shared revision counter — or a
 * SnapshotStateMap, which tracks reads at whole-map granularity — would
 * recompose every chat-list row on every keystroke in any conversation.)
 */
class DraftStore internal constructor(
    private val persistence: DraftPersistence,
) {
    private val drafts = mutableMapOf<String, MutableState<String?>>()

    init {
        persistence.read().forEach { (k, value) -> drafts[k] = mutableStateOf(value) }
    }

    // Per-key state so reads/writes are observed independently. Creating an
    // empty state on a miss is what lets a composable that read a not-yet-set
    // draft recompose once it is set.
    private fun stateFor(k: String): MutableState<String?> = drafts.getOrPut(k) { mutableStateOf(null) }

    fun get(
        accountIdHex: String,
        groupIdHex: String,
    ): String? = stateFor(key(accountIdHex, groupIdHex)).value

    /**
     * Sets the draft. Empty or whitespace-only text clears the draft so we
     * don't store noise.
     */
    fun set(
        accountIdHex: String,
        groupIdHex: String,
        text: String,
    ) {
        val k = key(accountIdHex, groupIdHex)
        val state = stateFor(k)
        if (text.isBlank()) {
            if (state.value != null) {
                state.value = null
                persistence.write(k, null)
            }
        } else if (state.value != text) {
            state.value = text
            persistence.write(k, text)
        }
    }

    fun clearAllForAccount(accountIdHex: String) {
        val prefix = "$accountIdHex "
        drafts.forEach { (k, state) ->
            if (k.startsWith(prefix) && state.value != null) {
                state.value = null
                persistence.write(k, null)
            }
        }
    }

    private fun key(
        accountIdHex: String,
        groupIdHex: String,
    ): String = "$accountIdHex $groupIdHex"

    companion object {
        fun forContext(context: Context): DraftStore = DraftStore(SharedPreferencesDraftPersistence(context.applicationContext))
    }
}

internal class SharedPreferencesDraftPersistence(
    context: Context,
) : DraftPersistence {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("darkmatter.drafts", Context.MODE_PRIVATE)

    override fun read(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefs.all.filterValues { it is String } as Map<String, String>
    }

    override fun write(
        key: String,
        value: String?,
    ) {
        prefs
            .edit()
            .apply {
                if (value == null) remove(key) else putString(key, value)
            }.apply()
    }
}
