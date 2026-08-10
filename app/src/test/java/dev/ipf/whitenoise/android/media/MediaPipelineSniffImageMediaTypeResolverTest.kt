package dev.ipf.whitenoise.android.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

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

    @Test
    fun animationProbeFailureFailsClosedForTheSendPath() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/broken.png")
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
        assertTrue(MediaPipeline.shouldPreserveOriginalImageSource(context.contentResolver, uri))
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

    @Test
    fun apngAndStaticPngAreDistinguishedBeforePixelData() {
        val context = RuntimeEnvironment.getApplication()
        val animatedUri = Uri.parse("content://$AUTHORITY/animated.png")
        val staticUri = Uri.parse("content://$AUTHORITY/static.png")
        val prefix = PNG_SIGNATURE + pngChunk("IHDR", ByteArray(13))
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(animatedUri) {
            ByteArrayInputStream(prefix + pngChunk("acTL", ByteArray(8)) + pngChunk("IDAT", byteArrayOf(1)))
        }
        shadowOf(context.contentResolver).registerInputStreamSupplier(staticUri) {
            ByteArrayInputStream(prefix + pngChunk("IDAT", ByteArray(5_000)))
        }

        assertEquals(
            ImageAnimationStatus.ANIMATED,
            MediaPipeline.imageAnimationStatus(context.contentResolver, animatedUri),
        )
        assertEquals(
            ImageAnimationStatus.STATIC,
            MediaPipeline.imageAnimationStatus(context.contentResolver, staticUri),
        )
    }

    @Test
    fun pngPrefixWithoutAnimationControlOrImageDataIsIndeterminate() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://$AUTHORITY/truncated.png")
        val png = PNG_SIGNATURE + pngChunk("iCCP", ByteArray(5_000))
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(png) }

        assertEquals(
            ImageAnimationStatus.INDETERMINATE,
            MediaPipeline.imageAnimationStatus(context.contentResolver, uri),
        )
    }

    @Test
    fun staticAndAnimatedIsoBmffBrandsAreDistinguished() {
        val context = RuntimeEnvironment.getApplication()
        val staticAvif = Uri.parse("content://$AUTHORITY/static.avif")
        val animatedAvif = Uri.parse("content://$AUTHORITY/animated.avif")
        val staticHeic = Uri.parse("content://$AUTHORITY/static.heic")
        ShadowContentResolver.registerProviderInternal(AUTHORITY, GhostProvider())
        shadowOf(context.contentResolver).registerInputStreamSupplier(staticAvif) {
            ByteArrayInputStream(isoBmffFileTypeBox("avif", "mif1"))
        }
        shadowOf(context.contentResolver).registerInputStreamSupplier(animatedAvif) {
            ByteArrayInputStream(isoBmffFileTypeBox("avis", "avif"))
        }
        shadowOf(context.contentResolver).registerInputStreamSupplier(staticHeic) {
            ByteArrayInputStream(isoBmffFileTypeBox("heic", "mif1"))
        }

        assertEquals(
            ImageAnimationStatus.STATIC,
            MediaPipeline.imageAnimationStatus(context.contentResolver, staticAvif),
        )
        assertEquals(
            ImageAnimationStatus.ANIMATED,
            MediaPipeline.imageAnimationStatus(context.contentResolver, animatedAvif),
        )
        assertEquals(
            ImageAnimationStatus.STATIC,
            MediaPipeline.imageAnimationStatus(context.contentResolver, staticHeic),
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

    private fun pngChunk(
        type: String,
        data: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(data.size).array())
            output.write(type.toByteArray(Charsets.US_ASCII))
            output.write(data)
            output.write(ByteArray(Int.SIZE_BYTES))
            output.toByteArray()
        }

    private fun isoBmffFileTypeBox(
        majorBrand: String,
        vararg compatibleBrands: String,
    ): ByteArray {
        val size = 16 + compatibleBrands.size * 4
        return ByteBuffer
            .allocate(size)
            .putInt(size)
            .put("ftyp".toByteArray(Charsets.US_ASCII))
            .put(majorBrand.toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .apply {
                compatibleBrands.forEach { brand -> put(brand.toByteArray(Charsets.US_ASCII)) }
            }.array()
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
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
