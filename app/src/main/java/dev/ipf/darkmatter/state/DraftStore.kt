package dev.ipf.darkmatter.state

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

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
        fun forContext(context: Context): DraftStore = DraftStore(EncryptedDraftPersistence(context.applicationContext))
    }
}

/**
 * Draft text is message-shaped plaintext, so it is held in an
 * [EncryptedSharedPreferences] store keyed by the Android Keystore rather than
 * the plaintext file the drafts originally shipped in. Existing plaintext
 * drafts are migrated into the encrypted store once, then the plaintext file
 * is wiped.
 */
internal class EncryptedDraftPersistence(
    context: Context,
) : DraftPersistence {
    private val prefs: SharedPreferences = openSecure(context.applicationContext)

    init {
        migrateLegacyPlaintext(context.applicationContext, prefs)
    }

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

    private companion object {
        const val TAG = "DraftStore"
        const val SECURE_FILE = "darkmatter.drafts.secure"
        const val LEGACY_FILE = "darkmatter.drafts"

        fun openSecure(context: Context): SharedPreferences =
            try {
                create(context)
            } catch (error: GeneralSecurityException) {
                // A rotated/cleared Keystore key or tampered keyset leaves the
                // file undecryptable; drafts are disposable, so drop the corrupt
                // store and start fresh. Unrelated failures propagate rather
                // than silently wiping valid drafts.
                recreateAfterCorruption(context)
            } catch (error: IOException) {
                recreateAfterCorruption(context)
            }

        fun recreateAfterCorruption(context: Context): SharedPreferences {
            context.deleteSharedPreferences(SECURE_FILE)
            return create(context)
        }

        fun create(context: Context): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun migrateLegacyPlaintext(
            context: Context,
            secure: SharedPreferences,
        ) {
            val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)

            @Suppress("UNCHECKED_CAST")
            val legacyDrafts = legacy.all.filterValues { it is String } as Map<String, String>
            if (legacyDrafts.isEmpty()) return
            migrateDrafts(
                legacy = legacyDrafts,
                existingSecureKeys = secure.all.keys,
                persistSecure = { drafts ->
                    val editor = secure.edit()
                    drafts.forEach { (key, value) -> editor.putString(key, value) }
                    editor.commit()
                },
                clearLegacy = {
                    val cleared = legacy.edit().clear().commit()
                    val deleted = context.deleteSharedPreferences(LEGACY_FILE)
                    if (!cleared || !deleted) {
                        // The plaintext drafts survived the wipe. The collision
                        // guard keeps them from clobbering newer encrypted edits
                        // on the next launch, but the plaintext copy lingers on
                        // disk — surface it rather than swallowing the failure.
                        Log.w(TAG, "legacy plaintext draft wipe incomplete (cleared=$cleared deleted=$deleted)")
                    }
                },
            )
        }
    }
}

/**
 * One-way migration: copy legacy plaintext drafts into the encrypted store,
 * then wipe the plaintext source. Two guarantees:
 *
 *  - Encrypted values win: a key already present in [existingSecureKeys] is
 *    never re-migrated, so a plaintext file that outlived a failed wipe can't
 *    clobber a newer encrypted edit on a later launch.
 *  - Durability before wipe: the plaintext is cleared only once [persistSecure]
 *    confirms the encrypted copy is durably committed — a non-durable write
 *    lost to process death would otherwise take the drafts with it.
 *
 * Pure over its collaborators so both guarantees can be unit-tested without an
 * Android Keystore.
 */
internal fun migrateDrafts(
    legacy: Map<String, String>,
    existingSecureKeys: Set<String>,
    persistSecure: (Map<String, String>) -> Boolean,
    clearLegacy: () -> Unit,
) {
    val toMigrate = legacy.filterKeys { it !in existingSecureKeys }
    // Nothing fresh to copy means the plaintext is fully superseded; still wipe
    // it. Otherwise wipe only after the encrypted copy durably commits.
    if (toMigrate.isEmpty() || persistSecure(toMigrate)) {
        clearLegacy()
    }
}
