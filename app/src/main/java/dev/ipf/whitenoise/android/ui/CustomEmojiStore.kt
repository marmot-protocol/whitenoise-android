package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * One user-defined emoji: `:code:`, the file it lives in, and its decoded artwork. A file saved by an
 * earlier version in a format that cannot be sent (`<code>.img`) is not [sendable]: it still renders
 * and can be removed, but it is never offered for composing and never uploaded.
 */
@Immutable
internal class CustomEmoji(
    val shortcode: String,
    val file: File,
    val image: ImageBitmap,
) {
    val art = EmojiArt.Decoded(image)

    /** Whether the file has an extension the sender can upload. */
    val sendable: Boolean = CustomEmojiStore.hasSendableExtension(file)
}

/** The user's emoji in shortcode order. */
@Immutable
internal class CustomEmojiSet(
    val entries: List<CustomEmoji>,
) {
    private val byShortcode = entries.associateBy { it.shortcode }

    /** The emoji that can be composed and sent, leaving out render-only legacy files. */
    val sendable: List<CustomEmoji> = entries.filter { it.sendable }

    /** Any emoji with this code, sendable or not, so existing artwork keeps rendering. */
    operator fun get(shortcode: String): CustomEmoji? = byShortcode[shortcode]

    companion object {
        val Empty = CustomEmojiSet(emptyList())
    }
}

internal enum class CustomEmojiSaveResult { Saved, InvalidName, TooLarge, NotAnImage, Failed }

/**
 * NIP-30's shortcode alphabet: lowercase letters, digits and `_`. `-` becomes `_` and anything
 * else is dropped, which also keeps the code safe as a filename.
 */
internal fun sanitizeEmojiCode(raw: String): String =
    buildString {
        for (c in raw.lowercase(Locale.ROOT)) {
            when (c) {
                in 'a'..'z', in '0'..'9', '_' -> append(c)
                '-' -> append('_')
            }
        }
    }.take(EmojiShortcodes.MAX_CODE_LENGTH)

/** The prefilled code for a picked file: its name without the extension, sanitized. */
internal fun emojiCodeForFileName(name: String): String = sanitizeEmojiCode(name.substringBeforeLast('.'))

/**
 * User emoji as device-local files `emoji/<code>.<ext>` under the app's files directory. The
 * filename stem is the shortcode, so the directory is the whole map. These are the user's own
 * source images, never received protocol data.
 */
internal class CustomEmojiStore(
    private val directory: File,
) {
    private val mutex = Mutex()
    private var loaded = false

    var emoji: CustomEmojiSet by mutableStateOf(CustomEmojiSet.Empty)
        private set

    /** Reads the directory once per process. */
    suspend fun load() {
        mutex.withLock {
            if (loaded) {
                return
            }
            emoji = withContext(Dispatchers.IO) { scan() }
            loaded = true
        }
    }

    /** Stores [bytes] as `:code:`, replacing an emoji of the same code. */
    suspend fun save(
        code: String,
        bytes: ByteArray,
    ): CustomEmojiSaveResult {
        val rejected =
            when {
                code.isEmpty() || sanitizeEmojiCode(code) != code -> CustomEmojiSaveResult.InvalidName
                bytes.size > MAX_BYTES -> CustomEmojiSaveResult.TooLarge
                else -> null
            }
        return rejected ?: mutex.withLock { withContext(Dispatchers.IO) { store(code, bytes) } }
    }

    /** Deletes every file behind [shortcode], including a legacy file a sendable one shadows. */
    suspend fun remove(shortcode: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                filesFor(shortcode.trim(':')).forEach(File::delete)
                emoji = scan()
                loaded = true
            }
        }
    }

    private fun store(
        code: String,
        bytes: ByteArray,
    ): CustomEmojiSaveResult {
        val extension = imageExtension(bytes) ?: return CustomEmojiSaveResult.NotAnImage
        val written =
            try {
                write(code, extension, bytes)
                true
            } catch (_: IOException) {
                false
            }
        emoji = scan()
        loaded = true
        return if (written) CustomEmojiSaveResult.Saved else CustomEmojiSaveResult.Failed
    }

    private fun write(
        code: String,
        extension: String,
        bytes: ByteArray,
    ) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("cannot create $directory")
        }
        val staged = File(directory, ".$code.tmp")
        staged.writeBytes(bytes)

        // Install first so a failed rename keeps the previous emoji.
        val target = File(directory, "$code.$extension")
        val previous = filesFor(code).filter { it != target }
        if (!staged.renameTo(target)) {
            staged.delete()
            throw IOException("cannot store $code")
        }
        previous.forEach(File::delete)
    }

    private fun filesFor(code: String): List<File> {
        val files = directory.listFiles().orEmpty()
        return files.filter { it.isFile && it.nameWithoutExtension == code }
    }

    /**
     * Every decodable emoji file, legacy formats included so they stay visible and removable. A sendable
     * file sorts ahead of a legacy one with the same code, so the legacy file never shadows it.
     */
    private fun scan(): CustomEmojiSet {
        val entries =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.isFile && !it.name.startsWith('.') }
                .sortedWith(compareBy({ !hasSendableExtension(it) }, { it.name }))
                .mapNotNull { file ->
                    val code = file.name.substringBeforeLast('.')
                    if (code.isEmpty() || sanitizeEmojiCode(code) != code || file.length() > MAX_BYTES) {
                        return@mapNotNull null
                    }
                    val image = decodeEmojiImage(file.readBytes()) ?: return@mapNotNull null
                    CustomEmoji(":$code:", file, image)
                }.distinctBy { it.shortcode }
                .sortedBy { it.shortcode }
        return CustomEmojiSet(entries)
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val DIRECTORY = "emoji"

        /** Whether [file] has an emoji image extension, ignoring case like the sender does. */
        fun hasSendableExtension(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in SENDABLE_EXTENSIONS

        /** File extensions of emoji images that can be sent, so every listed emoji is sendable. */
        val SENDABLE_EXTENSIONS = setOf("png", "gif", "webp", "jpg")
    }
}

private const val EMOJI_DECODE_PX = 128

/**
 * Emoji artwork downsampled so its longer edge is under twice [EMOJI_DECODE_PX]; null when not an image.
 * Sampling by the longer edge bounds memory for extreme aspect ratios (e.g. 100000x255).
 */
internal fun decodeEmojiImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    var sample = 1
    val longerEdge = maxOf(bounds.outWidth, bounds.outHeight)
    while (longerEdge / (sample * 2) >= EMOJI_DECODE_PX) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** File extension for a decodable image, from its content rather than its name. */
private fun imageExtension(bytes: ByteArray): String? {
    if (decodeEmojiImage(bytes) == null) {
        return null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return when (bounds.outMimeType) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/jpeg" -> "jpg"
        // Other decodable formats cannot be sent as emoji, so they are refused instead of kept as literal-only.
        else -> null
    }
}

/** The process-wide store, loaded on first use. */
@Composable
internal fun rememberCustomEmojiStore(context: Context = LocalContext.current): CustomEmojiStore {
    val application = context.applicationContext as WhiteNoiseApplication
    val store = remember(application) { application.customEmojiStore }
    LaunchedEffect(store) {
        store.load()
    }
    return store
}
