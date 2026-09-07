package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateMapOf

internal enum class RetainedComposerExpansionMode {
    Manual,
    FullScreen,
}

/** The minimum UI-only geometry needed to restore a user's explicit composer choice. */
internal data class RetainedComposerExpansion(
    val mode: RetainedComposerExpansionMode,
    val manualHeightDp: Float?,
)

/** Bundle-safe representation used by the main shell's existing saved-state boundary. */
internal data class SavedComposerExpansion(
    val accountRef: String,
    val groupIdHex: String,
    val mode: RetainedComposerExpansionMode,
    val manualHeightDp: Float?,
)

/**
 * Bounded Android UI state for manual composer geometry.
 *
 * Message text remains MDK-owned. Records are keyed only by account and
 * conversation; draft and UI revisions fence delayed send callbacks without
 * making ordinary edits change the stable lookup key.
 */
@Suppress("TooManyFunctions") // One bounded state machine owns every geometry lifecycle transition.
internal class ComposerExpansionStateRetention(
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) {
    private data class Key(
        val accountRef: String,
        val groupIdHex: String,
    )

    private data class PendingSend(
        val uiRevision: Long,
        val recovery: RetainedComposerExpansion,
    )

    private data class Entry(
        val presentation: RetainedComposerExpansion?,
        val draftGeneration: Long,
        val uiRevision: Long,
        val pendingSend: PendingSend? = null,
    )

    private val entries = mutableStateMapOf<Key, Entry>()
    private val insertionOrder = ArrayDeque<Key>()
    private val listeners = linkedSetOf<(List<SavedComposerExpansion>) -> Unit>()
    private var nextUiRevision = 0L

    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    /** Returns the current presentation for one exact account/conversation owner. */
    fun preferenceFor(
        accountRef: String,
        groupIdHex: String,
    ): RetainedComposerExpansion? = entries[Key(accountRef, groupIdHex)]?.presentation

    /** Returns the UI revision captured by a send, or null when the draft is automatic. */
    fun revisionFor(
        accountRef: String,
        groupIdHex: String,
    ): Long? = entries[Key(accountRef, groupIdHex)]?.uiRevision

    /** Records an explicit resize/toggle, or removes the override for Automatic. */
    @Suppress("ReturnCount") // Owner, staleness, and malformed-state guards must fail before mutation.
    fun update(
        accountRef: String,
        groupIdHex: String,
        preference: RetainedComposerExpansion?,
        draftGeneration: Long,
    ) {
        val key = Key(accountRef, groupIdHex)
        if (entries[key]?.draftGeneration?.let { draftGeneration < it } == true) return
        if (preference == null) {
            if (entries.remove(key) != null) {
                insertionOrder.remove(key)
                notifyListeners()
            }
            return
        }
        if (!preference.isValid() || accountRef.isBlank() || groupIdHex.isBlank()) return
        nextUiRevision += 1L
        entries[key] =
            Entry(
                presentation = preference,
                draftGeneration = draftGeneration,
                uiRevision = nextUiRevision,
            )
        insertionOrder.remove(key)
        insertionOrder.addLast(key)
        pruneToBound()
        notifyListeners()
    }

    /** Keeps a selected height while moving its stale-callback fence to the newest draft edit. */
    fun onDraftGenerationAdvanced(
        accountRef: String,
        groupIdHex: String,
        draftGeneration: Long,
    ) {
        val key = Key(accountRef, groupIdHex)
        val entry = entries[key] ?: return
        if (draftGeneration <= entry.draftGeneration) return
        if (entry.pendingSend != null) {
            entries.remove(key)
            insertionOrder.remove(key)
            notifyListeners()
        } else {
            entries[key] = entry.copy(draftGeneration = draftGeneration)
        }
    }

    /** Temporarily presents Automatic while retaining an exact failure recovery snapshot. */
    @Suppress("ReturnCount") // Each failed ownership fence returns without partially hiding state.
    fun onSendAccepted(
        accountRef: String,
        groupIdHex: String,
        draftGeneration: Long,
        uiRevision: Long?,
    ): Boolean {
        val key = Key(accountRef, groupIdHex)
        val entry = entries[key] ?: return uiRevision == null
        if (!entry.matches(draftGeneration, uiRevision) || entry.presentation == null) return false
        entries[key] =
            entry.copy(
                presentation = null,
                pendingSend = PendingSend(entry.uiRevision, entry.presentation),
            )
        notifyListeners()
        return true
    }

    /** Deletes the override only for the exact send that reached durable acceptance. */
    @Suppress("ReturnCount") // Missing and stale owners are distinct no-mutation guards.
    fun onSendDurablyAccepted(
        accountRef: String,
        groupIdHex: String,
        draftGeneration: Long,
        uiRevision: Long?,
    ): Boolean {
        val key = Key(accountRef, groupIdHex)
        val entry = entries[key] ?: return uiRevision == null
        if (!entry.matches(draftGeneration, uiRevision)) return false
        entries.remove(key)
        insertionOrder.remove(key)
        notifyListeners()
        return true
    }

    /** Restores a hidden override after a definite failure, if no newer draft or resize won. */
    @Suppress("ReturnCount") // Every generation/revision guard must precede the single restoring write.
    fun onSendTerminalFailure(
        accountRef: String,
        groupIdHex: String,
        draftGeneration: Long,
        uiRevision: Long?,
    ): Boolean {
        val key = Key(accountRef, groupIdHex)
        val entry = entries[key] ?: return false
        val pending = entry.pendingSend ?: return false
        if (!entry.matches(draftGeneration, uiRevision) || pending.uiRevision != uiRevision) return false
        entries[key] = entry.copy(presentation = pending.recovery, pendingSend = null)
        notifyListeners()
        return true
    }

    /** Deletes retained geometry when the owning group leaves the local lifecycle. */
    fun removeGroup(
        accountRef: String,
        groupIdHex: String,
    ) {
        val key = Key(accountRef, groupIdHex)
        if (entries.remove(key) != null) {
            insertionOrder.remove(key)
            notifyListeners()
        }
    }

    /** Deletes every retained record made inaccessible by account removal or sign-out. */
    fun removeAccount(accountRef: String) {
        val removed = entries.keys.filter { it.accountRef == accountRef }
        if (removed.isEmpty()) return
        removed.forEach(entries::remove)
        insertionOrder.removeAll(removed.toSet())
        notifyListeners()
    }

    /** Returns the recoverable choice; transient optimistic Automatic is never persisted. */
    fun savedRecords(): List<SavedComposerExpansion> =
        insertionOrder.mapNotNull { key ->
            val entry = entries[key] ?: return@mapNotNull null
            val preference = entry.pendingSend?.recovery ?: entry.presentation ?: return@mapNotNull null
            SavedComposerExpansion(
                accountRef = key.accountRef,
                groupIdHex = key.groupIdHex,
                mode = preference.mode,
                manualHeightDp = preference.manualHeightDp,
            )
        }

    /** Restores validated records only when no newer process-owned state exists. */
    fun restoreIfEmpty(records: List<SavedComposerExpansion>) {
        if (entries.isNotEmpty()) return
        records
            .mapNotNull { saved ->
                val preference = RetainedComposerExpansion(saved.mode, saved.manualHeightDp)
                saved
                    .takeIf {
                        saved.accountRef.isNotBlank() &&
                            saved.groupIdHex.isNotBlank() &&
                            preference.isValid()
                    }?.let { it to preference }
            }.takeLast(maxRecords)
            .forEach { (saved, preference) ->
                nextUiRevision += 1L
                val key = Key(saved.accountRef, saved.groupIdHex)
                entries[key] = Entry(preference, draftGeneration = 0L, uiRevision = nextUiRevision)
                insertionOrder.remove(key)
                insertionOrder.addLast(key)
            }
    }

    /** Immediately publishes every subsequent bounded snapshot until closed. */
    fun observe(listener: (List<SavedComposerExpansion>) -> Unit): AutoCloseable {
        listeners += listener
        listener(savedRecords())
        return AutoCloseable { listeners -= listener }
    }

    /** Accepts a callback only for the exact draft generation and UI revision it captured. */
    private fun Entry.matches(
        draftGeneration: Long,
        uiRevision: Long?,
    ): Boolean = this.draftGeneration == draftGeneration && this.uiRevision == uiRevision

    /** Rejects malformed restored geometry before it becomes observable Compose state. */
    private fun RetainedComposerExpansion.isValid(): Boolean =
        when (mode) {
            RetainedComposerExpansionMode.FullScreen -> manualHeightDp == null
            RetainedComposerExpansionMode.Manual -> manualHeightDp?.let { it.isFinite() && it > 0f } == true
        }

    /** Evicts least-recently-updated owners until the process UI bound is restored. */
    private fun pruneToBound() {
        while (entries.size > maxRecords) {
            entries.remove(insertionOrder.removeFirst())
        }
    }

    /** Publishes one immutable, bounded snapshot outside collection mutation. */
    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val snapshot = savedRecords()
        listeners.toList().forEach { it(snapshot) }
    }

    private companion object {
        const val DEFAULT_MAX_RECORDS = 32
    }
}
