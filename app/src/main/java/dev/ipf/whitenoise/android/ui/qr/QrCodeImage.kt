package dev.ipf.whitenoise.android.ui.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.QrCodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun QrCodeImage(
    content: String,
    contentDescription: String = stringResource(R.string.profile_qr_code),
) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, content) {
        value = withContext(Dispatchers.Default) { qrBitmap(content, 900).asImageBitmap() }
    }

    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        if (image == null) {
            Box(Modifier.size(257.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = image!!,
                contentDescription = contentDescription,
                modifier = Modifier.size(257.dp).padding(16.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun qrBitmap(
    content: String,
    size: Int,
): Bitmap {
    val pixels =
        QrCodeEncoder.pixels(
            content = content,
            size = size,
            onColor = android.graphics.Color.BLACK,
            offColor = android.graphics.Color.WHITE,
        )
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
