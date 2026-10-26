package dev.ipf.whitenoise.android.notifications

import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.state.StalenessGuard
import java.util.UUID

internal enum class ConversationCardOp {
    SHOW_NOTIFY,
    SHOW_ENRICH,
    REFRESH_CONTACT_NAME,
    DISMISS_CANCEL,
    MARK_REPLY_HANDLED,
    MARK_REPLY_FAILED,
    CANCEL_IF_SAME_GENERATION,
}

internal enum class ConversationCardBarrier {
    AFTER_REGISTER,
    AFTER_READ,
    BEFORE_WRITE,
    AFTER_WRITE,
    BEFORE_PLATFORM_WRITE,
}

internal data class ConversationCardShowToken(
    val notificationTag: String,
    val notificationId: Int,
    val conversationScope: ConversationCardScope,
    val dismissalGeneration: Long,
    val showGeneration: Long,
    val notificationGeneration: NotificationCardGeneration,
)

/** Account and conversation identity used to invalidate opaque in-flight cards. */
internal data class ConversationCardScope(
    val accountRef: String,
    val groupIdHex: String,
)

@VisibleForTesting
internal interface ConversationCardTestHook {
    fun onAwaitingLock(
        op: ConversationCardOp,
        notificationTag: String,
        notificationId: Int,
    ) {}

    fun onLockAcquired(
        op: ConversationCardOp,
        notificationTag: String,
        notificationId: Int,
    ) {}

    fun onBarrier(
        op: ConversationCardOp,
        barrier: ConversationCardBarrier,
        notificationTag: String,
        notificationId: Int,
    ) {}

    fun onLockReleased(
        op: ConversationCardOp,
        notificationTag: String,
        notificationId: Int,
    ) {}
}

// Serializes read/modify/notify and compare/cancel on a single conversation
// message card (tag, id) across every LocalNotificationPresenter instance.
// Striped locks avoid unbounded per-conversation state.
internal object ConversationCardPostSynchronizer {
    private const val STRIPE_COUNT = 64
    private val stripes = Array(STRIPE_COUNT) { Any() }

    // This registry contains only currently preparing posts and their bounded
    // detached enrichment. Completion always removes the final registration,
    // so dismissal ordering adds no durable cache.
    private val inFlightShowsLock = Any()
    private val inFlightShows = mutableMapOf<ConversationCardKey, InFlightShowState>()

    @VisibleForTesting
    @Volatile
    var testHook: ConversationCardTestHook? = null

    /** Registers one card post under the current dismissal and newest-show lifetimes. */
    suspend fun <T> withRegisteredShow(
        notificationTag: String,
        notificationId: Int,
        conversationScope: ConversationCardScope,
        block: suspend (ConversationCardShowToken) -> T,
    ): T {
        val key = ConversationCardKey(notificationTag, notificationId)
        val token =
            synchronized(inFlightShowsLock) {
                val state = inFlightShows.getOrPut(key) { InFlightShowState() }
                state.activeShows += 1
                state.activeScopes[conversationScope] = state.activeScopes.getOrDefault(conversationScope, 0) + 1
                ConversationCardShowToken(
                    notificationTag = notificationTag,
                    notificationId = notificationId,
                    conversationScope = conversationScope,
                    dismissalGeneration = state.dismissals.capture(),
                    showGeneration = state.shows.advance(),
                    notificationGeneration = NotificationCardGenerations.register(),
                )
            }
        return try {
            block(token)
        } finally {
            releaseShow(token)
        }
    }

    /** Keeps a generation registered while its detached rich-card update runs. */
    fun retainShow(token: ConversationCardShowToken): Boolean =
        synchronized(inFlightShowsLock) {
            val state = inFlightShows[ConversationCardKey(token.notificationTag, token.notificationId)]
            if (
                state == null ||
                !state.dismissals.isCurrent(token.dismissalGeneration) ||
                !state.shows.isCurrent(token.showGeneration)
            ) {
                false
            } else if (!NotificationCardGenerations.retain(token.notificationGeneration)) {
                false
            } else {
                state.activeShows += 1
                state.activeScopes[token.conversationScope] =
                    state.activeScopes.getOrDefault(token.conversationScope, 0) + 1
                true
            }
        }

    /** Releases one registered post and retires its key after all detached work completes. */
    fun releaseShow(token: ConversationCardShowToken) {
        NotificationCardGenerations.release(token.notificationGeneration)
        val key = ConversationCardKey(token.notificationTag, token.notificationId)
        synchronized(inFlightShowsLock) {
            val state = inFlightShows[key] ?: return
            state.activeShows -= 1
            val remainingForScope = state.activeScopes.getOrDefault(token.conversationScope, 0) - 1
            if (remainingForScope > 0) {
                state.activeScopes[token.conversationScope] = remainingForScope
            } else {
                state.activeScopes.remove(token.conversationScope)
            }
            if (state.activeShows == 0) inFlightShows.remove(key)
        }
    }

