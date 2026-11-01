package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps every attachment route on the same focus, foreground and page-ownership contract. */
class VideoPlaybackCoordinationCoverageTest {
    /** Media3 owns focus and headphones; video admission pauses the existing voice session. */
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
        assertTrue(
            "headphone disconnection must use Media3 noisy-route handling",
            "setHandleAudioBecomingNoisy(true)" in body,
        )
        assertFalse(
            "direct videos must not create a second player outside the shared viewer",
            "FullscreenVideoPlayer" in source,
        )
    }

    /** Neighbour pages cannot consume a decoder or play, including after a background materialization. */
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
        assertTrue(
            "the current page must prepare its player",
            "if (isCurrent)" in playbackEffect && "exo.prepare()" in playbackEffect,
        )
        assertTrue("an off-screen page must release its decoder", "exo.stop()" in playbackEffect)
        assertTrue(
            "late materialization must not auto-play behind Home or the lock screen",
            "autoplayAllowed.value && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)" in playbackEffect,
        )
    }

    /** STOP pauses the retained player, while foreground return has no automatic resume path. */
    @Test
    fun mountedPlayerPausesOnStopAndDetachesItsObserver() {
        val body = mediaVideoSource().readText().functionBody("VideoViewerPage")
        val observer =
            body
                .substringAfter("DisposableEffect(exo, lifecycle, autoplayAllowed)")
                .substringBefore("HoldCacheFileWhileComposed")
        assertTrue("the host lifecycle must own the observer", "LocalLifecycleOwner.current.lifecycle" in body)
        val pendingFile = body.substringBefore("if (file == null)")
        assertTrue(
            "a pending file must remember STOP before the unavailable-page return",
            "if (event == Lifecycle.Event.ON_STOP) autoplayAllowed.value = false" in pendingFile,
        )
        assertTrue("STOP must pause the exact player", "if (event == Lifecycle.Event.ON_STOP) exo.pause()" in observer)
        assertTrue("observer must be registered", "lifecycle.addObserver(observer)" in observer)
        assertTrue(
            "observer must be removed on disposal",
            "lifecycle.removeObserver(observer)" in observer.substringAfter("onDispose"),
        )
        assertTrue(
            "replacement must retain the latest play intent",
            "autoplayAllowed.value = playWhenReady" in observer,
        )
        assertTrue("pause listener must be attached", "exo.addListener(pauseListener)" in observer)
        assertTrue(
            "pause listener must be detached",
            "exo.removeListener(pauseListener)" in observer.substringAfter("onDispose"),
        )
        assertTrue(
            "the retained view must follow the replacement player",
            "playerView.player = exo" in body.substringAfter("update ="),
        )
        assertFalse(
            "returning to foreground must not play",
            "exo.play(" in observer || "playWhenReady = true" in observer,
        )
    }

    /** Resolves the production source from module and repository test working directories. */
    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MediaVideo.kt")
}
