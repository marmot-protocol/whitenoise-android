package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeavingImeInsetsTest {
    @Test
    fun closingKeyboardKeepsRouteLaidOutForClosedKeyboard() {
        assertFalse(imeLeavingSettled(previousBottom = Int.MAX_VALUE, bottom = 800))
        assertFalse(imeLeavingSettled(previousBottom = 800, bottom = 400))
    }

    @Test
    fun closedKeyboardSettlesTheRoute() {
        assertTrue(imeLeavingSettled(previousBottom = 400, bottom = 0))
    }

    @Test
    fun keyboardOpenedOnTheNewRouteResumesNormalInsets() {
        assertTrue(imeLeavingSettled(previousBottom = 400, bottom = 450))
    }
}
