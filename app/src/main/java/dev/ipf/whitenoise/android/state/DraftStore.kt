package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService

/**
 * Injectable persistence seam retained for lifecycle-state tests. Production
 * passes a no-op implementation because MDK owns durable message drafts.
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
 * Lifecycle cache for MDK-owned drafts per `(accountRef, groupIdHex)`.
 * Production uses a no-op persistence; the injectable persistence remains for
 * deterministic state-holder tests and the one-time legacy migration reader.
 *
 * Each draft is held in its own Compose [MutableState] keyed by
 * `(accountRef, groupIdHex)`, so a composable that called [get] re-composes
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
    private val evictedDraftStates = mutableMapOf<String, EvictedDraftStateReference>()
    private val evictedDraftValues = mutableMapOf<String, String>()

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
            stateForLocked(k).also { pruneDraftStatesLocked(retainedState = it) }
        }

    private fun stateForLocked(k: String): MutableState<String?> {
        drainCollectedEvictedDraftStatesLocked()
        drafts[k]?.let { return it }
        val state = evictedDraftStates.remove(k)?.get() ?: mutableStateOf(evictedDraftValues[k])
        evictedDraftValues.remove(k)
        drafts[k] = state
        return state
    }

    private fun drainCollectedEvictedDraftStatesLocked() {
        while (true) {
            val reference = collectedEvictedDraftStates.poll() as? EvictedDraftStateReference ?: return
            if (evictedDraftStates[reference.key] === reference) {
                evictedDraftStates.remove(reference.key)
            }
        }
    }

    fun get(
        accountRef: String,
        groupIdHex: String,
    ): String? = getDraft(accountRef, groupIdHex)?.textFieldValue?.text

    fun getDraft(
        accountRef: String,
        groupIdHex: String,
    ): ComposerDraftSnapshot? {
        val stored = stateFor(key(accountRef, groupIdHex)).value ?: return null
        return decodeComposerDraftStored(stored)
    }

    /**
     * Sets the draft. Empty or whitespace-only text clears the draft so we
     * don't store noise. Selection-only updates are persisted too.
     */
    fun set(
        accountRef: String,
        groupIdHex: String,
        value: TextFieldValue,
    ) {
        val k = key(accountRef, groupIdHex)
        var sortOrderChanged = false
        // Keep persistence writes inside the same lock as the cache mutation so
        // set/clear cannot interleave between the in-memory and backing-store updates.
        synchronized(lock) {
            if (value.text.isBlank()) {
                val state =
                    drafts[k]
                        ?: if (k in evictedDraftValues || evictedDraftStates[k]?.get()?.value != null) {
                            stateForLocked(k)
                        } else {
                            return@synchronized
                        }
                if (state.value != null) {
                    state.value = null
                    persistence.write(k, null)
                    if (draftedAtSeconds.remove(k) != null) sortOrderChanged = true
                    pruneDraftStatesLocked()
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
            pruneDraftStatesLocked(retainedState = state)
        }
        if (sortOrderChanged) onDraftSortOrderChanged?.invoke()
    }

    /** Drafted-at (unix seconds) for the chat, or null when it has no draft. */
    fun draftedAtSecondsFor(
        accountRef: String,
        groupIdHex: String,
    ): ULong? = synchronized(lock) { draftedAtSeconds[key(accountRef, groupIdHex)]?.toULong() }

    /** Seeds authoritative MDK metadata without hydrating attachment plaintext. */
    fun replaceSummaries(
        accountRef: String,
        draftedAtMillisByGroup: Map<String, Long>,
    ) {
        val prefix = "$accountRef "
        synchronized(lock) {
            draftedAtSeconds.keys.removeAll { it.startsWith(prefix) }
            draftedAtMillisByGroup.forEach { (groupIdHex, draftedAtMs) ->
                draftedAtSeconds[key(accountRef, groupIdHex)] = draftedAtMs / MILLIS_PER_SECOND
            }
        }
        onDraftSortOrderChanged?.invoke()
    }

    /** Hydrates content once; a local edit that already exists always wins. */
    fun hydrate(
        accountRef: String,
        groupIdHex: String,
        content: String,
        draftedAtMs: Long,
        replaceExisting: Boolean = false,
    ) {
        val k = key(accountRef, groupIdHex)
        var sortOrderChanged = false
        synchronized(lock) {
            val state = stateForLocked(k)
            if ((state.value == null || replaceExisting) && content.isNotBlank()) {
                val selection = TextRange(content.length)
                val draftedAt = draftedAtMs / MILLIS_PER_SECOND
                sortOrderChanged = draftedAtSeconds[k] != draftedAt
                draftedAtSeconds[k] = draftedAt
                state.value = encodeComposerDraft(TextFieldValue(content, selection), draftedAt)
            }
            pruneDraftStatesLocked(retainedState = state)
        }
        if (sortOrderChanged) onDraftSortOrderChanged?.invoke()
    }

    /** Reconciles lifecycle text after pending writes have been flushed to MDK. */
    fun replaceFromAuthoritative(
        accountRef: String,
        groupIdHex: String,
        content: String?,
        draftedAtMs: Long?,
    ) {
        val k = key(accountRef, groupIdHex)
        synchronized(lock) {
            val state = stateForLocked(k)
            if (content.isNullOrBlank()) {
                state.value = null
                draftedAtSeconds.remove(k)
            } else {
                val draftedAt = draftedAtMs?.div(MILLIS_PER_SECOND)
                if (draftedAt != null) draftedAtSeconds[k] = draftedAt
                state.value =
                    encodeComposerDraft(
                        TextFieldValue(content, TextRange(content.length)),
                        draftedAtSeconds[k],
                    )
            }
            pruneDraftStatesLocked(retainedState = state)
        }
        onDraftSortOrderChanged?.invoke()
    }

    fun applyAuthoritativeTimestamp(
        accountRef: String,
        groupIdHex: String,
        draftedAtMs: Long?,
    ) {
        synchronized(lock) {
            val k = key(accountRef, groupIdHex)
            if (draftedAtMs == null) {
                draftedAtSeconds.remove(k)
            } else {
                draftedAtSeconds[k] = draftedAtMs / MILLIS_PER_SECOND
            }
        }
        onDraftSortOrderChanged?.invoke()
    }

    /**
     * Appends [incoming] to an existing draft with a newline separator. Blank
     * [incoming] is a no-op. Used by inbound share staging — never overwrites
     * an existing composer draft.
     */
    fun mergeText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
    ) {
        val trimmedIncoming = incoming.trim()
        if (trimmedIncoming.isEmpty()) return
        val existing = get(accountRef, groupIdHex)
        val merged =
            if (existing.isNullOrBlank()) {
                trimmedIncoming
            } else {
                "${existing.trimEnd()}\n$trimmedIncoming"
            }
        set(accountRef, groupIdHex, TextFieldValue(merged, TextRange(merged.length)))
    }

    fun clearAllForAccount(accountRef: String) {
        val prefix = "$accountRef "
        var sortOrderChanged = false
        synchronized(lock) {
            val matchingDrafts =
                (drafts.keys + evictedDraftStates.keys + evictedDraftValues.keys)
                    .asSequence()
                    .filter { it.startsWith(prefix) }
                    .distinct()
                    .mapNotNull { k ->
                        val state = stateForLocked(k)
                        state.value?.let { value -> Triple(k, state, value) }
                    }.toList()
            matchingDrafts.forEach { (k, state, snapshottedValue) ->
                if (state.value == snapshottedValue) {
                    state.value = null
                    persistence.write(k, null)
                    if (draftedAtSeconds.remove(k) != null) sortOrderChanged = true
                }
            }
            pruneDraftStatesLocked()
        }
        if (sortOrderChanged) onDraftSortOrderChanged?.invoke()
    }

    /** Durably drains any coalesced background persistence work. */
    fun flush() = persistence.flush()

    private fun pruneDraftStatesLocked(retainedState: MutableState<String?>? = null) {
        drainCollectedEvictedDraftStatesLocked()
        if (drafts.size <= MAX_IN_MEMORY_DRAFT_STATES) return
        val iterator = drafts.entries.iterator()
        while (drafts.size > MAX_IN_MEMORY_DRAFT_STATES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value !== retainedState) {
                iterator.remove()
                entry.value.value?.let { evictedDraftValues[entry.key] = it }
                    ?: evictedDraftValues.remove(entry.key)
                evictedDraftStates[entry.key] =
                    EvictedDraftStateReference(entry.key, entry.value, collectedEvictedDraftStates)
            }
        }
    }

    private fun key(
        accountRef: String,
        groupIdHex: String,
    ): String = "$accountRef $groupIdHex"

    companion object {
        internal const val MAX_IN_MEMORY_DRAFT_STATES = 512
        private const val MILLIS_PER_SECOND = 1_000L

        fun forContext(
            @Suppress("UNUSED_PARAMETER") context: Context,
        ): DraftStore = DraftStore(NoOpDraftPersistence)
    }
}

