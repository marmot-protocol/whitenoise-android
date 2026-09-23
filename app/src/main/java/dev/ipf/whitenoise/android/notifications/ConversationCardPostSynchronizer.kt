package dev.ipf.whitenoise.android.notifications

import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.state.StalenessGuard

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
}

internal data class ConversationCardShowToken(
    val notificationTag: String,
    val notificationId: Int,
    val conversationScope: ConversationCardScope,
    val dismissalGeneration: Long,
    val showGeneration: Long,
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
            } else {
                state.activeShows += 1
                state.activeScopes[token.conversationScope] =
                    state.activeScopes.getOrDefault(token.conversationScope, 0) + 1
                true
            }
        }

    /** Releases one registered post and retires its key after all detached work completes. */
    fun releaseShow(token: ConversationCardShowToken) {
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
                        state.shows.isCurrent(token.showGeneration)
                } == true
        }

    /** Initial posts preserve every message; only a dismissal may invalidate them. */
    fun isShowNotDismissed(token: ConversationCardShowToken): Boolean =
        synchronized(inFlightShowsLock) {
            inFlightShows[ConversationCardKey(token.notificationTag, token.notificationId)]
                ?.dismissals
                ?.isCurrent(token.dismissalGeneration) == true
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
    private val postedCards = linkedMapOf<PostedConversationCardKey, Long>()

    /** Records an app-side write so an immediate dismissal can still find it. */
    fun markPosted(
        notificationTag: String,
        notificationId: Int,
    ) {
        val key = PostedConversationCardKey(notificationTag, notificationId)
        synchronized(lock) {
            postedCards.remove(key)
            postedCards[key] = System.currentTimeMillis()
            while (postedCards.size > CAPACITY) postedCards.remove(postedCards.keys.first())
        }
    }

    /** Forgets a write and optionally preserves records newer than [cutoffMs]. */
    fun clearPosted(
        notificationTag: String,
        notificationId: Int,
        cutoffMs: Long? = null,
    ): Boolean {
        val key = PostedConversationCardKey(notificationTag, notificationId)
        return synchronized(lock) {
            val writtenAtMs = postedCards[key] ?: return@synchronized false
            if (cutoffMs == null || writtenAtMs <= cutoffMs) {
                postedCards.remove(key)
                true
            } else {
                false
            }
        }
    }

    private data class PostedConversationCardKey(
        val tag: String,
        val id: Int,
    )
}
