package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Presentation-only scheduling; native rows and published lists remain owned by ChatsController. */
internal class ChatListPresentationLifetime(
    private val scope: CoroutineScope,
    private val publish: () -> Unit,
) {
    private var visible = true
    private var pending = false
    private var scheduled: Job? = null
    private var cleared = false

    /** Coalesces folded updates without taking ownership of the controller's other scope jobs. */
    fun schedule() {
        if (cleared || scheduled != null) return
        scheduled =
            scope.launch {
                delay(RECOMPUTE_DEBOUNCE_MS)
                scheduled = null
                publish()
            }
    }

    /** Returning must publish synchronously before navigation compares its head snapshot. */
    fun setVisible(value: Boolean) {
        if (cleared || visible == value) return
        visible = value
        if (visible && (pending || scheduled != null)) publish()
    }

    /** Called after account unread publication, which must continue while the list is hidden. */
    fun beginVisiblePublication(): Boolean {
        if (cleared) return false
        scheduled?.cancel()
        scheduled = null
        pending = !visible
        return visible
    }

    /** Retires queued work from an old binding while preserving whether the screen is visible. */
    fun reset() {
        scheduled?.cancel()
        scheduled = null
        pending = false
    }

    /** Cancels only this lifetime's job; the controller separately disposes its shared scope. */
    fun clear() {
        cleared = true
        reset()
    }

    private companion object {
        const val RECOMPUTE_DEBOUNCE_MS = 16L
    }
}
