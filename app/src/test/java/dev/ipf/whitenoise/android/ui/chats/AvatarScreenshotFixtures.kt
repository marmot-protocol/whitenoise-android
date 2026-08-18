package dev.ipf.whitenoise.android.ui.chats

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Base64

internal object AvatarScreenshotFixtures {
    fun onePixelPngBytes(): ByteArray = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)

    fun distinctAvatarBitmap(color: Int = Color.RED): ImageBitmap =
        Bitmap
            .createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply {
                eraseColor(color)
            }.asImageBitmap()

    private const val ONE_PIXEL_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
}
