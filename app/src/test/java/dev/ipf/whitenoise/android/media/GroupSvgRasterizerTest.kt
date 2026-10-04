package dev.ipf.whitenoise.android.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.IMAGE_DOCUMENT_MIME_TYPES
import dev.ipf.whitenoise.android.ui.group.groupImageFailureDetail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupSvgRasterizerTest {
    private val header = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">"

    @Test
    fun transparentSvgBecomesBoundedPngBeforeCroppingAndOrdinaryUploadDraft() =
        runTest {
            val source = "$header<rect x=\"25\" y=\"25\" width=\"50\" height=\"50\" fill=\"red\"/></svg>"
            val raster = GroupSvgRasterizer.rasterize(source.toByteArray())
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(raster, 0, raster.size))
            assertEquals(1024, bitmap.width)
            assertEquals(1024, bitmap.height)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertTrue(Color.red(bitmap.getPixel(512, 512)) > 240)
            bitmap.recycle()
            val draft = renderIdentityImageDraft(raster, IdentityImageCrop.Centered, MediaQuality.Standard)
            assertTrue(draft.mediaType in setOf("image/jpeg", "image/png"))
            assertTrue(draft.plaintext.size <= REMOTE_PROFILE_IMAGE_MAX_BYTES)
            assertFalse(draft.plaintext.toString(Charsets.UTF_8).contains("<svg"))
            assertEquals(null, draft.initialGroupImage().sourceUrl)
            val finalBitmap = requireNotNull(BitmapFactory.decodeByteArray(draft.plaintext, 0, draft.plaintext.size))
            assertTrue(Color.red(finalBitmap.getPixel(0, 0)) > 240)
            assertTrue(Color.green(finalBitmap.getPixel(0, 0)) > 240)
            finalBitmap.recycle()
        }

    @Test
    fun groupCropSourceAndUncroppedFallbackBothProduceRasterDrafts() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val uri = Uri.parse("content://group-image-fixture/selected.svg")
            val svg = "$header<rect width=\"100\" height=\"100\" fill=\"red\"/></svg>".toByteArray()
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(svg) }
            val source = requireNotNull(loadIdentityImageCropSource(app.contentResolver, uri, prepareGroupImage = true))
            assertEquals(1024, source.orientedSize.width)
            assertEquals(1024, source.orientedSize.height)
            source.preview.recycle()
            val fallback = GroupImageDraftProcessor.fromGroupContentUri(app.contentResolver, uri)
            assertTrue(fallback.mediaType in setOf("image/jpeg", "image/png"))
            assertTrue(fallback.plaintext.size <= REMOTE_PROFILE_IMAGE_MAX_BYTES)
            assertEquals(null, fallback.sourceUrl)
        }

    @Test
    fun viewBoxOnlyAndLocalGradientRender() =
        runTest {
            val xml = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
            <defs><linearGradient id="paint"><stop offset="0" stop-color="red"/>
            <stop offset="1" stop-color="blue"/></linearGradient></defs>
            <rect width="200" height="100" fill="url(#paint)"/></svg>"""
            val bytes = GroupSvgRasterizer.rasterize(xml.toByteArray())
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(1024, bitmap.width)
            assertEquals(512, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(20, 100)) > Color.blue(bitmap.getPixel(20, 100)))
            bitmap.recycle()
        }

    @Test
    fun externalResourcesScriptsAndRecursiveReferencesAreRejectedBeforeRenderer() =
        runTest {
            listOf(
                "$header<image href=\"https://private.invalid/secret\"/></svg>",
                "$header<image href=\"data:image/png;base64,anything\"/></svg>",
                "$header<script>ignored()</script></svg>",
                "$header<rect onclick=\"anything()\"/></svg>",
                "$header<use href=\"#cycle\" id=\"cycle\"/></svg>",
                "$header<pattern id=\"cycle\"><rect fill=\"url(#cycle)\"/></pattern></svg>",
                "$header<style>@import url(https://private.invalid/font);</style></svg>",
                "$header<rect fill=\"url(https://private.invalid/image)\"/></svg>",
                "$header<rect fill=\"url(#missing)\"/></svg>",
                "$header<rect style=\"fill:u\\72l(https://private.invalid)\"/></svg>",
                "$header<foreignObject/></svg>",
                "$header<animate/></svg>",
                "$header<line x2=\"8192\" stroke=\"red\" stroke-dasharray=\"0.0001\"/></svg>",
                "$header<text font-size=\"8192\">unbounded glyph rasterization</text></svg>",
            ).forEachIndexed { index, fixture -> assertRejected(fixture.toByteArray(), index) }
        }

    @Test
    fun entitiesMalformedXmlAndOversizedDocumentsAreRejected() =
        runTest {
            listOf(
                "<!DOCTYPE svg [<!ENTITY a 'secret'>]>$header<text>&a;</text></svg>",
                "<?unsafe external?>$header</svg>",
                "$header<rect></svg>",
                "$header${"<g>".repeat(17)}${"</g>".repeat(17)}</svg>",
                "$header${"<rect/>".repeat(257)}</svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"9000\" height=\"1\"/>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"1\"/>",
                "$header<path d=\"M 0 0 L 1e100 0\"/></svg>",
            ).forEachIndexed { index, fixture -> assertRejected(fixture.toByteArray(), index) }
            assertRejected(ByteArray(GroupSvgPolicy.MAX_BYTES + 1) { 32 })
            assertRejected(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        }

    @Test
    fun rasterTooLargeForWholeFileCropStillUsesStreamingFallback() =
        runTest {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            val oversizedPhoto = output.toByteArray().copyOf(IDENTITY_IMAGE_SOURCE_MAX_BYTES + 1)
            val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
            val uri = Uri.parse("content://group-image-fixture/large.png")
            shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(oversizedPhoto) }
            assertEquals(null, loadIdentityImageCropSource(resolver, uri, prepareGroupImage = true))
            val draft = GroupImageDraftProcessor.fromGroupContentUri(resolver, uri)
            assertEquals("image/jpeg", draft.mediaType)
            assertTrue(draft.plaintext.size <= REMOTE_PROFILE_IMAGE_MAX_BYTES)
        }

    @Test
    fun profileFallbackKeepsRasterOnlyDecoderAndRejectedGroupSvgGetsFormatDetail() =
        runTest {
            val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
            val uri = Uri.parse("content://group-image-fixture/rejected.svg")
            val svg = "$header<script/></svg>".toByteArray()
            shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(svg) }
            assertEquals(null, loadIdentityImageCropSource(resolver, uri, prepareGroupImage = true))
            try {
                GroupImageDraftProcessor.fromGroupContentUri(resolver, uri)
                error("Expected rejected group SVG")
            } catch (failure: ImageUploadPreparationException) {
                assertEquals(ImageUploadPreparationException.UnsupportedSvg, failure)
                val state = WhiteNoiseAppState(context = ApplicationProvider.getApplicationContext())
                state.presentFailure(
                    R.string.toast_couldnt_prepare_image,
                    "GROUP_IMAGE_PREPARE",
                    failure,
                    detail = groupImageFailureDetail(failure),
                )
                assertEquals(AppText.Resource(R.string.group_svg_rejected_detail), state.toast?.detail)
                assertTrue(requireNotNull(state.toast?.diagnosticReport).contains("error=UNSUPPORTED_SVG"))
            }
            try {
                GroupImageDraftProcessor.fromContentUri(resolver, uri)
                error("Expected unsupported profile raster")
            } catch (failure: ImageUploadPreparationException) {
                assertEquals(ImageUploadPreparationException.UnsupportedImage, failure)
            }
        }

    @Test
    fun builtinAndNumericReferencesInMetadataAreSafe() =
        runTest {
            val source =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "$header<title>A &amp; B &#160;</title><rect id=\"curl\" width=\"100\" height=\"100\"/></svg>"
            assertTrue(GroupSvgRasterizer.rasterize(source.toByteArray()).isNotEmpty())
        }

    @Test
    fun groupFilePickerAllowsSvgDocuments() {
        assertTrue(IMAGE_DOCUMENT_MIME_TYPES.any { it == "image/*" || it == "image/svg+xml" })
    }

    private suspend fun assertRejected(
        bytes: ByteArray,
        fixture: Int = -1,
    ) {
        try {
            GroupSvgRasterizer.rasterize(bytes)
            error("SVG fixture $fixture should have been rejected")
        } catch (failure: ImageUploadPreparationException) {
            assertEquals(ImageUploadPreparationException.UnsupportedSvg, failure)
        }
    }
}
