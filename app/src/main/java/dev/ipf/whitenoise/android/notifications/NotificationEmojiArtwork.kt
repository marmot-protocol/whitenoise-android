package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import dev.ipf.whitenoise.android.ui.sanitizeEmojiCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal const val NOTIFICATION_EMOJI_TILE_PX = 128
internal const val NOTIFICATION_EMOJI_MAX_IMAGES = 4
internal const val NOTIFICATION_EMOJI_MAX_BYTES = 256 * 1024
internal const val NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS = 250L
private const val MAX_CANDIDATE_CODES = 16
private const val MAX_SOURCE_CHARS = 8192
private const val MAX_ARTIFACTS = 64
private const val EXPORT_DIRECTORY = "notification_emoji"
private val ownedName = Regex("[0-9a-f-]{36}\\.png")
private val artifactLock = NotificationEmojiLeases.lock

/** An entire optional preview reads no more than four source images' worth of bytes. */
private class EmojiReadBudget {
    private var remaining = NOTIFICATION_EMOJI_MAX_IMAGES * CustomEmojiStore.MAX_BYTES

    fun read(file: File): ByteArray? {
        if (remaining <= 0) return null
        val limit = minOf(remaining, CustomEmojiStore.MAX_BYTES)
        val bytes = readNotificationEmojiBytes(file, limit)
        remaining -= bytes.size
        return bytes.takeIf { it.size <= limit }
    }
}

/** An immutable derived image, held until its guarded post finishes. Original emoji files are never exported. */
class NotificationEmojiArtifact internal constructor(
    val uri: Uri,
    name: String,
) : AutoCloseable {
    private val lease = NotificationEmojiLeases.retain(setOf(name))

    override fun close() {
        lease.close()
    }
}

/** Optional image errors must not suppress a text notification. Caller cancellation is never swallowed. */
internal suspend fun prepareNotificationEmojiArtwork(
    prepare: suspend (String, String?) -> NotificationEmojiArtifact?,
    text: String,
    source: String?,
    timeoutMs: Long = NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS,
): NotificationEmojiArtifact? {
    var artifact: NotificationEmojiArtifact? = null
    var handedOff = false
    return try {
        val result = withTimeoutOrNull(timeoutMs) {
            prepare(text, source).also { artifact = it }
        }
        handedOff = result != null
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        // Optional image failure, never a protocol/notification failure.
        @Suppress("TooGenericExceptionCaught")
        _: Exception,
    ) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        if (!handedOff) artifact?.close()
    }
}

/** Optional presentation only: no network, protocol cache, or received-message shortcode lookup. */
internal suspend fun notificationEmojiArtwork(
    context: Context,
    text: String,
    sourceText: String? = text,
): NotificationEmojiArtifact? {
    var artifact: NotificationEmojiArtifact? = null
    var handedOff = false
    return try {
        withContext(Dispatchers.IO) {
            val codes = notificationEmojiCodes(text, sourceText)
            if (codes.isEmpty()) return@withContext null
            val bitmaps = mutableListOf<Bitmap>()
            val readBudget = EmojiReadBudget()
            try {
                for (code in codes) {
                    coroutineContext.ensureActive()
                    notificationEmojiBitmap(context, code, readBudget)?.let(bitmaps::add)
                    if (bitmaps.size == NOTIFICATION_EMOJI_MAX_IMAGES) break
                }
                if (bitmaps.isEmpty()) return@withContext null
                coroutineContext.ensureActive()
                val rendered = renderNotificationEmojiArtwork(bitmaps)
                try {
                    coroutineContext.ensureActive()
                    writeNotificationEmojiArtifact(context, rendered).also { artifact = it }
                } finally {
                    rendered.recycle()
                }
            } finally {
                bitmaps.forEach(Bitmap::recycle)
            }
        }.also { handedOff = true }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    } finally {
        if (!handedOff) artifact?.close()
    }
}

/** A flattened preview loses link annotations, so only qualified original text can supply artwork. */
private fun notificationEmojiCodes(text: String, sourceText: String?): List<String> {
    val source = sourceText?.takeUnless { it.length > MAX_SOURCE_CHARS || '[' in it || "://" in it }
    return source?.let { EmojiShortcodes.presentationShortcodes(it).filter(text::contains).take(MAX_CANDIDATE_CODES) }
        ?: emptyList()
}

