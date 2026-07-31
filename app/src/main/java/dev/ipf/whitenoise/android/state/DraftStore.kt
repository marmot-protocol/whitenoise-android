package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.IOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    fun flush() = Unit
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
@Suppress("TooManyFunctions")
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

    /** Durably drains any coalesced background persistence work. */
    fun flush() = persistence.flush()

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
 * Draft text is message-shaped plaintext, so it is sealed with an AES-GCM key
 * held in the Android Keystore rather than written to a plaintext file. The
 * previous implementation used the now-EOL `androidx.security-crypto` stack;
 * existing drafts are imported once from that file and it is then deleted.
 */
internal class EncryptedDraftPersistence(
    context: Context,
    writeExecutor: ExecutorService = newDraftWriteExecutor(),
) : DraftPersistence {
    private val app = context.applicationContext
    private val store =
        KeystoreSecureStore(
            context = app,
            fileName = SECURE_FILE,
            keyProvider = AndroidKeystoreSecretKeyProvider(KEY_ALIAS),
        )
    private val writer: CoalescingDraftWriter

    init {
        importLegacyDrafts()
        writer =
            CoalescingDraftWriter(
                initial = readSecureStore(),
                executor = writeExecutor,
                persist = ::persistSnapshot,
            )
    }

    override fun read(): Map<String, String> = writer.read()

    override fun write(
        key: String,
        value: String?,
    ) = writer.write(key, value)

    override fun flush() = writer.flush()

    private fun readSecureStore(): Map<String, String> =
        try {
            store.readAll()
        } catch (error: GeneralSecurityException) {
            // A rotated/cleared Keystore key or a tampered payload leaves the
            // store undecryptable; drafts are disposable, so drop it and start
            // fresh rather than failing every read.
            Log.w(LOG_TAG, "draft store unreadable, recreating", error)
            recreateAfterCorruption()
            emptyMap()
        }

    private fun persistSnapshot(values: Map<String, String>) {
        try {
            if (!store.replaceAllDurably(values)) {
                Log.w(LOG_TAG, "draft snapshot commit failed")
            }
        } catch (error: GeneralSecurityException) {
            Log.w(LOG_TAG, "draft write failed, recreating store", error)
            recreateAfterCorruption()
            runCatching { store.replaceAllDurably(values) }
        }
    }

    private fun recreateAfterCorruption() {
        runCatching { store.clear() }
    }

    /**
     * One-way import from the retired library, routed through [migrateDrafts]
     * so it keeps that helper's two guarantees: encrypted values win over the
     * legacy copy, and the legacy file is deleted only once the new store has
     * DURABLY committed. Deleting on a transient failure — or before the write
     * reaches disk — would lose a draft the user is mid-way through typing.
     */
    private fun importLegacyDrafts() {
        val legacy = readLegacyDrafts()
        val existingKeys = if (legacy == null) null else secureKeys()
        if (legacy != null && existingKeys != null) {
            migrateDrafts(
                legacy = legacy,
                existingSecureKeys = existingKeys,
                persistSecure = ::persistImportedDrafts,
                clearLegacy = { app.deleteSharedPreferences(LEGACY_SECURE_FILE) },
            )
        }
    }

    // Null when there is nothing to import, or when the legacy keyset is
    // unreadable — in which case the file is only dead weight and is dropped.
    private fun readLegacyDrafts(): Map<String, String>? =
        try {
            LegacySecurePreferences.read(app, LEGACY_SECURE_FILE)
        } catch (error: GeneralSecurityException) {
            Log.w(LOG_TAG, "legacy draft store unreadable, discarding", error)
            app.deleteSharedPreferences(LEGACY_SECURE_FILE)
            null
        } catch (error: IOException) {
            Log.w(LOG_TAG, "legacy draft store unreadable, discarding", error)
            app.deleteSharedPreferences(LEGACY_SECURE_FILE)
            null
        }

    // Null aborts the import: without knowing what the new store already holds,
    // migrateDrafts cannot honour "encrypted values win".
    private fun secureKeys(): Set<String>? =
        try {
            store.readAll().keys
        } catch (error: GeneralSecurityException) {
            Log.w(LOG_TAG, "draft store unreadable during import", error)
            null
        }

    private fun persistImportedDrafts(fresh: Map<String, String>): Boolean =
        try {
            store.putAllDurably(fresh)
        } catch (error: GeneralSecurityException) {
            Log.w(LOG_TAG, "draft import write failed, keeping legacy file", error)
            false
        }

    private companion object {
        const val LOG_TAG = "DMDrafts"
        const val SECURE_FILE = "whitenoise.drafts.keystore"
        const val LEGACY_SECURE_FILE = "whitenoise.drafts.secure"
        const val KEY_ALIAS = "whitenoise.drafts.aes_gcm.v1"

        fun newDraftWriteExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "WhiteNoiseDraftWriter").apply { isDaemon = true }
            }
    }
}

/**
 * Keeps the latest draft map in memory and serializes durable writes through a
 * single background worker. A burst of keystrokes produces at most one active
 * encryption plus one latest snapshot, rather than one whole-map encryption
 * per key event on the main thread.
 */
internal class CoalescingDraftWriter(
    initial: Map<String, String>,
    private val executor: ExecutorService,
    private val persist: (Map<String, String>) -> Unit,
) {
    private val lock = Any()
    private var values = initial.toMap()
    private var generation = 0L
    private var workerScheduled = false
    private var workerDone = CountDownLatch(0)

    fun read(): Map<String, String> = synchronized(lock) { values.toMap() }

    fun write(
        key: String,
        value: String?,
    ) {
        synchronized(lock) {
            val updated = if (value == null) values - key else values + (key to value)
            if (updated == values) return
            values = updated
            generation += 1
            scheduleWorkerLocked()
        }
    }

    fun flush() {
        while (true) {
            val completion =
                synchronized(lock) {
                    if (!workerScheduled) return
                    workerDone
                }
            completion.await()
        }
    }

    private fun drain(completion: CountDownLatch) {
        try {
            while (true) {
                val (snapshot, snapshotGeneration) =
                    synchronized(lock) { values.toMap() to generation }
                persist(snapshot)
                val caughtUp =
                    synchronized(lock) {
                        if (generation == snapshotGeneration) {
                            workerScheduled = false
                            true
                        } else {
                            false
                        }
                    }
                if (caughtUp) return
            }
        } finally {
            synchronized(lock) {
                if (workerDone === completion) {
                    workerScheduled = false
                }
                completion.countDown()
            }
        }
    }

    private fun scheduleWorkerLocked() {
        if (workerScheduled) return
        workerScheduled = true
        val completion = CountDownLatch(1)
        workerDone = completion
        executor.execute { drain(completion) }
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
