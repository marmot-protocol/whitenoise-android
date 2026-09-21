package dev.ipf.whitenoise.android.share

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Cooperative external target advertised only by the instrumentation APK. */
class OutboundShareTestTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val decoded = stream?.let(::decodeQr)
        val neighborReadable =
            intent.getStringExtra(EXTRA_NEIGHBOR_URI)?.let(Uri::parse)?.let { neighbor ->
                runCatching { contentResolver.openInputStream(neighbor)?.use { it.read() } }.isSuccess
            } ?: false
        getSharedPreferences(RESULTS, MODE_PRIVATE)
            .edit()
            .putString(KEY_DECODED_QR, decoded)
            .putBoolean(KEY_NEIGHBOR_READABLE, neighborReadable)
            .putBoolean(KEY_COMPLETE, true)
            .commit()
        finish()
    }

    /** Reads and decodes the granted image stream from the receiving app's process. */
    private fun decodeQr(uri: Uri): String? =
        contentResolver.openInputStream(uri)?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return@use null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        }

    companion object {
        const val RESULTS = "outbound_share_target_results"
        const val EXTRA_NEIGHBOR_URI = "outbound_share_target.neighbor_uri"
        const val KEY_COMPLETE = "complete"
        const val KEY_DECODED_QR = "decoded_qr"
        const val KEY_NEIGHBOR_READABLE = "neighbor_readable"
    }
}
