package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.media.ByteSizeLruCache
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Per-conversation send state that outlives one conversation controller: optimistic rows, ordering
 * overrides, send phases, accepted-text bridges and retained uploads. One lock guards the records,
 * their bounded retention window and the account wipe. Accessors return the same live collections
 * until the conversation is evicted or the account is wiped, so a replacement controller after
 * navigation continues the sends its predecessor started.
 */
@Suppress("TooManyFunctions") // One accessor per retained collection keeps the controller call sites unchanged.
internal class RetainedConversationStates(
    maxRetained: Int,
    isProtected: (String) -> Boolean,
    private val activeConversationKey: () -> String?,
    private val closeStagedUploadSource: (StagedUploadSource) -> Unit,
) {
    private val lock = Any()
    private val retention = ConversationStateRetention(maxRetained, isProtected)
    private val states = mutableMapOf<String, ConversationSendState>()

    // Transient view invalidation, bounded with the retained conversation state. Snapshot state so a
    // composition reading a revision outside the lock observes later cleanups.
    private val nativeComposerCleanupRevisions = mutableStateMapOf<String, Long>()

    // Never reuse a stamp after conversation eviction or account-cache cleanup.
    private var nativeComposerCleanupSequence = 0L

    fun optimisticMessages(
        accountRef: String?,
        groupIdHex: String,
    ): SnapshotStateMap<String, TimelineMessage> = retained(accountRef, groupIdHex) { optimisticMessages }

    fun durableAcceptanceCallbacks(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, () -> Unit> = retained(accountRef, groupIdHex) { durableAcceptanceCallbacks }

    fun projectedMessageIds(
        accountRef: String?,
        groupIdHex: String,
    ): MutableSet<String> = retained(accountRef, groupIdHex) { projectedMessageIds }

    fun timelineOrderOverrides(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, ULong> = retained(accountRef, groupIdHex) { timelineOrderOverrides }

    fun timelineTimestampOverrides(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, ULong> = retained(accountRef, groupIdHex) { timelineTimestampOverrides }

    fun optimisticSendPositionPreserves(
        accountRef: String?,
        groupIdHex: String,
    ): OptimisticSendPositionPreserves = retained(accountRef, groupIdHex) { optimisticSendPositionPreserves }

    fun retentionAtSend(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, ULong> = retained(accountRef, groupIdHex) { retentionAtSend }

    fun acceptedPendingTextOptimisticIds(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, String> = retained(accountRef, groupIdHex) { acceptedPendingTextOptimisticIds }

    fun optimisticSendPhases(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, OptimisticSendPhase> = retained(accountRef, groupIdHex) { optimisticSendPhases }

    fun optimisticCancellationGeneration(
        accountRef: String?,
        groupIdHex: String,
    ): MutableStateFlow<Long> = retained(accountRef, groupIdHex) { optimisticCancellationGeneration }

    /** Reads the accepted-text bridge without retaining or creating state for the conversation. */
    fun acceptedPendingTextOptimisticId(
        accountRef: String?,
        groupIdHex: String,
        confirmedMessageIdHex: String,
    ): String? =
        synchronized(lock) {
            states[key(accountRef, groupIdHex)]
                ?.acceptedPendingTextOptimisticIds
                ?.existing
                ?.get(confirmedMessageIdHex)
        }

    fun retainedMediaUploads(
        accountRef: String?,
        groupIdHex: String,
    ): ByteSizeLruCache<String, RetainedMediaUpload> = retained(accountRef, groupIdHex) { retainedMediaUploads }

    fun activeUploadKeys(
        accountRef: String?,
        groupIdHex: String,
    ): MutableSet<String> = retained(accountRef, groupIdHex) { activeUploadKeys }

    fun pendingProjectionsAwaitingBridge(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, TimelineMessageRecordFfi> {
        return retained(accountRef, groupIdHex) { pendingProjectionsAwaitingBridge }
    }

    /** Only committed cleanup of this exact owner may refresh its native-restored media shelf. */
    fun nativeComposerCleanupRevision(
        accountRef: String?,
        groupIdHex: String,
    ): Long = nativeComposerCleanupRevisions[key(accountRef, groupIdHex)] ?: 0L

    /** Stamps a committed native draft cleanup with a revision never used before in this process. */
    fun recordNativeComposerCleanup(
        accountRef: String?,
        groupIdHex: String,
    ) {
        synchronized(lock) {
            val key = retain(accountRef, groupIdHex)
            nativeComposerCleanupSequence += 1
            nativeComposerCleanupRevisions[key] = nativeComposerCleanupSequence
        }
    }

    /** Protects the conversation now on screen and moves its existing state to the recent end. */
    fun promote(
        accountRef: String?,
        groupIdHex: String,
    ) {
        synchronized(lock) {
            val key = key(accountRef, groupIdHex)
            retention
                .promote(key, protectedKey = key)
                .forEach(::remove)
        }
    }

    /** Evicts conversations whose active-upload protection has ended and that overflow the window. */
    fun prune() {
        synchronized(lock) {
            retention.prune(activeConversationKey()).forEach(::remove)
        }
    }

    /**
     * Account switch and sign-out: empty every retained collection, including the ones controllers
     * still hold, then drop all records. Retention order and the cleanup sequence are kept.
     */
    fun clearAll() {
        synchronized(lock) {
            states.values.forEach { it.wipe() }
            states.clear()
            nativeComposerCleanupRevisions.clear()
        }
    }

    private inline fun <T> retained(
        accountRef: String?,
        groupIdHex: String,
        field: ConversationSendState.() -> Lazy<T>,
    ): T =
        synchronized(lock) {
            val key = retain(accountRef, groupIdHex)
            states.getOrPut(key) { ConversationSendState(closeStagedUploadSource) }.field().value
        }

    private fun retain(
        accountRef: String?,
        groupIdHex: String,
    ): String {
        val key = key(accountRef, groupIdHex)
        retention
            .retain(key, protectedKey = activeConversationKey())
            .forEach(::remove)
        return key
    }

    /** Drops all cached conversation overlays and clears the retained queue to release its source owners. */
    private fun remove(staleKey: String) {
        nativeComposerCleanupRevisions.remove(staleKey)
        states.remove(staleKey)?.evict()
    }

    /** Each collection is created on first access, with the type and policy its callers expect. */
    private class ConversationSendState(
        closeStagedUploadSource: (StagedUploadSource) -> Unit,
    ) {
        val optimisticMessages = unguarded { mutableStateMapOf<String, TimelineMessage>() }

        // A failed optimistic send can be retried by a replacement controller after
        // navigation. Keep its one-shot acceptance cleanup beside the retained row,
        // under the same bounded conversation-state lifecycle.
        val durableAcceptanceCallbacks = unguarded { mutableMapOf<String, () -> Unit>() }
        val projectedMessageIds = unguarded { mutableSetOf<String>() }
        val timelineOrderOverrides = unguarded { mutableMapOf<String, ULong>() }
        val timelineTimestampOverrides = unguarded { mutableMapOf<String, ULong>() }
        val optimisticSendPositionPreserves = unguarded { OptimisticSendPositionPreserves() }
        val retentionAtSend = unguarded { mutableMapOf<String, ULong>() }

        // Canonical MDK app-event id -> local optimistic text id. MDK can accept a
        // text intent before publishing it, so the eventual projection needs this
        // exact bridge instead of matching identical pending texts heuristically.
        val acceptedPendingTextOptimisticIds = unguarded { mutableMapOf<String, String>() }

        // Stable client-key phase for each optimistic send. The controller keeps
        // this beside the retained optimistic row so a replacement conversation
        // can still distinguish cancellable pre-acceptance work from an intent
        // already owned by MDK.
        val optimisticSendPhases = unguarded { mutableMapOf<String, OptimisticSendPhase>() }
        val optimisticCancellationGeneration = unguarded { MutableStateFlow(0L) }

        // Retained-upload bytes survive screen disposal so a user who navigates
        // out of a chat mid-send and returns sees the pending bubble still carry
        // its preview/filename instead of an empty placeholder. Cap (and sizeOf
        // policy) match the controller-local version they replace.
        val retainedMediaUploads =
            unguarded {
                ByteSizeLruCache<String, RetainedMediaUpload>(
                    maxBytes = ConversationController.MEDIA_RETAINED_MAX_BYTES,
                    sizeOf = { upload -> upload.attachments.sumOf { it.plaintextBytes.size } },
                    onEntryRemoved = { upload -> upload.releaseSource(closeStagedUploadSource) },
                    // Evicting a send whose items all live in private files frees no heap, only its retry.
                    isEvictable = { upload -> upload.attachments.any { it.inMemoryBytes != null } },
                )
            }
        val activeUploadKeys = unguarded { mutableSetOf<String>() }
        val pendingProjectionsAwaitingBridge = unguarded { linkedMapOf<String, TimelineMessageRecordFfi>() }

        /**
         * Eviction releases one-shot acceptance cleanups and retained upload sources; a controller that
         * still holds the other collections keeps their contents.
         */
        fun evict() {
            durableAcceptanceCallbacks.existing?.clear()
            retainedMediaUploads.existing?.clear()
        }

        /**
         * The optimistic rows hold decrypted outgoing records and the override maps their ordering
         * keys, so the account wipe empties every collection a controller may still hold.
         */
        fun wipe() {
            retainedMediaUploads.existing?.clear()
            activeUploadKeys.existing?.clear()
            pendingProjectionsAwaitingBridge.existing?.clear()
            optimisticMessages.existing?.clear()
            durableAcceptanceCallbacks.existing?.clear()
            projectedMessageIds.existing?.clear()
            timelineOrderOverrides.existing?.clear()
            timelineTimestampOverrides.existing?.clear()
            optimisticSendPositionPreserves.existing?.clear()
            retentionAtSend.existing?.clear()
            acceptedPendingTextOptimisticIds.existing?.clear()
            optimisticSendPhases.existing?.clear()
        }
    }

    companion object {
        /** Retention and in-flight upload key; the upload registry matches on this exact prefix. */
        fun key(
            accountRef: String?,
            groupIdHex: String,
        ): String = "${accountRef.orEmpty()}\u0000$groupIdHex"

        /** Records are only touched under [lock], so their lazy collections need no lock of their own. */
        private fun <T> unguarded(create: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.NONE, create)

        private val <T> Lazy<T>.existing: T?
            get() = if (isInitialized()) value else null
    }
}
