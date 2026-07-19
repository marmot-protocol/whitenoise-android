package dev.ipf.whitenoise.android.notifications

import androidx.annotation.VisibleForTesting

internal enum class ConversationCardOp {
    SHOW_NOTIFY,
    DISMISS_CANCEL,
    MARK_REPLY_HANDLED,
    CANCEL_IF_SAME_GENERATION,
}

internal enum class ConversationCardBarrier {
    AFTER_READ,
    BEFORE_WRITE,
}

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

    @VisibleForTesting
    @Volatile
    var testHook: ConversationCardTestHook? = null

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
}
