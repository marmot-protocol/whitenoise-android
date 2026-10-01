package dev.ipf.whitenoise.android.core

import android.graphics.drawable.AnimatedImageDrawable
import dev.ipf.whitenoise.android.ui.common.decodeAnimatedProfileAvatar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** GIF profile pictures keep their static first-frame contract and gain only a bounded animated source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedProfileAvatarLoaderTest {
    /** Leaves the process-wide loader empty and detached for the next test. */
    @After
    fun tearDown() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /** A GIF publishes its first frame for every consumer and keeps the encoded bytes for animation. */
    @Test
    fun gifAvatarKeepsItsStaticFirstFrameAndAnimatedSource() =
        runBlocking {
            val gif = twoFrameGif()
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> gif }
            val url = "https://profiles.example/alice"

            assertNotNull(AvatarImageLoader.load(url))
            assertArrayEquals(gif, AvatarImageLoader.peekAnimatedSource(url))
            // Notification and shortcut icons keep reading the same cached first frame.
            assertNotNull(AvatarImageLoader.peekBitmap(url))
        }

    /** Static images keep today's behaviour and never gain an animated source. */
    @Test
    fun staticAvatarHasNoAnimatedSource() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> pngBytes() }
            val url = "https://profiles.example/static.png"

            assertNotNull(AvatarImageLoader.load(url))
            assertNull(AvatarImageLoader.peekAnimatedSource(url))
        }

    /** Content decides, not the URL: a `.gif` URL serving PNG stays static, a `.png` URL serving GIF animates. */
    @Test
    fun mislabeledUrlsAreClassifiedByContent() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { url, _ ->
                if (url.endsWith(".gif")) pngBytes() else twoFrameGif()
            }

            assertNotNull(AvatarImageLoader.load("https://profiles.example/looks-animated.gif"))
            assertNotNull(AvatarImageLoader.load("https://profiles.example/looks-static.png"))
            assertNull(AvatarImageLoader.peekAnimatedSource("https://profiles.example/looks-animated.gif"))
            assertNotNull(AvatarImageLoader.peekAnimatedSource("https://profiles.example/looks-static.png"))
        }

    /** A truncated GIF fails like any undecodable avatar: no source, and the failure TTL blocks a refetch. */
    @Test
    fun truncatedGifFallsBackWithoutRetrying() =
        runBlocking {
            val fetches = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetches.incrementAndGet()
                twoFrameGif().copyOf(GIF_HEADER_ONLY_BYTES)
            }
            val url = "https://profiles.example/truncated"

            assertNull(AvatarImageLoader.load(url))
            assertNull(AvatarImageLoader.load(url))
            assertNull(AvatarImageLoader.peekAnimatedSource(url))
            assertEquals(1, fetches.get())
        }

    /** Concurrent surfaces share one fetch and one source. */
    @Test
    fun concurrentLoadsShareOneFetchAndSource() =
        runBlocking {
            val fetches = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetches.incrementAndGet()
                twoFrameGif()
            }
            val url = "https://profiles.example/shared"

            (1..4).map { async { AvatarImageLoader.load(url) } }.awaitAll()

            assertEquals(1, fetches.get())
            assertNotNull(AvatarImageLoader.peekAnimatedSource(url))
        }

    /** An account clear drops animated sources with the pixels, so nothing survives into the next owner. */
    @Test
    fun clearDropsAnimatedSources() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> twoFrameGif() }
            val url = "https://profiles.example/cleared"
            assertNotNull(AvatarImageLoader.load(url))

            AvatarImageLoader.clear()

            assertNull(AvatarImageLoader.peekAnimatedSource(url))
        }

    /** Banners are out of scope: a GIF banner decodes statically and leaves no avatar source behind. */
    @Test
    fun bannerRequestsNeverKeepAnAnimatedSource() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> twoFrameGif() }
            val url = "https://profiles.example/banner"

            assertNotNull(AvatarImageLoader.loadBanner(url, targetWidthPx = 1080))

            assertNull(AvatarImageLoader.peekAnimatedSource(url))
        }

    /** The sniff accepts only a GIF signature with a non-empty canvas inside the animation bound. */
    @Test
    fun sniffRequiresAGifSignatureAndABoundedCanvas() {
        assertTrue(isAnimatableProfileAvatar(twoFrameGif()))
        assertTrue(isAnimatableProfileAvatar(twoFrameGif(MAX_ANIMATED_PROFILE_AVATAR_EDGE, 1)))
        assertFalse(isAnimatableProfileAvatar(twoFrameGif(MAX_ANIMATED_PROFILE_AVATAR_EDGE + 1, 1)))
        assertFalse(isAnimatableProfileAvatar(twoFrameGif(1, MAX_ANIMATED_PROFILE_AVATAR_EDGE + 1)))
        assertFalse(isAnimatableProfileAvatar(twoFrameGif(0, 1)))
        assertFalse(isAnimatableProfileAvatar(pngBytes()))
        assertFalse(isAnimatableProfileAvatar("GIF89a".encodeToByteArray()))
    }

    /** An oversized GIF canvas, an animation-bomb shape, keeps only its static first frame. */
    @Test
    fun oversizedGifCanvasStaysStatic() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> twoFrameGif(MAX_ANIMATED_PROFILE_AVATAR_EDGE * 2, 2) }
            val url = "https://profiles.example/huge-canvas"

            assertNotNull(AvatarImageLoader.load(url))
            assertNull(AvatarImageLoader.peekAnimatedSource(url))
        }

    /** The viewer's larger fetch still animates only within the avatar's encoded-byte bound. */
    @Test
    fun viewerSourceHonoursTheAvatarByteBound() {
        val gif = twoFrameGif()
        assertArrayEquals(gif, profileAvatarAnimationSource(gif))
        assertNull(profileAvatarAnimationSource(gif + ByteArray(2 * 1024 * 1024)))
        assertNull(profileAvatarAnimationSource(null))
    }

    /** The shared decoder yields a real animated drawable no larger than the requested edge. */
    @Test
    fun decodeProducesABoundedAnimatedDrawable() =
        runBlocking {
            val drawable = decodeAnimatedProfileAvatar(twoFrameGif(64, 32), maxEdgePx = 16)

            assertTrue(drawable is AnimatedImageDrawable)
            assertTrue(checkNotNull(drawable).intrinsicWidth <= 16)
            assertTrue(drawable.intrinsicHeight <= 16)
        }

    /** A malformed animated source decodes to nothing, leaving the caller on its first frame. */
    @Test
    fun malformedSourceDecodesToNothing() =
        runBlocking {
            assertNull(decodeAnimatedProfileAvatar(twoFrameGif().copyOf(GIF_HEADER_ONLY_BYTES), maxEdgePx = 16))
        }

    private companion object {
        /** Signature, logical screen and palette only — no frames. */
        const val GIF_HEADER_ONLY_BYTES = 19

        /** One opaque pixel, the loader tests' static fixture. */
        fun pngBytes(): ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
