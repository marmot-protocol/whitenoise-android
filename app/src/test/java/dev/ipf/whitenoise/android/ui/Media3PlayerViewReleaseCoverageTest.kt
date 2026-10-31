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
            "$functionName must clear its published player and release that exact player on disposal",
            Regex("""onDispose\s*\{\s*latestOnPlayerChanged\(null\)\s*exo\.release\(\)\s*}""")
                .containsMatchIn(body),
        )
        assertEquals(
            "the attachment viewer must use the shared local isolated-player factory",
            1,
            Regex("localVideoPlayer\\(").findAll(body).count(),
        )
        assertLocalFactory(mediaSource("LocalVideoSource.kt").readText())
        assertTrue("attachment viewer must use the isolated decoder", !source.contains("ExoPlayer"))
    }

    /** Draft attachment playback retains controls on release; external Nostr URLs keep their own network route. */
    @Test
    fun draftAndNetworkViewsDetachWithoutSharingCompressedSourceOwnership() {
        val draft = mediaSource("DraftVideoPreview.kt").readText()
        val retained = draft.functionBody("rememberDraftVideoPlayer")
        assertEquals(1, Regex("localVideoPlayer\\(").findAll(retained).count())
        assertTrue(!draft.contains("ExoPlayer"))
        assertTrue(retained.contains("position = live.currentPosition.coerceAtLeast(0L)"))
        assertTrue(retained.contains("volume = live.volume"))
        assertTrue(retained.contains("speed = live.playbackParameters.speed"))
        assertTrue(Regex("""player\s*=\s*null\s*live\.release\(\)""").containsMatchIn(retained))
        val surface = draft.functionBody("DraftVideoSurface")
        assertEquals(1, Regex("AndroidView\\(").findAll(surface).count())
        assertTrue(
            Regex("""onRelease\s*=\s*\{\s*view\s*->\s*view\.player\s*=\s*null\s*}""")
                .containsMatchIn(surface),
        )

        val network = sourceFile("ui/conversation/nostr/NostrEventViewers.kt").readText()
        val owner = network.functionBody("rememberNostrVideoPlayer")
        assertEquals(1, Regex("""ExoPlayer\s*\.Builder\(""").findAll(owner).count())
        assertTrue(owner.contains("DefaultMediaSourceFactory(OkHttpDataSource.Factory(nostrMediaHttpClient))"))
        assertTrue(!owner.contains("localVideoPlayer"))
        assertTrue(Regex("""onDispose\s*\{\s*player\.release\(\)\s*}""").containsMatchIn(owner))
        val networkSurface = network.functionBody("NostrVideoPlayerSurface")
        assertEquals(1, Regex("AndroidView\\(").findAll(networkSurface).count())
        assertTrue(
            Regex("""onRelease\s*=\s*\{\s*playerView\s*->\s*playerView\.player\s*=\s*null\s*}""")
                .containsMatchIn(networkSurface),
        )
    }

    private fun assertLocalFactory(source: String) {
        assertEquals(1, Regex("IsolatedAttachmentPlayer\\(").findAll(source).count())
        assertTrue(source.contains("if (isLocalVideoUri(uri)) IsolatedAttachmentPlayer(context, uri) else null"))
        assertTrue(source.contains("uri.scheme == \"file\" || uri.scheme == \"content\""))
        assertTrue(!source.contains("ExoPlayer"))
    }

    private fun mediaVideoSource(): File = mediaSource("MediaVideo.kt")

    private fun mediaSource(name: String): File = sourceFile("ui/conversation/media/$name")

    /** Resolves production source from repository-root and app-module test working directories. */
    private fun sourceFile(relative: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relative"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relative"),
        ).firstOrNull(File::exists) ?: error("Missing $relative")
}
