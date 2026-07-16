package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Media3PlayerViewReleaseCoverageTest {
    @Test
    fun everyVideoPlayerViewDetachesItsPlayerOnRelease() {
        val source = mediaVideoSource().readText()
        val androidViewCount = Regex("AndroidView\\(").findAll(source).count()
        val detachCount =
            Regex("onRelease\\s*=\\s*\\{\\s*playerView\\s*->\\s*playerView\\.player\\s*=\\s*null\\s*}")
                .findAll(source)
                .count()

        assertEquals("test assumes MediaVideo owns exactly two PlayerViews", 2, androidViewCount)
        assertEquals("every PlayerView must drop its ExoPlayer reference", androidViewCount, detachCount)
        assertTrue("ExoPlayer instances must still be released", source.contains("onDispose { exo.release() }"))
    }

    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MediaVideo.kt")
}
