package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoPlaybackCoordinationCoverageTest {
    @Test
    fun everyVideoPlayerOwnsAudioFocusAndPausesVoicePlayback() {
        val source = mediaVideoSource().readText()
        val body = source.functionBody("VideoViewerPage")

        assertTrue(
            "the shared video viewer must delegate audio focus to Media3",
            "setAudioAttributes(videoPlaybackAudioAttributes, true)" in body,
        )
        assertTrue(
            "the shared video viewer must stop voice-note audio before video playback",
            "VoicePlaybackController.pause()" in body,
        )
        assertFalse(
            "direct videos must not create a second player outside the shared viewer",
            "FullscreenVideoPlayer" in source,
        )
    }

    @Test
    fun pagerVideoOnlyPreparesWhileItIsCurrent() {
        val body = mediaVideoSource().readText().functionBody("VideoViewerPage")
        val beforePlaybackEffect = body.substringBefore("LaunchedEffect(isCurrent, exo)")
        val playbackEffect =
            body
                .substringAfter("LaunchedEffect(isCurrent, exo)")
                .substringBefore("androidx.compose.ui.viewinterop.AndroidView")

        assertFalse(
            "pre-composed neighbour pages must not eagerly prepare a decoder",
            "prepare()" in beforePlaybackEffect,
        )
        assertTrue("the current page must prepare its player", "if (isCurrent)" in playbackEffect && "exo.prepare()" in playbackEffect)
        assertTrue("an off-screen page must release its decoder", "exo.stop()" in playbackEffect)
    }

    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MediaVideo.kt")
}
