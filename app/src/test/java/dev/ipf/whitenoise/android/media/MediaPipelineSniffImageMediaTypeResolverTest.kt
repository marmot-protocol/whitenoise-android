package dev.ipf.whitenoise.android.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun decodeSampledFromUri_returnsNullWhenProviderStreamFailsDuringRead() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/broken.jpg")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException("provider stream failed")
            }
        }

        assertNull(MediaPipeline.decodeSampledFromUri(context.contentResolver, uri))
    }

    @Test
    fun animationStatusReturnsIndeterminateWhenProviderStreamFailsDuringRead() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/broken.gif")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException("provider stream failed")
            }
        }

        assertEquals(
            ImageAnimationStatus.INDETERMINATE,
            MediaPipeline.imageAnimationStatus(context.contentResolver, uri),
        )
    }

    @Test
    fun animationProbeFailureStaysIndeterminateWhenTheProviderLaterDecodes() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/flaky.png")
        val png =
            ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.BLUE)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                } finally {
                    bitmap.recycle()
                }
                output.toByteArray()
            }
        val opens = AtomicInteger()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            if (opens.getAndIncrement() == 0) {
                object : InputStream() {
                    override fun read(): Int = throw UnsupportedOperationException("transient provider failure")
                }
            } else {
                ByteArrayInputStream(png)
            }
        }

        val probe = MediaPipeline.imageAnimationStatus(context.contentResolver, uri)
        val decoded = MediaPipeline.decodeSampledFromUri(context.contentResolver, uri)

        assertEquals(ImageAnimationStatus.INDETERMINATE, probe)
        assertNotNull(decoded)
        decoded?.recycle()
    }

    @Test
    fun animatedWebpWithLargeIccChunkIsDetectedFromTheVp8xFlag() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/animated.webp")
        val body =
            webpChunk("VP8X", byteArrayOf(0x02) + ByteArray(9)) +
                webpChunk("ICCP", ByteArray(5_000)) +
                webpChunk("ANIM", ByteArray(6))
        val webp = "RIFF".encodeToByteArray() + u32le(body.size + 4) + "WEBP".encodeToByteArray() + body
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(webp) }

        assertEquals(
            ImageAnimationStatus.ANIMATED,
            MediaPipeline.imageAnimationStatus(context.contentResolver, uri),
        )
    }

    private fun webpChunk(
        fourCc: String,
        payload: ByteArray,
    ): ByteArray =
        fourCc.encodeToByteArray() +
            u32le(payload.size) +
            payload +
            if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun u32le(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )

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
