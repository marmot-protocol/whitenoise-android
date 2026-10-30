package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * Which worker domain each photo-editor decode names (#3373). A worker that an earlier hostile pick of the same
 * conversation compromised has seen that conversation's picks whole, so an edit must never decode there: it could
 * restore what a crop, blur or mark removed. Unedited picks keep their conversation's outgoing domain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditorDomainTest {
    private val seen = Collections.synchronizedList(mutableListOf<MediaTrustDomain>())

    /** Installs a codec that records the domain declared for every inspection and decode, then decodes in-process. */
    @Before
    fun recordDomains() {
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun inspect(bytes: ByteArray): ImageInspection? {
                    seen += MediaTrustScope.current()
                    return TestMediaDecodeTransport.inspect(bytes)
                }

                override fun decode(
                    bytes: ByteArray,
                    edge: Int,
                    oriented: Boolean,
                    operation: Int,
                ): Bitmap? {
                    seen += MediaTrustScope.current()
                    return TestMediaDecodeTransport.decode(bytes, edge, oriented, operation)
                }

                override fun region(
                    bytes: ByteArray,
                    region: MediaImageRegion,
                    edge: Int,
                ): Bitmap? {
                    seen += MediaTrustScope.current()
                    return TestMediaDecodeTransport.region(bytes, region, edge)
                }
            },
        )
    }

    /** Restores the shared in-process codec for the next test. */
    @After
    fun restoreCodec() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    /** Inspection and an unedited render decode in the pick's outgoing domain. */
    @Test
    fun uneditedPickInspectsAndRendersInItsOutgoingDomain() =
        runTest {
            val renderer = renderer()
            renderer.inspect(source())
            renderer.render(source(), PhotoEditRecipe.Original, MediaQuality.High)
            assertTrue("the codec saw no request", seen.isNotEmpty())
            assertEquals(setOf(PICKS), seen.toSet())
        }

    /** A crop, a turn or a mark renders single-use, and so does the editor's own preview of the source. */
    @Test
    fun editedRendersAndTheEditorPreviewDecodeSingleUse() =
        runTest {
            val renderer = renderer()
            listOf(
                PhotoEditRecipe(crop = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)),
                PhotoEditRecipe(quarterTurnsClockwise = 1),
            ).forEach { recipe -> renderer.render(source(), recipe, MediaQuality.High) }
            renderer.decodePreview(source())?.recycle()
            assertTrue("the codec saw no request", seen.isNotEmpty())
            assertEquals(setOf(MediaTrustDomain.Unique), seen.toSet())
            val turned = PhotoEditRecipe(quarterTurnsClockwise = 2)
            assertEquals(MediaTrustDomain.Unique, photoEditRenderDomain(turned, PICKS))
            assertEquals(PICKS, photoEditRenderDomain(PhotoEditRecipe.Original, PICKS))
        }

    /** A renderer whose unedited picks belong to [PICKS]. */
    private fun renderer() =
        PhotoEditorRenderer(
            renderDispatcher = UnconfinedTestDispatcher(),
            memoryBudgetBytes = { 256L * 1024L * 1024L },
            domain = { PICKS },
        )

    /** A small opaque PNG the in-process codec can inspect and decode. */
    private fun source(): ByteArray {
        val bitmap = Bitmap.createBitmap(SOURCE_EDGE, SOURCE_EDGE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        val PICKS = MediaTrustDomain.Outgoing("account", "conversation")
        const val SOURCE_EDGE = 64
    }
}