    /** Checks both dismissal and newest-show ownership for rich-card publication. */
    fun isShowCurrent(token: ConversationCardShowToken): Boolean =
        synchronized(inFlightShowsLock) {
            inFlightShows[ConversationCardKey(token.notificationTag, token.notificationId)]
                ?.let { state ->
                    state.dismissals.isCurrent(token.dismissalGeneration) &&
                        state.shows.isCurrent(token.showGeneration) &&
                        !token.notificationGeneration.dismissed.get()
                } == true
        }

    /** Initial posts preserve every message; only a dismissal may invalidate them. */
    fun isShowNotDismissed(token: ConversationCardShowToken): Boolean =
        synchronized(inFlightShowsLock) {
            inFlightShows[ConversationCardKey(token.notificationTag, token.notificationId)]
                ?.dismissals
                ?.isCurrent(token.dismissalGeneration) == true &&
                !token.notificationGeneration.dismissed.get()
        }

    /** Invalidates every registered post that predates a conversation-card dismissal. */
    fun markDismissed(
        notificationTag: String,
        notificationId: Int,
    ) {
        synchronized(inFlightShowsLock) {
            inFlightShows[ConversationCardKey(notificationTag, notificationId)]
                ?.dismissals
                ?.advance()
        }
    }

    /** Invalidates every registered card for one account/conversation, including opaque invite keys. */
    fun markConversationDismissed(scope: ConversationCardScope) {
        synchronized(inFlightShowsLock) {
            inFlightShows.values.forEach { state ->
                if (state.activeScopes.containsKey(scope)) state.dismissals.advance()
            }
        }
    }

    /** Serializes one conversation-card mutation on its deterministic key stripe. */
    inline fun <T> withLock(
        notificationTag: String,
        notificationId: Int,
        op: ConversationCardOp,
        block: () -> T,
    ): T {
        testHook?.onAwaitingLock(op, notificationTag, notificationId)
        return synchronized(stripeFor(notificationTag, notificationId)) {
            testHook?.onLockAcquired(op, notificationTag, notificationId)
            try {
                block()
            } finally {
                testHook?.onLockReleased(op, notificationTag, notificationId)
            }
        }
    }

    fun awaitTestBarrier(
        op: ConversationCardOp,
        barrier: ConversationCardBarrier,
        notificationTag: String,
        notificationId: Int,
    ) {
        testHook?.onBarrier(op, barrier, notificationTag, notificationId)
    }

    /** Maps one notification card to its stable mutation-serialization stripe. */
    private fun stripeFor(
        tag: String,
        id: Int,
    ): Any {
        var hash = tag.hashCode()
        hash = 31 * hash + id
        return stripes[(hash and Int.MAX_VALUE) % STRIPE_COUNT]
    }

    private data class ConversationCardKey(
        val tag: String,
        val id: Int,
    )

    private data class InFlightShowState(
        var activeShows: Int = 0,
        val activeScopes: MutableMap<ConversationCardScope, Int> = mutableMapOf(),
        val dismissals: StalenessGuard = StalenessGuard(),
        val shows: StalenessGuard = StalenessGuard(),
    )
}

/** Bounded record of app writes that may not be visible in the platform tray snapshot yet. */
internal object ConversationCardPostedRegistry {
    private const val CAPACITY = 256
    private val lock = Any()
    private val postedCards = linkedMapOf<PostedConversationCardKey, PostedCard>()
    private var untrackedPreviousProcessAllowed = true
    private val processRevisionPrefix = UUID.randomUUID().toString() + ":"

