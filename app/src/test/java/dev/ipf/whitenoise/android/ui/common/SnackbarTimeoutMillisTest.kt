package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.ui.platform.AccessibilityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root snackbar clock copies the material host's timing, which material keeps private: 4 s for Short, 10 s
 * for Long, never for Indefinite, each stretched to the accessibility manager's recommendation for text with icons
 * and, when the snackbar has an action, controls (#3312). These cases pin that copy.
 */
class SnackbarTimeoutMillisTest {
    /** A short snackbar lasts the material four seconds without an accessibility manager. */
    @Test
    fun shortLastsFourSeconds() {
        assertEquals(4_000L, snackbarTimeoutMillis(visuals(SnackbarDuration.Short), null))
    }

    /** A long snackbar lasts the material ten seconds without an accessibility manager. */
    @Test
    fun longLastsTenSeconds() {
        assertEquals(10_000L, snackbarTimeoutMillis(visuals(SnackbarDuration.Long), null))
    }

    /** An indefinite snackbar never times out, and the accessibility manager is not even consulted. */
    @Test
    fun indefiniteNeverTimesOut() {
        val manager = RecordingAccessibilityManager(scale = 3)
        assertEquals(Long.MAX_VALUE, snackbarTimeoutMillis(visuals(SnackbarDuration.Indefinite), manager))
        assertNull("no recommendation is asked for a snackbar that never ends", manager.lastControls)
    }

    /** The accessibility manager's recommendation stretches the fixed duration. */
    @Test
    fun accessibilityScalingStretchesTheDuration() {
        val manager = RecordingAccessibilityManager(scale = 2)
        assertEquals(8_000L, snackbarTimeoutMillis(visuals(SnackbarDuration.Short), manager))
        assertEquals(20_000L, snackbarTimeoutMillis(visuals(SnackbarDuration.Long), manager))
    }

    /** A snackbar with an action is reported as containing controls, one without is not, both as text with icons. */
    @Test
    fun anActionIsReportedAsControls() {
        val manager = RecordingAccessibilityManager(scale = 1)
        snackbarTimeoutMillis(visuals(SnackbarDuration.Short, actionLabel = "Undo"), manager)
        assertEquals(true, manager.lastControls)
        assertTrue(manager.lastIcons == true && manager.lastText == true)

        snackbarTimeoutMillis(visuals(SnackbarDuration.Short), manager)
        assertFalse(manager.lastControls == true)
    }

    /** The plainest visuals material accepts, with the duration and action under test. */
    private fun visuals(
        duration: SnackbarDuration,
        actionLabel: String? = null,
    ): SnackbarVisuals =
        object : SnackbarVisuals {
            override val message: String = "Profile saved"
            override val actionLabel: String? = actionLabel
            override val withDismissAction: Boolean = false
            override val duration: SnackbarDuration = duration
        }

    /** Multiplies every recommendation by [scale] and records what the caller said the snackbar contains. */
    private class RecordingAccessibilityManager(
        private val scale: Long,
    ) : AccessibilityManager {
        var lastIcons: Boolean? = null
        var lastText: Boolean? = null
        var lastControls: Boolean? = null

        /** Returns the scaled timeout and remembers the content flags it was given. */
        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ): Long {
            lastIcons = containsIcons
            lastText = containsText
            lastControls = containsControls
            return originalTimeoutMillis * scale
        }
    }
}
