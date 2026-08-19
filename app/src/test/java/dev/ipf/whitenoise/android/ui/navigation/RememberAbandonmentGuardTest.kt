package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RememberAbandonmentGuardTest {
    @Test
    fun abandonedCompositionClearsTheGuardedValue() {
        val cleaned = mutableListOf<String>()
        val guard = RememberAbandonmentGuard("controller") { cleaned += it }
        guard.onAbandoned()
        assertEquals(listOf("controller"), cleaned)
    }

    @Test
    fun committedLifecycleNeverTriggersTheAbandonmentCleanup() {
        val cleaned = mutableListOf<String>()
        val guard = RememberAbandonmentGuard("controller") { cleaned += it }
        guard.onRemembered()
        guard.onForgotten()
        assertEquals(emptyList<String>(), cleaned)
    }
}
