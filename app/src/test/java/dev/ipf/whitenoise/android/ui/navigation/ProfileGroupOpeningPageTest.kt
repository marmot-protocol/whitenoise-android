package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileGroupOpeningPageTest {
    private val host = Rect(0f, 24f, 400f, 800f)

    @Test
    fun onlyFullyVisibleFiniteControlCanLaunch() {
        assertTrue(validProfileGroupOrigin(Rect(10f, 40f, 390f, 90f), host))
        assertFalse(validProfileGroupOrigin(null, host))
        assertFalse(validProfileGroupOrigin(Rect.Zero, host))
        assertFalse(validProfileGroupOrigin(Rect(10f, 0f, 390f, 60f), host))
        assertFalse(validProfileGroupOrigin(Rect(10f, 801f, 390f, 850f), host))
        assertFalse(validProfileGroupOrigin(Rect(10f, 40f, Float.POSITIVE_INFINITY, 90f), host))
        assertFalse(validProfileGroupOrigin(Rect(10f, 40f, 390f, 90f), Rect.Zero))
    }
}
