package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Media3PlayerViewReleaseCoverageTest {
    /** Every video view detaches its player, and disposal clears the published handle before release. */
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
        assertTrue(
            "$functionName must clear its published player and release that ExoPlayer on disposal",
            Regex("""onDispose\s*\{\s*latestOnPlayerChanged\(null\)\s*exo\.release\(\)\s*}""")
                .containsMatchIn(body),
        )
        assertEquals(
            "all video routes must share one ExoPlayer implementation",
            1,
            Regex("""androidx\.media3\.exoplayer\.ExoPlayer\s*\.Builder\(""").findAll(source).count(),
        )
    }

    /** Resolves the production viewer source from repository-root and app-module test working directories. */
    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MediaVideo.kt")
}
