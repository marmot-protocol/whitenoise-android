package dev.ipf.whitenoise.android.ui.keyboard

import android.view.Window
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shortcut window callback must forward every Java default method that Kotlin delegation skips (#4005). */
class HardwareShortcutWindowCallbackTest {
    /** Every default method of [Window.Callback] has an explicit override, so none silently loses the activity's. */
    @Test
    fun everyDefaultCallbackMethodIsForwarded() {
        val declared =
            HardwareShortcutWindowCallback::class.java.declaredMethods
                .map { it.name to it.parameterTypes.toList() }
                .toSet()
        val missing =
            Window.Callback::class.java.methods
                .filter { it.isDefault }
                .filterNot { (it.name to it.parameterTypes.toList()) in declared }
                .map { it.name }

        assertEquals(emptyList<String>(), missing)
    }
}