/** Copies at most four already bounded images into a static, aspect-preserving strip. */
internal fun renderNotificationEmojiArtwork(images: List<Bitmap>): Bitmap {
    val selected = images.take(NOTIFICATION_EMOJI_MAX_IMAGES)
    require(selected.isNotEmpty())
    val output =
        Bitmap.createBitmap(
            selected.size * NOTIFICATION_EMOJI_TILE_PX,
            NOTIFICATION_EMOJI_TILE_PX,
            Bitmap.Config.ARGB_8888,
        )
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    selected.forEachIndexed { index, image ->
        val scale = NOTIFICATION_EMOJI_TILE_PX.toFloat() / maxOf(image.width, image.height)
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val left = index * NOTIFICATION_EMOJI_TILE_PX + (NOTIFICATION_EMOJI_TILE_PX - width) / 2
        val top = (NOTIFICATION_EMOJI_TILE_PX - height) / 2
        canvas.drawBitmap(image, null, Rect(left, top, left + width, top + height), paint)
    }
    return output
}

private suspend fun notificationEmojiBitmap(
    context: Context,
    code: String,
    readBudget: EmojiReadBudget,
): Bitmap? {
    val local = localNotificationEmojiBitmap(context, code, readBudget)
    if (local != null) return local
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

private suspend fun localNotificationEmojiBitmap(
    context: Context,
    code: String,
    readBudget: EmojiReadBudget,
): Bitmap? {
    val directory = File(context.filesDir, CustomEmojiStore.DIRECTORY)
    val localCode = code.trim(':').takeIf { sanitizeEmojiCode(it) == it }
    val candidates =
        if (Files.isSymbolicLink(directory.toPath())) {
            emptyList()
        } else {
            CustomEmojiStore.filesForPresentation(directory)
                .filter { it.nameWithoutExtension == localCode && it.length() <= CustomEmojiStore.MAX_BYTES }
                .filterNot { Files.isSymbolicLink(it.toPath()) }
        }
    for (file in candidates) {
        coroutineContext.ensureActive()
        val bytes = runCatching { readBudget.read(file) }.getOrNull()
        val bitmap = bytes?.takeIf { it.size <= CustomEmojiStore.MAX_BYTES }
            ?.let { runCatching { decodeEmojiImage(it)?.asAndroidBitmap() }.getOrNull() }
        if (bitmap != null) return bitmap
    }
    return null
}

private fun readNotificationEmojiBytes(
    file: File,
    limit: Int,
): ByteArray =
    file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

/** Serialized, size-bounded private export. A full store falls back to text instead of deleting active images. */
private fun writeNotificationEmojiArtifact(
    context: Context,
    image: Bitmap,
): NotificationEmojiArtifact? =
    synchronized(artifactLock) {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY)
        val directoryReady = directory.isDirectory || directory.mkdirs()
        if (!directoryReady || Files.isSymbolicLink(directory.toPath())) return@synchronized null
        val existing = directory.listFiles() ?: return@synchronized null
        if (existing.size >= MAX_ARTIFACTS) return@synchronized null
        val bytes =
            java.io.ByteArrayOutputStream().use { output ->
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) return@synchronized null
                output.toByteArray()
            }
        if (bytes.size > NOTIFICATION_EMOJI_MAX_BYTES) return@synchronized null
        val name = "${UUID.randomUUID()}.png"
        val file = File(directory, name)
        try {
            file.outputStream().use { it.write(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val artifact = NotificationEmojiArtifact(uri, name)
            artifact
        } catch (_: java.io.IOException) {
            file.delete()
            null
        } catch (_: IllegalArgumentException) {
            file.delete()
            null
        }
    }

/** Avoid extra tray IPC when this application has no notification artwork to reconcile. */
internal fun hasNotificationEmojiArtwork(context: Context): Boolean =
    File(context.cacheDir, EXPORT_DIRECTORY).listFiles()?.any { ownedName.matches(it.name) } == true

/** Called only with a confirmed OS snapshot under its existing mutation/visibility gate. */
internal fun pruneNotificationEmojiArtwork(
    context: Context,
    cards: Array<StatusBarNotification>,
) {
    val live = cards.flatMap { notificationEmojiUris(it.notification) }.toSet()
    synchronized(artifactLock) {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY)
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        directory.listFiles().orEmpty().filter { ownedName.matches(it.name) }.forEach { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || NotificationEmojiLeases.contains(file.name)) {
                return@forEach
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            if (uri !in live) {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                file.delete()
            }
        }
    }
}

private fun notificationEmojiUris(notification: Notification): List<Uri> {
    val style =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return emptyList()
    return (style.messages + style.historicMessages).mapNotNull { it.dataUri }
}
