package dev.ipf.whitenoise.android.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaPipelineSniffImageMediaTypeResolverTest {
    @After
    fun tearDown() {
        ShadowContentResolver.reset()
    }

    @Test
    fun sniffImageMediaType_swallowsDeadProviderOpenInputStreamExceptions() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/ghost.jpg")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        val shadowResolver = shadowOf(context.contentResolver)
        shadowResolver.registerInputStreamSupplier(uri) {
            throw IllegalArgumentException("Unknown URI")
        }

        assertNull(MediaPipeline.sniffImageMediaType(context.contentResolver, uri))
    }

    @Test
    fun sniffImageMediaType_swallowsNullPointerFromOpenInputStream() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/released.jpg")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        val shadowResolver = shadowOf(context.contentResolver)
        shadowResolver.registerInputStreamSupplier(uri) {
            throw NullPointerException("provider released")
        }

        assertNull(MediaPipeline.sniffImageMediaType(context.contentResolver, uri))
    }

    @Test
    fun sniffImageMediaType_readsBoundedJpegHeaderFromResolver() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/photo.jpg")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        }

        assertEquals("image/jpeg", MediaPipeline.sniffImageMediaType(context.contentResolver, uri))
    }

    private class GhostProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = null

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

    private companion object {
        const val AUTHORITY = "dev.ipf.whitenoise.android.media.sniff-image-test"
    }
}
