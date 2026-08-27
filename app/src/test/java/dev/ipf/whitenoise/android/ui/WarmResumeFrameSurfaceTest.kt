package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WarmResumeFrameSurfaceTest {
    @Test
    fun renderedSurfaceRecorderUsesTheDrawPhaseWithoutACancellableFrameWait() {
        val body = whiteNoiseAppSource().readText().functionBody("WarmResumeFrameSurface")

        assertTrue(
            "surface evidence must be tied to an actual Android draw",
            "ViewTreeObserver.OnDrawListener" in body,
        )
        assertTrue(
            "the draw callback must publish the rendered surface identity",
            "WarmResumeTrace.renderedSurfaceFrame" in body,
        )
        assertFalse(
            "a keyed frame wait can be cancelled before recording a drawn surface",
            "withFrameNanos" in body,
        )
    }

    private fun whiteNoiseAppSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
        ).firstOrNull(File::exists) ?: error("Missing WhiteNoiseApp.kt source file")
}
