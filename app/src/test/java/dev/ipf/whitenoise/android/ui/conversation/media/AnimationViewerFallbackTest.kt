package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.AnimationStartupException
import dev.ipf.whitenoise.android.media.AnimationTermination
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real admitted-GIF viewer consumer, not merely the nullable facade in isolation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnimationViewerFallbackTest {
    /**
     * A transient animated-startup failure still yields a static sampled image for the viewer,
     * while the group seam keeps the typed failure.
     */
    @Test
    fun transientAnimatedStartupStillReachesTheSampledImageConsumer(): Unit =
        runBlocking {
            var animations = 0
            var stills = 0
            val transport =
                object : MediaDecodeTransport by TestMediaDecodeTransport {
                    /** Counts the attempt and fails with a transient transport startup error. */
                    override fun animation(
                        bytes: ByteArray,
                        edge: Int,
                    ): Drawable? {
                        animations++
                        throw AnimationStartupException(AnimationTermination.TransportUnavailable)
                    }

                    /** Counts the still decode and returns a small blank bitmap. */
                    override fun decode(
                        bytes: ByteArray,
                        edge: Int,
                        oriented: Boolean,
                        operation: Int,
                    ): Bitmap {
                        stills++
                        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    }
                }
            IsolatedMediaDecoder.installTestTransport(transport)
            try {
                val image = decodeMessageAttachmentImage(twoFrameGif(), "image/gif", 32, animationAllowed = true)
                assertTrue(image is DecodedAttachmentPresentation.Static)
                assertEquals(1, animations)
                assertEquals(1, stills)
                (image as DecodedAttachmentPresentation.Static).bitmap.recycle()
                // Only the internal group seam retains the same typed startup failure.
                assertThrows(AnimationStartupException::class.java) {
                    MediaPipeline.decodeGroupThumbnailAnimation(twoFrameGif(), 32, null)
                }
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }

    /** Only the real viewer opt-in requests original detail; bubbles keep their normal animation budget. */
    @Test
    fun viewerOriginalAnimationIsAnExplicitSourcePolicy(): Unit =
        runBlocking {
            val edges = mutableListOf<Int>()
            val transport =
                object : MediaDecodeTransport by TestMediaDecodeTransport {
                    override fun animation(
                        bytes: ByteArray,
                        edge: Int,
                    ): Drawable? {
                        edges.add(edge)
                        return null
                    }

                    override fun decode(
                        bytes: ByteArray,
                        edge: Int,
                        oriented: Boolean,
                        operation: Int,
                    ): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }
            IsolatedMediaDecoder.installTestTransport(transport)
            try {
                for (viewerOriginal in listOf(false, true)) {
                    val presentation =
                        decodeMessageAttachmentImage(
                            twoFrameGif(),
                            "image/gif",
                            32,
                            animationAllowed = true,
                            viewerOriginalAnimation = viewerOriginal,
                        )
                    (presentation as DecodedAttachmentPresentation.Static).bitmap.recycle()
                }
                assertEquals(listOf(MediaPipeline.ANIMATED_IMAGE_MAX_EDGE_PX, 4096), edges)
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }
}
