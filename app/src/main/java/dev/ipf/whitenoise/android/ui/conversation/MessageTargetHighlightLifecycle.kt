package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

internal const val MESSAGE_TARGET_HIGHLIGHT_DWELL_MILLIS = 900L
internal const val TRANSIENT_MESSAGE_HIGHLIGHT_DWELL_MILLIS = 1_500L

/**
 * Monotonic owner shared by reply, mention, and search navigation. Cancelling a
 * coroutine is still best-effort when a paging boundary swallows cancellation;
 * this token gives every operation an explicit stale check before it pages or
 * starts a scroll.
 */
internal class MessageTargetNavigationOwner(
    private val onOwnershipChanged: () -> Unit = {},
) {
    private var owner = 0L

    /** Starts a navigation that supersedes every earlier one; [onOwnershipChanged] runs first. */
    fun begin(): Request {
        onOwnershipChanged()
        val requestOwner = ++owner
        return Request { owner == requestOwner }
    }

    /** Ends the current navigation without starting another, as a reader scroll does. */
    fun cancel() {
        onOwnershipChanged()
        owner += 1L
    }

    internal class Request(
        private val current: () -> Boolean,
    ) {
        fun isCurrent(): Boolean = current()
    }
}

/**
 * Latest-wins owner for a message target cue. The same owner spans target
 * loading, programmatic scrolling, the post-settle dwell, and cancellation so
 * an older navigation's cleanup can never clear a newer target.
 */
internal class MessageTargetHighlightLifecycle(
    private val dwellMillis: Long = MESSAGE_TARGET_HIGHLIGHT_DWELL_MILLIS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    var highlightedMessageId by mutableStateOf<String?>(null)
        private set

    private var owner = 0L

    suspend fun highlightWhile(
        messageId: String,
        postSettleDwellMillis: Long = dwellMillis,
        operation: suspend () -> Boolean,
    ): Boolean {
        val requestOwner = ++owner
        highlightedMessageId = messageId
        return try {
            val completed = operation()
            if (completed && owner == requestOwner) wait(postSettleDwellMillis)
            completed
        } finally {
            if (owner == requestOwner) {
                owner += 1L
                highlightedMessageId = null
            }
        }
    }

    fun clear() {
        owner += 1L
        highlightedMessageId = null
    }
}
