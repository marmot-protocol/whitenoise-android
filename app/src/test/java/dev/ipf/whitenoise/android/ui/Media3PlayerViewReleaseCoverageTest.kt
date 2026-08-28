package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Media3PlayerViewReleaseCoverageTest {
    @Test
    fun everyVideoPlayerViewDetachesItsPlayerOnRelease() {
        val source = mediaVideoSource().readText()
        val functionName = "VideoViewerPage"
        val body = source.functionBody(functionName)

        assertEquals("$functionName must own exactly one PlayerView", 1, Regex("AndroidView\\(").findAll(body).count())
        assertTrue(
            "$functionName must detach its exact PlayerView on release",
            Regex("onRelease\\s*=\\s*\\{\\s*playerView\\s*->\\s*playerView\\.player\\s*=\\s*null\\s*}")
                .containsMatchIn(body),
        )
        assertTrue("$functionName must release its ExoPlayer", "onDispose { exo.release() }" in body)
        assertEquals(
            "all video routes must share one ExoPlayer implementation",
            1,
            Regex("androidx\\.media3\\.exoplayer\\.ExoPlayer").findAll(source).count(),
        )
    }

    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MediaVideo.kt")
}
