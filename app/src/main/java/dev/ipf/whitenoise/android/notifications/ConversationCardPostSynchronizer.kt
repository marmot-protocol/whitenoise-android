package dev.ipf.whitenoise.android.notifications

import androidx.annotation.VisibleForTesting

internal enum class ConversationCardOp {
    SHOW_NOTIFY,
    DISMISS_CANCEL,
    MARK_REPLY_HANDLED,
    MARK_REPLY_FAILED,
    CANCEL_IF_SAME_GENERATION,
}

internal enum class ConversationCardBarrier {
    AFTER_REGISTER,
    AFTER_READ,
    BEFORE_WRITE,
}

internal data class ConversationCardShowToken(
    val notificationTag: String,
    val notificationId: Int,
    val dismissalGeneration: Long,
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

    // This registry contains only currently preparing posts. Completion always
    // removes the final registration, so dismissal ordering adds no durable cache.
    private val inFlightShowsLock = Any()
    private val inFlightShows = mutableMapOf<ConversationCardKey, InFlightShowState>()

    @VisibleForTesting
    @Volatile
    var testHook: ConversationCardTestHook? = null

    suspend fun <T> withRegisteredShow(
        notificationTag: String,
        notificationId: Int,
        block: suspend (ConversationCardShowToken) -> T,
    ): T {
        val key = ConversationCardKey(notificationTag, notificationId)
        val token =
            synchronized(inFlightShowsLock) {
                val state = inFlightShows.getOrPut(key) { InFlightShowState() }
                state.activeShows += 1
                ConversationCardShowToken(notificationTag, notificationId, state.dismissalGeneration)
            }
        return try {
            block(token)
        } finally {
            synchronized(inFlightShowsLock) {
                val state = inFlightShows[key] ?: return@synchronized
                state.activeShows -= 1
                if (state.activeShows == 0) inFlightShows.remove(key)
            }
        }
    }

    fun isShowCurrent(token: ConversationCardShowToken): Boolean =
        synchronized(inFlightShowsLock) {
            inFlightShows[ConversationCardKey(token.notificationTag, token.notificationId)]
                ?.dismissalGeneration == token.dismissalGeneration
        }

    fun markDismissed(
        notificationTag: String,
        notificationId: Int,
    ) {
        synchronized(inFlightShowsLock) {
            inFlightShows[ConversationCardKey(notificationTag, notificationId)]
                ?.let { state -> state.dismissalGeneration += 1 }
        }
    }

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
        var dismissalGeneration: Long = 0,
    )
}
