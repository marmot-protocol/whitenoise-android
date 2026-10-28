package dev.ipf.whitenoise.android.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

/** In-memory video preparation: private metadata is removed before the probe, and refusals never fall back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [MediaPipelineVideoReadTest.ThrowingMetadataRetrieverShadow::class])
class MediaPipelineVideoReadTest {
    /** Resets the provider registry and the retriever shadow between cases. */
    @After
    fun tearDown() {
        ShadowContentResolver.reset()
        ThrowingMetadataRetrieverShadow.reset()
    }

    /**
     * A copied video whose probe fails still releases the retriever. The probe already sees the scrubbed copy, so
     * private metadata is removed before anything reads the file.
     */
    @Test
    fun returnsFailedWhenMetadataRetrieverRejectsCopiedVideo() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/corrupt.mp4")
        val tagged = videoFixture("mp4-tagged.mp4")

        // The provider passes the video MIME gate, and the stream supplier supplies a real tagged clip,
        // so the test reaches MediaMetadataRetriever and can verify release cleanup.
        ShadowContentResolver.registerProviderInternal(AUTHORITY, VideoMimeProvider())
        shadowOf(context.contentResolver)
            .registerInputStreamSupplier(uri) { ByteArrayInputStream(tagged) }

        val result = MediaPipeline.readVideoForUpload(context, uri)

        assertSame(MediaPipeline.VideoReadResult.Failed, result)
        assertEquals(1, ThrowingMetadataRetrieverShadow.releaseCalls)
        val probed = checkNotNull(ThrowingMetadataRetrieverShadow.probedBytes)
        assertArrayEquals(tagged.copyOf().also { VideoMetadataScrubber.scrub(it) }, probed)
        assertFalse(probed.containsAscii("WNPrivateTitle"))
        assertTrue(videoTempFiles(context).isEmpty())
    }

    /** A video whose metadata cannot be removed is refused before any probe, and its private copy is deleted. */
    @Test
    fun returnsMetadataNotRemovableForUnsupportedVideoWithoutProbing() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/clip.avi")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, VideoMimeProvider())
        shadowOf(context.contentResolver)
            .registerInputStreamSupplier(uri) { ByteArrayInputStream(aviVideoBytes()) }

        val result = MediaPipeline.readVideoForUpload(context, uri)

        assertSame(MediaPipeline.VideoReadResult.MetadataNotRemovable, result)
        assertEquals("the retriever never saw the unscrubbed bytes", 0, ThrowingMetadataRetrieverShadow.releaseCalls)
        assertNull(ThrowingMetadataRetrieverShadow.probedBytes)
        assertTrue(videoTempFiles(context).isEmpty())
    }

    /** Copies left in the private video-metadata cache. */
    private fun videoTempFiles(context: android.content.Context): List<java.io.File> =
        java.io
            .File(context.cacheDir, MediaCacheDirs.VIDEO)
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("vidmeta-") }

    /** A provider that reports every Uri as an MP4 video, so a read reaches the video path. */
    private class VideoMimeProvider : ContentProvider() {
        /** Needs no setup. */
        override fun onCreate(): Boolean = true

        /** Reports the video type that admits the pick to the video reader. */
        override fun getType(uri: Uri): String = "video/mp4"

        /** Answers no metadata queries, so the reader falls back to its default name. */
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        /** Read-only provider. */
        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        /** Read-only provider. */
        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        /** Read-only provider. */
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    /** A retriever that records the bytes it was given and then rejects them. */
    @Implements(MediaMetadataRetriever::class)
    class ThrowingMetadataRetrieverShadow {
        /** Records the descriptor's bytes and fails like a corrupt file would. */
        @Implementation
        fun setDataSource(
            descriptor: java.io.FileDescriptor,
            offset: Long,
            length: Long,
        ) {
            check(descriptor.valid() && offset == 0L && length > 0L)
            probedBytes = java.io.FileInputStream(descriptor).readBytes()
            throw RuntimeException("setDataSource failed")
        }

        /** Counts releases so cleanup after a failed probe is observable. */
        @Implementation
        fun release() {
            releaseCalls += 1
        }

        companion object {
            var releaseCalls: Int = 0
                private set

            /** The bytes the retriever was given, read from its descriptor. */
            var probedBytes: ByteArray? = null
                private set

            /** Clears the recorded calls and bytes between tests. */
            @JvmStatic
            @Resetter
            fun reset() {
                releaseCalls = 0
                probedBytes = null
            }
        }
    }

    private companion object {
        const val AUTHORITY = "dev.ipf.whitenoise.android.media.video-test"
    }
}
