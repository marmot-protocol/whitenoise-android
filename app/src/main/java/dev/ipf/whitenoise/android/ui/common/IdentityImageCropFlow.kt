package dev.ipf.whitenoise.android.ui.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.IdentityImageCrop
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.IdentityImageCropSource
import dev.ipf.whitenoise.android.media.loadIdentityImageCropSource

/**
 * Puts a crop between picking a picture and publishing it as an identity image.
 *
 * [onCropped] receives the original bytes alongside the chosen crop rather than a finished draft,
 * so each screen keeps the loading, failure and cancellation handling it already had around its own
 * upload. A picture that cannot be read dismisses straight away and leaves that handling to run.
 */
@Suppress("FunctionNaming")
@Composable
internal fun IdentityImageCropFlow(
    uri: Uri?,
    shape: IdentityImageCropShape,
    onDismiss: () -> Unit,
    onUnreadable: (Uri) -> Unit,
    onCropped: (ByteArray, IdentityImageCrop) -> Unit,
) {
    val context = LocalContext.current
    var source by remember(uri) { mutableStateOf<IdentityImageCropSource?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val loaded = loadIdentityImageCropSource(context.contentResolver, uri)
        if (loaded == null) {
            onDismiss()
            onUnreadable(uri)
        } else {
            source = loaded
        }
    }
    val ready = source
    if (uri != null && ready != null) {
        IdentityImageCropDialog(
            preview = ready.preview.asImageBitmap(),
            sourceSize = ready.orientedSize,
            shape = shape,
            onDismiss = onDismiss,
            onConfirm = { crop -> onCropped(ready.bytes, crop) },
        )
    }
}
