package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ipf.whitenoise.android.state.ContactPictureStore
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Local pixel transformation only: bounded, oriented square PNG with no original metadata or upload step. */
internal suspend fun preparePrivateContactPicture(
    source: ByteArray,
    crop: IdentityImageCrop,
): ByteArray {
    require(source.size in 1..IDENTITY_IMAGE_SOURCE_MAX_BYTES)
    val rendered = renderIdentityImageDraft(source, crop, MediaQuality.Low)
    return withContext(Dispatchers.Default) {
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(rendered.plaintext, 0, rendered.plaintext.size))
        try {
            val edge = minOf(decoded.width, decoded.height, PRIVATE_CONTACT_PICTURE_EDGE)
            val bounded = Bitmap.createScaledBitmap(decoded, edge, edge, true)
            try {
                val output = ByteArrayOutputStream()
                check(bounded.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
                output.toByteArray().also { require(it.size <= ContactPictureStore.MAX_BYTES) }
            } finally {
                if (bounded !== decoded) bounded.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }
}

internal const val PRIVATE_CONTACT_PICTURE_EDGE = 512

private const val PNG_QUALITY = 100
