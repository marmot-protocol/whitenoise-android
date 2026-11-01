package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises decode provenance and the existing identity/eviction boundary in the real guarded thumbnail cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaThumbnailCacheTest {
    private lateinit var fixture: MediaDownloadIntegrationFixture

    /** Creates only synthetic app/native state and a private temporary encrypted cache. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
    }

    /** Releases synthetic resources without affecting installed app state. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** A small decode cannot satisfy a larger request; a later small completion cannot replace the adequate variant. */
    @Test
    fun largerRequestsRejectSmallPixelsAndLateSmallResultsCannotDowngradeThem() {
        val key = mediaCacheKey("account", "group", "message", 0)
        val small = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val large = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        fixture.state.cacheMediaThumbnail(key, small, 128)
        assertSame(small, fixture.state.cachedMediaThumbnailEntry(key, 128)?.bitmap)
        assertNull(fixture.state.cachedMediaThumbnailEntry(key, 512))
        fixture.state.cacheMediaThumbnail(key, large, 512)
        fixture.state.cacheMediaThumbnail(key, small, 128)
        assertSame(large, fixture.state.cachedMediaThumbnailEntry(key, 512)?.bitmap)
        assertSame(large, fixture.state.cachedMediaThumbnailEntry(key, 128)?.bitmap)
        assertEquals(setOf(key), fixture.state.mediaMemoryCacheKeysSnapshot())
        assertFalse(small.isRecycled)
    }

    /** A naturally tiny original is already adequate at its recorded decode ceiling and must not decode forever. */
    @Test
    fun tinyOriginalIsCompatibleWithItsRecordedRequest() {
        val key = mediaCacheKey("account", "group", "message", 0)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        fixture.state.cacheMediaThumbnail(key, bitmap, 768)
        assertSame(bitmap, fixture.state.cachedMediaThumbnailEntry(key, 768)?.bitmap)
        assertNull(fixture.state.cachedMediaThumbnailEntry(key, 1024))
    }

    /** Resolution metadata never broadens the existing account, group, message or attachment key. */
    @Test
    fun variantsStayScopedAndExistingEvictionRemovesThem() {
        val key = mediaCacheKey("account", "group", "message", 0)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        fixture.state.cacheMediaThumbnail(key, bitmap, 256)
        listOf(
            mediaCacheKey("other", "group", "message", 0),
            mediaCacheKey("account", "other", "message", 0),
            mediaCacheKey("account", "group", "other", 0),
            mediaCacheKey("account", "group", "message", 1),
        ).forEach { other -> assertNull(fixture.state.cachedMediaThumbnailEntry(other, 128)) }
        fixture.state.removeMediaMemoryCacheEntry(key)
        assertNull(fixture.state.cachedMediaThumbnailEntry(key, 128))
        assertFalse(bitmap.isRecycled)
    }
}
