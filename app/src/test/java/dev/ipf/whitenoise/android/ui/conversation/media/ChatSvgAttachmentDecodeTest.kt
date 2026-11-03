package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSvgAttachmentDecodeTest {
    /**
     * Supplies valid SVG bytes under misleading media types and checks that positive source inspection selects
     * vector rendering.
     */
    @Test
    fun positivelyValidatedOriginalSvgRendersWhateverItsAdvertisedMime() =
        runBlocking {
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            val source =
                (
                    """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><r""" +
                        """ect width="24" height="24" fill="red"/></svg>"""
                ).toByteArray()
            val result = decodeMessageAttachmentImage(source, "image/png", 128)
            assertTrue(result is DecodedAttachmentPresentation.Static)
            val bitmap = (result as DecodedAttachmentPresentation.Static).bitmap
            try {
                assertEquals(128, bitmap.width)
                assertEquals(android.graphics.Color.RED, bitmap.getPixel(64, 64))
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * Counts alternate decoder calls after animation refusal to ensure rejected bytes are not reinterpreted by
     * another codec.
     */
    @Test
    fun refusedAnimationNeverFallsThroughToStillOrSvgCodec() =
        runBlocking {
            var stills = 0
            var vectors = 0
            val transport =
                object : MediaDecodeTransport by TestMediaDecodeTransport {
                    /** Counts attempts to reinterpret the source as a still image while refusing output. */
                    override fun decode(
                        bytes: ByteArray,
                        edge: Int,
                        oriented: Boolean,
                        operation: Int,
                    ): Bitmap? {
                        stills++
                        return null
                    }

                    /**
                     * Counts vector inspection attempts while refusing metadata so codec fallthrough remains
                     * observable.
                     */
                    override fun svgInspect(bytes: ByteArray): ImageInspection? {
                        vectors++
                        return null
                    }
                }
            IsolatedMediaDecoder.installTestTransport(transport)
            try {
                assertNull(decodeMessageAttachmentImage(twoFrameGif() + byteArrayOf(0), "image/svg+xml", 128))
                assertNull(decodeChatAttachmentStill(twoFrameGif() + byteArrayOf(0), 128))
                assertEquals(0, stills)
                assertEquals(0, vectors)
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }

    /** Uses SVG MIME with unsafe or malformed bytes to prove the advertised type alone grants no renderer access. */
    @Test
    fun svgMimeDoesNotAdmitActiveOrMalformedSource() =
        runBlocking {
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            val unsafe =
                (
                    """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><i""" +
                        """mage href="https://example.invalid/private"/></svg>"""
                ).toByteArray()
            assertNull(decodeMessageAttachmentImage(unsafe, "image/svg+xml", 128))
            assertNull(decodeMessageAttachmentImage("<svg>".toByteArray(), "image/svg+xml", 128))
        }
}
