package dev.ipf.whitenoise.android.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [MediaPipelineVideoReadTest.ThrowingMetadataRetrieverShadow::class])
class MediaPipelineVideoReadTest {
    @After
    fun tearDown() {
        ShadowContentResolver.reset()
        ThrowingMetadataRetrieverShadow.reset()
    }

    @Test
    fun returnsFailedWhenMetadataRetrieverRejectsCopiedVideo() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/corrupt.mp4")

        ShadowContentResolver.registerProviderInternal(AUTHORITY, VideoMimeProvider())
        shadowOf(context.contentResolver)
            .registerInputStreamSupplier(uri) { ByteArrayInputStream(ByteArray(32) { 0x7f }) }

        val result = MediaPipeline.readVideoForUpload(context, uri)

        assertSame(MediaPipeline.VideoReadResult.Failed, result)
        assertEquals(1, ThrowingMetadataRetrieverShadow.releaseCalls)
    }

    private class VideoMimeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "video/mp4"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    @Implements(MediaMetadataRetriever::class)
    class ThrowingMetadataRetrieverShadow {
        @Implementation
        fun setDataSource(path: String): Unit = throw RuntimeException("setDataSource failed")

        @Implementation
        fun release() {
            releaseCalls += 1
        }

        companion object {
            var releaseCalls: Int = 0
                private set

            @JvmStatic
            @Resetter
            fun reset() {
                releaseCalls = 0
            }
        }
    }

    private companion object {
        const val AUTHORITY = "dev.ipf.whitenoise.android.media.video-test"
    }
}
