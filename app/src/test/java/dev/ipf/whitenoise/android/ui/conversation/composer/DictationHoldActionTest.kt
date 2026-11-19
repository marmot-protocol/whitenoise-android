package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationHoldActionTest {
    private fun action(
        deltaX: Float,
        deltaY: Float,
        released: Boolean,
    ) = dictationHoldAction(deltaX, deltaY, released, lockPx = LOCK, cancelPx = CANCEL)

    @Test
    fun `holding still keeps listening and releasing sends`() {
        assertEquals(DictationHoldAction.Continue, action(0f, 0f, released = false))
        assertEquals(DictationHoldAction.Send, action(0f, 0f, released = true))
    }

    @Test
    fun `sliding up past the lock distance locks without sending`() {
        assertEquals(DictationHoldAction.Continue, action(0f, -LOCK, released = false))
        assertEquals(DictationHoldAction.Lock, action(0f, -LOCK - 1f, released = false))
        assertEquals(DictationHoldAction.Lock, action(0f, -LOCK - 1f, released = true))
    }

    @Test
    fun `releasing after sliding left past the cancel distance cancels`() {
        assertEquals(DictationHoldAction.Continue, action(-CANCEL - 1f, 0f, released = false))
        assertEquals(DictationHoldAction.Cancel, action(-CANCEL - 1f, 0f, released = true))
        assertEquals(DictationHoldAction.Send, action(-CANCEL, 0f, released = true))
    }

    @Test
    fun `an armed cancel blocks the lock until the finger returns`() {
        assertEquals(DictationHoldAction.Cancel, action(-CANCEL - 1f, -LOCK - 1f, released = true))
        assertEquals(DictationHoldAction.Lock, action(-CANCEL + 1f, -LOCK - 1f, released = false))
    }

    @Test
    fun `sliding right or down never cancels or locks`() {
        assertEquals(DictationHoldAction.Send, action(CANCEL * 2, LOCK * 2, released = true))
    }

    private companion object {
        const val LOCK = 80f
        const val CANCEL = 120f
    }
}