internal class LegacyDraftMigrationSource(
    context: Context,
) {
    private val app = context.applicationContext
    private val store =
        KeystoreSecureStore(
            context = app,
            fileName = SECURE_FILE,
            keyProvider = AndroidKeystoreSecretKeyProvider(KEY_ALIAS),
        )

    fun read(): Map<String, String> =
        runCatching { store.readAll() }.getOrElse {
            Log.w(LOG_TAG, "legacy_draft_store_unreadable")
            emptyMap()
        }

    fun confirmMigrated(key: String): Boolean =
        runCatching {
            val remaining = store.readAll() - key
            store.replaceAllDurably(remaining).also { committed ->
                if (committed && remaining.isEmpty()) {
                    store.clear()
                }
            }
        }.getOrDefault(false)

    private companion object {
        const val LOG_TAG = "DMDrafts"
        const val SECURE_FILE = "whitenoise.drafts.keystore"
        const val KEY_ALIAS = "whitenoise.drafts.aes_gcm.v1"
    }
}

private object NoOpDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
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
    private val writes = StalenessGuard()
    private var workerScheduled = false
    private var workerDone = CountDownLatch(0)

    /** Returns an immutable snapshot of the newest accepted in-memory draft values. */
    fun read(): Map<String, String> = synchronized(lock) { values.toMap() }

    /** Updates the in-memory draft map and coalesces its encrypted persistence. */
    fun write(
        key: String,
        value: String?,
    ) {
        synchronized(lock) {
            val updated = if (value == null) values - key else values + (key to value)
            if (updated == values) return
            values = updated
            writes.advance()
            scheduleWorkerLocked()
        }
    }

    /** Blocks until the coalesced writer has persisted every value accepted before this call. */
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

    /** Persists snapshots until disk catches the latest accepted in-memory write. */
    private fun drain(completion: CountDownLatch) {
        try {
            while (true) {
                val (snapshot, snapshotGeneration) =
                    synchronized(lock) { values.toMap() to writes.capture() }
                persist(snapshot)
                val caughtUp =
                    synchronized(lock) {
                        if (writes.isCurrent(snapshotGeneration)) {
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