    /** Starts an independent test process; platform-only fixture cleanup must not retain retirement history. */
    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) {
            postedCards.clear()
            untrackedPreviousProcessAllowed = true
        }
    }

    /** Opaque identity for one accepted Android payload, independent of dismissal generation and age. */
    fun newPayloadRevision(): String = processRevisionPrefix + UUID.randomUUID()

    /** A copied payload may rewrite only the exact accepted revision, including after a silent cleanup. */
    fun isCurrentPayload(
        tag: String,
        id: Int,
        revision: String?,
    ): Boolean =
        synchronized(lock) {
            val snapshot = postedCards[PostedConversationCardKey(tag, id)]?.payload
            if (snapshot != null) {
                snapshot.revision == revision
            } else {
                untrackedPreviousProcessAllowed && revision?.startsWith(processRevisionPrefix) != true
            }
        }

    /**
     * Stale history can contribute only identities retained in the last accepted payload. This is Android write
     * metadata, not an unread/deletion authority or a second content store. Missing/evicted current-process
     * revisions fail closed. Untouched prior-process cards remain eligible until capacity eviction makes their
     * retirement history unverifiable; after that, untracked history is conservatively dropped for this process.
     */
    fun canCarryEntry(
        tag: String,
        id: Int,
        revision: String?,
        entryId: String?,
    ): Boolean =
        synchronized(lock) {
            val snapshot = postedCards[PostedConversationCardKey(tag, id)]?.payload
            when {
                snapshot == null ->
                    untrackedPreviousProcessAllowed && revision?.startsWith(processRevisionPrefix) != true
                snapshot.revision == revision -> true
                else -> entryId != null && entryId in snapshot.entryIds
            }
        }

    /** Called under the platform mutation lock before accepting a new write; pending cancellation owns its slot. */
    fun canAcceptPost(
        tag: String,
        id: Int,
    ): Boolean =
        synchronized(lock) {
            PostedConversationCardKey(tag, id) in postedCards ||
                postedCards.size < CAPACITY ||
                postedCards.values.any { !it.cancellationPending }
        }

    /** Records an app-side write at its dismissal age so an immediate cleanup can still find it. */
    fun markPosted(
        notificationTag: String,
        notificationId: Int,
        recordedAtMs: Long = System.currentTimeMillis(),
        generationId: String? = null,
        payload: PayloadSnapshot? = null,
    ) {
        val key = PostedConversationCardKey(notificationTag, notificationId)
        synchronized(lock) {
            check(canAcceptPost(notificationTag, notificationId)) { "Notification cancellation capacity exhausted" }
            postedCards.remove(key)
            postedCards[key] =
                PostedCard(
                    recordedAtMs,
                    generationId,
                    payload?.copy(entryIds = payload.entryIds.toSet()),
                )
            trimRecords()
        }
    }

    /** Consumes a posted write but retains bounded retirement metadata; newer dismissal ages stay untouched. */
    fun clearPosted(
        notificationTag: String,
        notificationId: Int,
        cutoffMs: Long? = null,
    ): Boolean {
        val key = PostedConversationCardKey(notificationTag, notificationId)
        return synchronized(lock) {
            val record = postedCards[key]?.takeIf { it.posted } ?: return@synchronized false
            if (cutoffMs == null || record.writtenAtMs <= cutoffMs) {
                postedCards[key] =
                    record.copy(
                        posted = false,
                        cancellationPending = false,
                        payload = PayloadSnapshot(newPayloadRevision(), emptySet()),
                    )
                true
            } else {
                false
            }
        }
    }

    /**
     * Retires copied content before attempting cancellation without consuming the obligation to cancel. A visible
     * legacy card also gets a pending record, so a failed cancel remains retryable after Android omits it.
     */
    fun prepareCancellation(
        tag: String,
        id: Int,
        platformHasCard: Boolean,
    ): Boolean =
        synchronized(lock) {
            val key = PostedConversationCardKey(tag, id)
            val record = postedCards[key]
            if (record?.posted != true && !platformHasCard) return@synchronized false
            check(canAcceptPost(tag, id)) { "Notification cancellation capacity exhausted" }
            postedCards[key] =
                (record ?: PostedCard(System.currentTimeMillis(), null, null)).copy(
                    posted = true,
                    cancellationPending = true,
                    payload = PayloadSnapshot(newPayloadRevision(), emptySet()),
                )
            trimRecords()
            true
        }

    /** Evicting a retirement record cannot make its unknown old-process payload eligible again. */
    private fun trimRecords() {
        while (postedCards.size > CAPACITY) {
            val oldest =
                postedCards.entries.firstOrNull { !it.value.posted && !it.value.cancellationPending }?.key
                    ?: postedCards.entries.first { !it.value.cancellationPending }.key
            postedCards.remove(oldest)
            untrackedPreviousProcessAllowed = false
        }
    }

    /** Detects an accepted app write newer than the asynchronously visible tray snapshot. */
    fun latestGeneration(
        tag: String,
        id: Int,
    ): String? =
        synchronized(lock) {
            postedCards[PostedConversationCardKey(tag, id)]?.takeIf { it.posted }?.generationId
        }

    private data class PostedCard(
        val writtenAtMs: Long,
        val generationId: String?,
        val payload: PayloadSnapshot?,
        val posted: Boolean = true,
        val cancellationPending: Boolean = false,
    )

    /** Metadata describing the accepted Android payload; not a second source of message state. */
    data class PayloadSnapshot(
        val revision: String,
        val entryIds: Set<String>,
    )

    private data class PostedConversationCardKey(
        val tag: String,
        val id: Int,
    )
}
