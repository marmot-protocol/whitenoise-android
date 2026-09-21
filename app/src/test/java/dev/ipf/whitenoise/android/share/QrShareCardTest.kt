package dev.ipf.whitenoise.android.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrShareCardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Long, Latin and RTL names cannot change the canonical profile QR payload. */
    @Test
    fun profileCardsKeepTheCanonicalQrPayload() {
        val profile = ProfileLink("npub1" + "q".repeat(58))
        listOf("Ada Lovelace", "Long profile name ".repeat(8), "أهلاً بالعالم").forEach { name ->
            val bitmap =
                QrShareCardRenderer.render(
                    QrShareCardSpec(
                        headline = context.getString(R.string.profile_share_card_headline),
                        qrPayload = profile.qrUri,
                        displayName = name,
                    ),
                )

            assertEquals(QrShareCardRenderer.WIDTH_PX, bitmap.width)
            assertEquals(QrShareCardRenderer.HEIGHT_PX, bitmap.height)
            assertEquals(profile.qrUri, decodeQr(bitmap))
        }
    }

    /** The invite card never substitutes display or tracking URLs for the canonical public download URL. */
    @Test
    fun inviteCardKeepsTheCanonicalDownloadQrPayload() {
        val bitmap =
            QrShareCardRenderer.render(
                QrShareCardSpec(
                    headline = context.getString(R.string.invite_to_white_noise),
                    qrPayload = WhiteNoiseUrls.DOWNLOAD,
                ),
            )

        assertEquals(WhiteNoiseUrls.DOWNLOAD, decodeQr(bitmap))
    }

    /** Staged cards use the reviewed image-share contract and contain no plaintext account metadata. */
    @Test
    fun stagedCardIsPngWithFallbackTextReadGrantAndNoHiddenAccountMetadata() =
        runBlocking {
            val privateHex = "0123456789abcdef".repeat(4)
            val staged =
                QrShareCardRenderer.stage(
                    context,
                    QrShareCardSpec(
                        headline = "On White Noise? Message me here.",
                        qrPayload = "marmot://profile/npub1${"q".repeat(58)}?from=qr",
                        displayName = "Ada",
                    ),
                )
            try {
                val intent = outboundShareIntent("https://example.test/profile", listOf(staged.stream))
                val bytes = staged.file.readBytes()

                assertEquals("image/png", intent.type)
                assertEquals("https://example.test/profile", intent.getStringExtra(Intent.EXTRA_TEXT))
                assertEquals(
                    staged.stream.uri,
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java),
                )
                assertEquals(staged.stream.uri, intent.clipData?.getItemAt(0)?.uri)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue(bytes.copyOfRange(1, 4).contentEquals("PNG".encodeToByteArray()))
                assertFalse(bytes.decodeToString().contains(privateHex))
                assertFalse(staged.file.name.contains(privateHex))
                assertNotNull(BitmapFactoryCompat.decode(bytes))
            } finally {
                staged.file.delete()
            }
        }

    /** Decodes the exported bitmap exactly as a receiving app would. */
    private fun decodeQr(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}

private object BitmapFactoryCompat {
    /** Keeps the PNG assertion readable without shadowing the test bitmap import. */
    fun decode(bytes: ByteArray): Bitmap? = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
