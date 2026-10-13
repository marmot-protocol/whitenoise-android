package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Optional artwork must honor the text notification deadline, including blocking IPC waits. */
internal suspend fun decodeNotificationEmojiInterruptibly(bytes: ByteArray): Bitmap? {
    var decoded: Bitmap? = null
    var handedOff = false
    return try {
        val result =
            runInterruptible(Dispatchers.IO) {
                decodeEmojiImage(bytes)?.asAndroidBitmap().also { decoded = it }
            }
        currentCoroutineContext().ensureActive()
        handedOff = true
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        if (!handedOff) decoded?.recycle()
    }
}
