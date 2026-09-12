package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One account/runtime presentation owns unique openings, including the pointer that opened each menu. */
internal class ChatContextMenuOwner {
    var token: Any? by mutableStateOf(null)
        private set
    var pointerHeld by mutableStateOf(false)
        private set
    private var disposed = false
    private var activeOpening = false
    val isActive: Boolean get() = !disposed

    /** Every opening receives a fresh identity; a disposed route cannot start another one. */
    fun open(held: Boolean): Any? {
        if (disposed) return null
        val next = Any()
        activeOpening = true
        pointerHeld = held
        token = next
        return next
    }

    /** Identity comparison rejects a closed opening and every older opening, even for the same row. */
    fun isCurrent(candidate: Any?): Boolean = activeOpening && isLatest(candidate)

    /** A range gesture may outlive its closed menu, but cannot outlive a replacement opening or route. */
    fun isLatest(candidate: Any?): Boolean = !disposed && candidate != null && token === candidate

    /** Only the pointer belonging to this opening can hand focus to its popup. */
    fun release(candidate: Any?) {
        if (isCurrent(candidate)) pointerHeld = false
    }

    /** Retain the display key for exit animation while immediately revoking command and dismissal ownership. */
    fun dismiss(candidate: Any?) {
        if (isCurrent(candidate)) {
            activeOpening = false
            pointerHeld = false
        }
    }

    /** Teardown permanently revokes every captured callback, including after account/route recreation. */
    fun dispose() {
        disposed = true
        activeOpening = false
        pointerHeld = false
        token = null
    }
}
