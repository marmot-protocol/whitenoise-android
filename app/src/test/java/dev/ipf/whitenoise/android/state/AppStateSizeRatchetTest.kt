package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [WhiteNoiseAppState] from growing back. New feature state belongs in a focused controller or
 * a `WhiteNoiseAppState` extension file in this package. Lower [MAX_LINES] when a change shrinks the file.
 */
class AppStateSizeRatchetTest {
    @Test
    fun appStateDoesNotGrow() {
        val lines = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt").lines().size
        assertTrue(
            "AppState.kt has $lines lines (limit $MAX_LINES). Move new work into a controller or " +
                "an AppState extension file instead of growing the class.",
            lines <= MAX_LINES,
        )
    }

    private companion object {
        const val MAX_LINES = 12_176
    }
}
