package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Received reactions have no attachment provenance in NotificationUpdateFfi. Only APK-bundled artwork
 * is safe to resolve by name; a device-local image with the same shortcode must never impersonate it.
 */
internal suspend fun notificationReactionEmojiArtwork(
    context: Context,
    shortcode: String,
): Bitmap? =
    withContext(Dispatchers.IO) {
        notificationBuiltinEmojiBitmap(context, shortcode)
    }

/** Bounded optional reaction preparation: image failures keep text, while caller cancellation propagates. */
internal suspend fun prepareNotificationReactionArtwork(
    prepare: suspend (String) -> Bitmap?,
    shortcode: String,
    timeoutMs: Long = NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS,
): Bitmap? {
    var bitmap: Bitmap? = null
    var handedOff = false
    return try {
        withTimeoutOrNull(timeoutMs) { prepare(shortcode).also { bitmap = it } }
            .also { handedOff = it != null }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught") // Optional image errors cannot suppress the reaction text.
        _: Exception,
    ) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        if (!handedOff) bitmap?.recycle()
    }
}

/** Shared static APK artwork rendering; no device-local files or received attachment guesses. */
internal fun notificationBuiltinEmojiBitmap(
    context: Context,
    code: String,
): Bitmap? {
    val drawable = EmojiShortcodes.builtinDrawable(code)?.let { AppCompatResources.getDrawable(context, it) }
    return drawable?.let {
        Bitmap
            .createBitmap(
                NOTIFICATION_EMOJI_TILE_PX,
                NOTIFICATION_EMOJI_TILE_PX,
                Bitmap.Config.ARGB_8888,
            ).also { bitmap ->
                it.setBounds(0, 0, bitmap.width, bitmap.height)
                it.draw(Canvas(bitmap))
            }
    }
}
