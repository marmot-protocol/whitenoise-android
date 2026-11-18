package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusedMessageTerminalActionsTest {
    @Test
    fun clickWaitsForTerminalCloseBeforeActivating() {
        val events = mutableListOf<String>()
        var afterExit: (() -> Unit)? = null
        val action = FocusedMessageAction("Reply", null, true, false, {}, { events += "activate" })
        val terminal = action.withTerminalCallbacks { afterExit = it }

        terminal.onClick()

        assertEquals(emptyList<String>(), events)
        afterExit!!()
        assertEquals(listOf("activate"), events)
        assertNull(terminal.onClickAt)
        assertEquals(action.label, terminal.label)
        assertEquals(action.enabled, terminal.enabled)
    }

    @Test
    fun locatedClickRetainsItsOriginUntilExitCompletes() {
        var afterExit: (() -> Unit)? = null
        var received: Rect? = null
        val origin = Rect(1f, 2f, 3f, 4f)
        val action =
            FocusedMessageAction("React", null, true, false, {}, {}, onClickAt = { received = it })
        val terminal = action.withTerminalCallbacks { afterExit = it }

        terminal.onClickAt!!(origin)

        assertNull(received)
        afterExit!!()
        assertEquals(origin, received)
    }
}
