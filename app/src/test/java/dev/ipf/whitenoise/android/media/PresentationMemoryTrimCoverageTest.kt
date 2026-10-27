package dev.ipf.whitenoise.android.media

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.core.AvatarCacheChanges
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.functionBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The production callback releases references without bootstrapping MDK or entering teardown paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = PresentationTrimTestApplication::class)
class PresentationMemoryTrimCoverageTest {
    /** Synthetic cache entries cannot leak into another fixture. */
    @After
    fun clearPixels() {
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
    }

    /** A real application callback reaches both global image caches while leaving a cold runtime cold. */
    @Test
    fun backgroundCallbackTrimsGlobalPixelsWithoutCreatingAppState() {
        val application = ApplicationProvider.getApplicationContext<PresentationTrimTestApplication>()
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        AvatarImageLoader.putCached("trim-profile", image)
        GroupAvatarImageLoader.putCached("trim-group", image)
        assertNull(application.initializedAppState())
        val published = AvatarCacheChanges.revision.value
        application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull(AvatarImageLoader.peek("trim-profile"))
        assertNull(GroupAvatarImageLoader.peek("trim-group"))
        assertFalse(image.asAndroidBitmap().isRecycled)
        org.junit.Assert.assertSame(
            "cache-only eviction must not rewrite launcher icons",
            published,
            AvatarCacheChanges.revision.value,
        )
        assertNull(application.initializedAppState())
        AvatarImageLoader.putCached("fresh", image)
        org.junit.Assert.assertNotSame(published, AvatarCacheChanges.revision.value)
    }

    /** Every image tier is wired, and trim cannot accidentally become account teardown or disk eviction. */
    @Test
    fun productionTrimKeepsTheCacheOwnershipBoundary() {
        val callback =
            source("WhiteNoiseApplication.kt")
                .functionBody("onTrimMemory")
                .filterNot(Char::isWhitespace)
        listOf("AvatarImageLoader.trimMemory", "GroupAvatarImageLoader.trimMemory", "trimRemoteGiphyMemory").forEach {
            assertTrue("missing process-cache trim: $it", callback.contains(it))
        }
        assertTrue(callback.contains("initializedAppState()?.trimPresentationMemory(trim)"))
        val state = source("state/AppState.kt").functionBody("trimPresentationMemory")
        assertTrue(state.contains("assertMainThread"))
        val stateTrims =
            listOf(
                "mediaPlaintextCache.trimToSize",
                "mediaThumbnailCache.trimToSize",
                "publicJpegPreviewLoader.trimMemory",
            )
        stateTrims.forEach { assertTrue("missing app-cache trim: $it", state.contains(it)) }
        val trimBodies =
            callback + state +
                source("core/AvatarImageLoader.kt").functionBody("trimMemory") +
                source("core/GroupAvatarImageLoader.kt").functionBody("trimMemory")
        val destructiveCalls =
            listOf(
                ".clear(",
                ".recycle(",
                "resetForAccount",
                "retireRequests",
                "diskMediaCache",
                "retainedMediaUploads",
            )
        destructiveCalls.forEach {
            assertFalse("trim must not retire active or durable ownership: $it", trimBodies.contains(it))
        }
    }

    /** Source lookup works from either the repository root or the Gradle app project directory. */
    private fun source(path: String): String {
        val relative = "src/main/java/dev/ipf/whitenoise/android/$path"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }
}

/** Leaves startup services disabled so the trim callback itself is the only operation under test. */
class PresentationTrimTestApplication : WhiteNoiseApplication() {
    /** No background work or native bootstrap is part of this cache-only fixture. */
    override fun onCreate() = Unit
}
