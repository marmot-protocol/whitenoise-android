package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import dev.ipf.whitenoise.android.core.twoFrameGif
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Explicit viewer intent may promote an admitted still; timeline rendering never selects animation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AttachmentAnimationIntentTest {
    @Test
    fun admittedGifKeepsFirstFrameUntilTheViewerCanAnimate() =
        runBlocking<Unit> {
            val bytes = twoFrameGif()
            val still = decodeMessageAttachmentImage(bytes, "image/png", 128) as DecodedAttachmentPresentation.Static
            try {
                assertTrue(still.animationDeferred)
                val animated =
                    decodeMessageAttachmentImage(bytes, "image/png", 128, animationAllowed = true)
                        as DecodedAttachmentPresentation.Animated
                (animated.drawable as? AnimatedImageDrawable)?.stop()
            } finally {
                still.bitmap.recycle()
            }
        }

    @Test
    fun staticPhotoNeverRequestsPromotionEvenWithMisleadingMime() =
        runBlocking<Unit> {
            val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes =
                try {
                    source.eraseColor(Color.RED)
                    ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                } finally {
                    source.recycle()
                }
            val photo =
                decodeMessageAttachmentImage(bytes, "image/gif", 128, animationAllowed = true)
                    as DecodedAttachmentPresentation.Static
            try {
                assertFalse(photo.animationDeferred)
            } finally {
                photo.bitmap.recycle()
            }
        }
}
