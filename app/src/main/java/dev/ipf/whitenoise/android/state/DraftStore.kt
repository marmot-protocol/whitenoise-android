package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
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
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {
    private val lock = Any()
    private val drafts = LinkedHashMap<String, MutableState<String?>>(16, 0.75f, true)
    private val collectedEvictedDraftStates = ReferenceQueue<MutableState<String?>>()
    private val evictedEmptyDraftStates = mutableMapOf<String, EvictedDraftStateReference>()

    // Unix-seconds "drafted-at" per key, updated only when a draft starts
    // (empty→non-empty) or clears — never on an ordinary keystroke — so the
    // chat list re-sorts on those transitions alone, not on every character.
    // Kept off the per-key draft [MutableState]s deliberately: routing it
    // through those would either recompose every row per keystroke or need a
    // shared revision the store was built to avoid.
    private val draftedAtSeconds = HashMap<String, Long>()

    /** Fired after a draft's sort timestamp changes (start or clear), outside
     *  the lock, so the chat list can re-sort. Never fired per keystroke. */
    var onDraftSortOrderChanged: (() -> Unit)? = null

    init {
        persistence.read().forEach { (k, value) ->
            drafts[k] = mutableStateOf(value)
            // Restore the persisted drafted-at so a draft written before a
            // process restart still promotes its chat. v1/legacy blobs carry
            // none; those get stamped on the next edit (see set).
            decodeComposerDraftStored(value).draftedAtSeconds?.let { draftedAtSeconds[k] = it }
        }
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
        var sortOrderChanged = false
        // Keep persistence writes inside the same lock as the cache mutation so
        // set/clear cannot interleave between the in-memory and backing-store updates.
        synchronized(lock) {
            if (value.text.isBlank()) {
                val state = drafts[k] ?: return@synchronized
                if (state.value != null) {
                    state.value = null
                    persistence.write(k, null)
                    if (draftedAtSeconds.remove(k) != null) sortOrderChanged = true
                    pruneEmptyDraftStatesLocked()
                }
                return@synchronized
            }

            val state = stateForLocked(k)
            // Stamp the empty→non-empty transition — the moment drafting began —
            // or a restored draft with no stamp yet (a v1/legacy blob), so
            // editing a persisted draft still promotes its chat. Editing an
            // already-stamped draft leaves the stamp and sort order untouched.
            if (state.value == null || draftedAtSeconds[k] == null) {
                draftedAtSeconds[k] = nowSeconds()
                sortOrderChanged = true
            }
            val encoded = encodeComposerDraft(value, draftedAtSeconds[k])
            if (state.value != encoded) {
                state.value = encoded
                persistence.write(k, encoded)
            }
            pruneEmptyDraftStatesLocked(retainedState = state)
        }
        if (sortOrderChanged) onDraftSortOrderChanged?.invoke()
    }

    /** Drafted-at (unix seconds) for the chat, or null when it has no draft. */
    fun draftedAtSecondsFor(
        accountIdHex: String,
        groupIdHex: String,
    ): ULong? = synchronized(lock) { draftedAtSeconds[key(accountIdHex, groupIdHex)]?.toULong() }

    /**
     * Appends [incoming] to an existing draft with a newline separator. Blank
     * [incoming] is a no-op. Used by inbound share staging — never overwrites
     * an existing composer draft.
     */
    fun mergeText(
        accountIdHex: String,
        groupIdHex: String,
        incoming: String,
    ) {
        val trimmedIncoming = incoming.trim()
        if (trimmedIncoming.isEmpty()) return
        val existing = get(accountIdHex, groupIdHex)
        val merged =
            if (existing.isNullOrBlank()) {
                trimmedIncoming
            } else {
                "${existing.trimEnd()}\n$trimmedIncoming"
            }
        set(accountIdHex, groupIdHex, TextFieldValue(merged, TextRange(merged.length)))
    }

    fun clearAllForAccount(accountIdHex: String) {
        val prefix = "$accountIdHex "
        var sortOrderChanged = false
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
                    if (draftedAtSeconds.remove(k) != null) sortOrderChanged = true
                    pruneEmptyDraftStatesLocked()
                }
            }
        }
        if (sortOrderChanged) onDraftSortOrderChanged?.invoke()
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
        private const val MILLIS_PER_SECOND = 1_000L

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
