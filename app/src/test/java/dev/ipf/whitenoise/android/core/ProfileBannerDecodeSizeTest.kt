package dev.ipf.whitenoise.android.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Decoded size of a real high-resolution 2:1 banner (#2762).
 *
 * The avatar cap left a banner at 512x256 before Compose stretched it across the whole screen. These
 * cases decode actual PNG bytes, so they measure the pixels each variant really produces rather than
 * the policy that chose them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileBannerDecodeSizeTest {
    /** Leaves the process-global loader empty for the next test class. */
    @After
    fun tearDownLoader() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /** The banner variant reaches its bounded target while the avatar variant stays at 512px. */
    @Test
    fun bannerVariantDecodesToItsBoundedTargetWhileAvatarStaysCapped() =
        runBlocking {
            val bytes = bannerPngBytes(width = 4000, height = 2000)
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> bytes }
            val url = "https://profiles.example/high-resolution-banner.png"

            val banner = checkNotNull(AvatarImageLoader.loadBanner(url, RENDERED_WIDTH_PX))
            val avatar = checkNotNull(AvatarImageLoader.load(url))

            assertEquals(profileBannerDecodeDimension(RENDERED_WIDTH_PX), banner.width)
            assertEquals(banner.width / 2, banner.height)
            assertTrue(
                "the banner must cover the width it is drawn at, was ${banner.width}",
                banner.width >= RENDERED_WIDTH_PX,
            )
            assertTrue("the avatar cap must be unchanged, was ${avatar.width}", avatar.width <= 512)
            assertEquals(avatar.width / 2, avatar.height)
        }

    /** A near-square source is cut back so one decode can never exceed the shared byte budget. */
    @Test
    fun nearSquareBannerIsCutBackToTheDecodedByteBudget() =
        runBlocking {
            val bytes = bannerPngBytes(width = 4000, height = 4000)
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> bytes }
            val url = "https://profiles.example/square-banner.png"

            val banner = checkNotNull(AvatarImageLoader.loadBanner(url, RENDERED_WIDTH_PX))

            val decodedBytes = banner.width.toLong() * banner.height * 4
            assertTrue("decode must fit the budget, was $decodedBytes", decodedBytes <= MAX_PROFILE_IMAGE_DECODED_BYTES)
        }

    /** PNG bytes for a [width] x [height] fixture with enough detail to survive a real encode. */
    private fun bannerPngBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply { color = Color.WHITE }
        var x = 0
        while (x < width) {
            canvas.drawRect(x.toFloat(), 0f, (x + STRIPE_WIDTH_PX).toFloat(), height.toFloat(), paint)
            x += STRIPE_WIDTH_PX * 2
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private companion object {
        const val RENDERED_WIDTH_PX = 1080
        const val STRIPE_WIDTH_PX = 16
    }
}
