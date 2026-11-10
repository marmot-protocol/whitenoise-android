package dev.ipf.whitenoise.android.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Exact-key cache publication that lets a failed visible avatar adopt another consumer's pixels (#3557). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarImageLoaderPublicationTest {
    /** Detaches the test fetcher and retires any waiter a failed assertion left behind. */
    @After
    fun tearDown() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /** A waiter adopts only its own key's publication, from another consumer's single fetch (#3557). */
    @Test
    fun publicationWakesOnlyTheExactKeyWaiterWithoutAnotherFetch() =
        runBlocking {
            val fetches = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetches.incrementAndGet()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            val url = "https://profiles.example/adopt.png"
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            val waiter = async(Dispatchers.Default) { AvatarImageLoader.awaitCached(url, lifetime) }
            withTimeout(5_000) { while (AvatarImageLoader.waitingPublicationKeyCount() == 0) yield() }

            assertNotNull(AvatarImageLoader.load("https://profiles.example/unrelated.png"))
            assertFalse(waiter.isCompleted)
            val published = checkNotNull(AvatarImageLoader.load(url))

            assertSame(published, withTimeout(5_000) { waiter.await() })
            assertEquals(2, fetches.get())
            assertEquals(0, AvatarImageLoader.waitingPublicationKeyCount())
        }

    /**
     * Two placeholders waiting on one key share that key's single registration slot, and one publication from a
     * single fetch wakes both with the same pixels and leaves no waiter behind (#3557).
     */
    @Test
    fun waitersOnOneKeyCoalesceIntoOnePublication() =
        runBlocking {
            val fetches = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                fetches.incrementAndGet()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            val url = "https://profiles.example/shared.png"
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            val first = async(Dispatchers.Default) { AvatarImageLoader.awaitCached(url, lifetime) }
            val second = async(Dispatchers.Default) { AvatarImageLoader.awaitCached(url, lifetime) }
            withTimeout(5_000) { while (AvatarImageLoader.waitingPublicationKeyCount() == 0) yield() }
            assertEquals("both waiters share one key", 1, AvatarImageLoader.waitingPublicationKeyCount())

            val published = checkNotNull(AvatarImageLoader.load(url))

            assertSame(published, withTimeout(5_000) { first.await() })
            assertSame(published, withTimeout(5_000) { second.await() })
            assertEquals(1, fetches.get())
            assertEquals(0, AvatarImageLoader.waitingPublicationKeyCount())
        }

    /** A banner decode of the same URL is a different variant and cannot satisfy an avatar waiter. */
    @Test
    fun bannerPublicationDoesNotSatisfyAnAvatarWaiter() =
        runBlocking {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64) }
            val url = "https://profiles.example/banner-variant.png"
            val waiter =
                async(Dispatchers.Default) {
                    AvatarImageLoader.awaitCached(url, AvatarImageLoader.currentCacheLifetime())
                }
            withTimeout(5_000) { while (AvatarImageLoader.waitingPublicationKeyCount() == 0) yield() }

            assertNotNull(AvatarImageLoader.loadBanner(url, 1440))
            delay(100)

            assertFalse(waiter.isCompleted)
            waiter.cancelAndJoin()
            assertEquals(0, AvatarImageLoader.waitingPublicationKeyCount())
        }

    /** Clearing the cache, or only its stored entries, retires a waiter before it can adopt later pixels. */
    @Test
    fun cacheLifetimeChangesRetireWaitersWithoutAdopting() =
        runBlocking {
            val url = "https://profiles.example/retired.png"
            for (retire in listOf(AvatarImageLoader::clear, AvatarImageLoader::clearStoredAvatars)) {
                val waiter =
                    async(Dispatchers.Default) {
                        AvatarImageLoader.awaitCached(url, AvatarImageLoader.currentCacheLifetime())
                    }
                withTimeout(5_000) { while (AvatarImageLoader.waitingPublicationKeyCount() == 0) yield() }
                retire()
                assertNull(withTimeout(5_000) { waiter.await() })
                assertEquals(0, AvatarImageLoader.waitingPublicationKeyCount())
            }
            val stale = AvatarImageLoader.currentCacheLifetime() - 1
            AvatarImageLoader.putCached(url, ImageBitmap(1, 1))
            assertNull(AvatarImageLoader.awaitCached(url, stale))
        }

    /**
     * #3635: a prefixed clear, as after one contact's Save or Clear, retires only that contact's private pixels and
     * keeps every other stored avatar, such as a chat row's stored picture, and the public images decoded.
     */
    @Test
    fun prefixedStoredClearRetiresOnlyTheKeysItNames() {
        val private = "marmot-avatar:private:owner:contact:picture.png"
        val otherContact = "marmot-avatar:private:owner:other:picture.png"
        val row = "marmot-avatar:owner:row@1"
        val public = "https://public.example/prefixed-clear.png"
        listOf(private, otherContact, row, public).forEach { AvatarImageLoader.putCached(it, ImageBitmap(2, 2)) }

        AvatarImageLoader.clearStoredAvatarsWithPrefix("marmot-avatar:private:owner:contact:")

        assertNull(AvatarImageLoader.cachedImage(private))
        assertNotNull(AvatarImageLoader.cachedImage(otherContact))
        assertNotNull(AvatarImageLoader.cachedImage(row))
        assertNotNull(AvatarImageLoader.peek(public))
    }

    /** An already-published key is read straight away, so a publication made before the wait is not missed. */
    @Test
    fun alreadyCachedPixelsAreReturnedWithoutWaiting() =
        runBlocking {
            val url = "https://profiles.example/already.png"
            val image = ImageBitmap(1, 1)
            AvatarImageLoader.putCached(url, image)
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            assertSame(image, withTimeout(5_000) { AvatarImageLoader.awaitCached(url, lifetime) })
            assertEquals(0, AvatarImageLoader.waitingPublicationKeyCount())
        }

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
