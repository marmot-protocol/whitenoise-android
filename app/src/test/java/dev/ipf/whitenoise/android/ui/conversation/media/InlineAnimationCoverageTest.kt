package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** All received inline image surfaces promote through one local-only, lifecycle-bound isolated playback owner. */
class InlineAnimationCoverageTest {
    /** GIPHY, standalone attachments and album tiles cannot silently fall back to permanent still-only rendering. */
    @Test
    fun everyReceivedImageSurfaceUsesSharedPlaybackAndLocalBytes() {
        val bubbles = source("MediaImageBubbles.kt")
        for (name in listOf("MediaImageBubble", "MediaImageGridTile")) {
            val body = bubbles.functionBody(name)
            assertTrue("$name must share inline admission", "InlineAttachmentImage(" in body)
            assertTrue("$name must bind account/runtime", "rememberInlineAnimationSource(" in body)
            assertTrue("$name promotion must only read local bytes", "localAttachmentBytes(" in body)
            assertTrue("$name must not lose animation intent in the still cache", "!decoded.animationDeferred" in body)
        }
        val giphy = source("RemoteGiphyMediaBubble.kt")
        assertTrue("InlineAttachmentImage(" in giphy.functionBody("RemoteGiphyMediaCard"))
        assertTrue("rememberInlineAnimationSource(" in giphy.functionBody("RemoteGiphyMediaBubble"))
        assertTrue("imageRequest?.url?.let(giphyByteCache::get)" in giphy)
        assertFalse("giphyPlaybackSlots" in giphy)
    }

    /** Timeline promotion cannot enable a privileged codec or fetch new bytes when it gets a grant. */
    @Test
    fun sharedOwnerKeepsCodecAndLifetimeBoundaries() {
        val source = source("InlineAttachmentImage.kt")
        assertTrue("Semaphore(1)" in source)
        assertTrue("inlineAnimationSlot.withPermit" in source)
        assertTrue(
            "MediaPipeline.decodeAnimatedDrawable(bytes, INLINE_ANIMATION_MAX_EDGE_PX, decorative = true)" in source,
        )
        assertTrue("currentCoroutineContext().ensureActive()" in source)
        assertTrue("LocalWindowInfo.current.isWindowFocused" in source)
        assertTrue("Lifecycle.State.RESUMED" in source)
        assertTrue("onVisibilityChanged" in source)
        assertTrue("runtime == appState.runtimeGeneration" in source)
        assertTrue("appState.isConversationReadVisible(it, groupIdHex)" in source)
        assertTrue("!appState.automaticAttachmentDownloadsPaused()" in source)
        assertFalse("SafeHttpsGet" in source)
        assertFalse("ImageDecoder" in source)
    }

    /** Resolves the source under Gradle's app directory or a repository-root runner. */
    private fun source(name: String): String =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .map { File(it, "dev/ipf/whitenoise/android/ui/conversation/media/$name") }
            .first(File::exists)
            .readText()
}
