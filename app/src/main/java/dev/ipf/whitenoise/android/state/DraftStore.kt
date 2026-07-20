package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException

/**
 * Persistence layer for unsent conversation drafts. The storage map is keyed
 * by `"<accountIdHex> <groupIdHex>"`; values are versioned draft blobs.
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

private class EvictedDraftStateReference(
    val key: String,
    state: MutableState<String?>,
    queue: ReferenceQueue<MutableState<String?>>,
) : WeakReference<MutableState<String?>>(state, queue)

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
    private val lock = Any()
    private val drafts = LinkedHashMap<String, MutableState<String?>>(16, 0.75f, true)
    private val collectedEvictedDraftStates = ReferenceQueue<MutableState<String?>>()
    private val evictedEmptyDraftStates = mutableMapOf<String, EvictedDraftStateReference>()

    init {
        persistence.read().forEach { (k, value) -> drafts[k] = mutableStateOf(value) }
    }

    // Per-key state so reads/writes are observed independently. Creating an
    // empty state on a miss is what lets a composable that read a not-yet-set
    // draft recompose once it is set. Evicted empty states remain weakly
    // reachable so an active observer keeps the same state identity on a later
    // write, while unobserved states remain eligible for garbage collection.
    private fun stateFor(k: String): MutableState<String?> =
        synchronized(lock) {
            stateForLocked(k).also { pruneEmptyDraftStatesLocked(retainedState = it) }
        }

    private fun stateForLocked(k: String): MutableState<String?> {
        drainCollectedEvictedDraftStatesLocked()
        drafts[k]?.let { return it }
        val state = evictedEmptyDraftStates.remove(k)?.get() ?: mutableStateOf<String?>(null)
        drafts[k] = state
        return state
    }

    private fun drainCollectedEvictedDraftStatesLocked() {
        while (true) {
            val reference = collectedEvictedDraftStates.poll() as? EvictedDraftStateReference ?: return
            if (evictedEmptyDraftStates[reference.key] === reference) {
                evictedEmptyDraftStates.remove(reference.key)
            }
        }
    }

    fun get(
        accountIdHex: String,
        groupIdHex: String,
    ): String? = getDraft(accountIdHex, groupIdHex)?.textFieldValue?.text

    fun getDraft(
        accountIdHex: String,
        groupIdHex: String,
    ): ComposerDraftSnapshot? {
        val stored = stateFor(key(accountIdHex, groupIdHex)).value ?: return null
        return decodeComposerDraftStored(stored)
    }

    /**
     * Sets the draft. Empty or whitespace-only text clears the draft so we
     * don't store noise. Selection-only updates are persisted too.
     */
    fun set(
        accountIdHex: String,
        groupIdHex: String,
        value: TextFieldValue,
    ) {
        val k = key(accountIdHex, groupIdHex)
        // Keep persistence writes inside the same lock as the cache mutation so
        // set/clear cannot interleave between the in-memory and backing-store updates.
        synchronized(lock) {
            if (value.text.isBlank()) {
                val state = drafts[k] ?: return@synchronized
                if (state.value != null) {
                    state.value = null
                    persistence.write(k, null)
                    pruneEmptyDraftStatesLocked()
                }
                return@synchronized
            }

            val encoded = encodeComposerDraft(value)
            val state = stateForLocked(k)
            if (state.value != encoded) {
                state.value = encoded
                persistence.write(k, encoded)
            }
            pruneEmptyDraftStatesLocked(retainedState = state)
        }
    }

    fun clearAllForAccount(accountIdHex: String) {
        val prefix = "$accountIdHex "
        synchronized(lock) {
            val matchingDrafts =
                drafts.entries
                    .mapNotNull { (k, state) ->
                        val value = state.value
                        if (k.startsWith(prefix) && value != null) Triple(k, state, value) else null
                    }
            matchingDrafts.forEach { (k, state, snapshottedValue) ->
                if (state.value == snapshottedValue) {
                    state.value = null
                    persistence.write(k, null)
                    pruneEmptyDraftStatesLocked()
                }
            }
        }
    }

    private fun pruneEmptyDraftStatesLocked(retainedState: MutableState<String?>? = null) {
        drainCollectedEvictedDraftStatesLocked()
        if (drafts.size <= MAX_IN_MEMORY_DRAFT_STATES) return
        val iterator = drafts.entries.iterator()
        while (drafts.size > MAX_IN_MEMORY_DRAFT_STATES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value !== retainedState && entry.value.value == null) {
                iterator.remove()
                evictedEmptyDraftStates[entry.key] =
                    EvictedDraftStateReference(entry.key, entry.value, collectedEvictedDraftStates)
            }
        }
    }

    private fun key(
        accountIdHex: String,
        groupIdHex: String,
    ): String = "$accountIdHex $groupIdHex"

    companion object {
        internal const val MAX_IN_MEMORY_DRAFT_STATES = 512

        fun forContext(context: Context): DraftStore = DraftStore(EncryptedDraftPersistence(context.applicationContext))
    }
}

/**
 * Draft text is message-shaped plaintext, so it is held in an
 * [EncryptedSharedPreferences] store keyed by the Android Keystore rather than
 * a plaintext file.
 */
internal class EncryptedDraftPersistence(
    context: Context,
) : DraftPersistence {
    private val prefs: SharedPreferences = openSecure(context.applicationContext)

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
        const val SECURE_FILE = "whitenoise.drafts.secure"

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
