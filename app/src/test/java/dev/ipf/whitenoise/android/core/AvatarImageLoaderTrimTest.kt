package dev.ipf.whitenoise.android.core

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Memory-pressure eviction keeps active avatar owners and admitted reads intact. */
@RunWith(RobolectricTestRunner::class)
class AvatarImageLoaderTrimTest {
    /** Releases only synthetic entries and fetch hooks after each isolated test. */
    @After
    fun tearDown() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /** Trimming releases cache references while a displayed bitmap and its account token remain valid. */
    @Test
    fun memoryTrimPreservesPixelsLifetimeAndFailureCooldown() =
        runBlocking {
            val url = "https://profiles.example/trim.png"
            val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
            AvatarImageLoader.putCachedAnimated(url, image, byteArrayOf(1, 2, 3))
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            val failures = AtomicInteger()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                failures.incrementAndGet()
                error("temporary failure")
            }
            assertNull(AvatarImageLoader.load("https://profiles.example/failure.png"))
            AvatarImageLoader.trimMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.EMPTY)
            assertNull(AvatarImageLoader.peek(url))
            assertNull(AvatarImageLoader.peekAnimatedSource(url))
            org.junit.Assert.assertFalse(image.asAndroidBitmap().isRecycled)
            assertEquals(lifetime, AvatarImageLoader.currentCacheLifetime())
            assertNull(AvatarImageLoader.load("https://profiles.example/failure.png"))
            assertEquals(1, failures.get())
        }

    /** A trim neither retires an admitted read nor permits a second same-key acquisition. */
    @Test
    fun trimKeepsInFlightAvatarReadsCoalesced() =
        runBlocking {
            val key = "marmot-avatar:trim-owner:reference@1"
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val read: suspend () -> ByteArray? = {
                reads.incrementAndGet()
                entered.complete(Unit)
                release.await()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            val first = async { AvatarImageLoader.loadStored(key, lifetime, read) }
            try {
                withTimeout(5_000) { entered.await() }
                AvatarImageLoader.trimMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.EMPTY)
                val second =
                    async(start = CoroutineStart.UNDISPATCHED) { AvatarImageLoader.loadStored(key, lifetime, read) }
                release.complete(Unit)
                val image = withTimeout(5_000) { first.await() }
                assertNotNull(image)
                assertSame(image, withTimeout(5_000) { second.await() })
                assertEquals(1, reads.get())
                assertSame(image, AvatarImageLoader.loadStored(key, lifetime) { error("unexpected reacquisition") })
            } finally {
                release.complete(Unit)
            }
        }

    /** Each partition keeps its MRU half and repeated hidden callbacks cause no extra eviction. */
    @Test
    fun warmTrimIsBoundedIdempotentAndMeasuredByExistingCounters() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        val bytes = bitmap.asAndroidBitmap().byteCount
        val cache = PartitionedProfileImageCache(bytes * 4, bytes * 4)
        val bannerKeys = (1..4).map { profileImageCacheKey("banner-$it", ProfileImageVariant.BANNER, 512) }
        AvatarCacheDiagnostics.start()
        try {
            repeat(4) { index ->
                cache.put("avatar-$index", bitmap)
                cache.put(bannerKeys[index], bitmap)
            }
            cache.trimMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.WARM)
            assertEquals(bytes * 2, cache.byteSize(ProfileImageVariant.AVATAR))
            assertEquals(bytes * 2, cache.byteSize(ProfileImageVariant.BANNER))
            assertNull(cache.get("avatar-0"))
            assertSame(bitmap, cache.get("avatar-3"))
            assertNull(cache.get(bannerKeys.first()))
            assertSame(bitmap, cache.get(bannerKeys.last()))
            val once = AvatarCacheDiagnostics.snapshot()
            cache.trimMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.WARM)
            assertEquals(once, AvatarCacheDiagnostics.snapshot())
            assertEquals(2L, once.profile.evictions)
            assertEquals(0L, once.profile.fetchCalls)
            assertEquals(0L, once.profile.decodeCalls)
        } finally {
            AvatarCacheDiagnostics.stop()
        }
    }

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
