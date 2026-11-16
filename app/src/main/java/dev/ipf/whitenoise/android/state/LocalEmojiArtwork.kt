package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Original emoji artwork prepared for upload with its filename and media metadata. */
class LocalEmojiArtwork(
    val shortcode: String,
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
    val dim: String? = null,
    val thumbhash: String? = null,
) {
    /** Lowercase hex SHA-256 of the image bytes, the identity of an upload. */
    val sha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

// Keep the aggregate at the existing per-message artwork bound even with larger installed images.
internal const val EMOJI_SEND_MAX_BYTES = 67_108_864uL

internal val EMOJI_MEDIA_TYPES =
    mapOf("png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "jpg" to "image/jpeg")

/**
 * The user's emoji files for [shortcodes] in [directory], in the order asked. A shortcode without
 * a readable, supported, sendable image file is left out, so it stays literal text instead of
 * failing a send. When several sendable files share a code, the first by name that is within
 * [CustomEmojiStore.MAX_BYTES] and readable wins, the same fall-through the picker applies.
 */
internal fun readLocalEmojiArtwork(
    directory: File,
    shortcodes: List<String>,
): List<LocalEmojiArtwork> {
    // The same extension rule as the picker, so a legacy party.img can never shadow party.png.
    val files =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.startsWith('.') && CustomEmojiStore.hasSendableExtension(it) }
            .sortedBy { it.name }
    return shortcodes.mapNotNull { shortcode ->
        if (!Nip30Emoji.isSendable(shortcode)) return@mapNotNull null
        val code = shortcode.trim(':')
        files
            .filter { it.nameWithoutExtension == code && it.length() <= CustomEmojiStore.MAX_BYTES }
            .firstNotNullOfOrNull { file -> readEmojiFile(shortcode, file) }
    }
}

/** [file] as sendable artwork for [shortcode], or null when its type is unknown or it cannot be read. */
private fun readEmojiFile(
    shortcode: String,
    file: File,
): LocalEmojiArtwork? {
    val mediaType = EMOJI_MEDIA_TYPES[file.extension.lowercase(Locale.ROOT)]
    val bytes = if (mediaType == null) null else runCatching { file.readBytes() }.getOrNull()
    return if (mediaType == null || bytes == null) null else LocalEmojiArtwork(shortcode, file.name, mediaType, bytes)
}

/** Resolves bundled, device-local and MDK-installed artwork for the captured sending account. */
internal suspend fun WhiteNoiseAppState.emojiArtworkIn(
    account: String,
    text: String,
): List<LocalEmojiArtwork> {
    val shortcodes = Nip30Emoji.shortcodesIn(text)
    if (shortcodes.isEmpty()) return emptyList()
    val local =
        withContext(Dispatchers.IO) {
            val bundled = readBuiltinEmojiArtwork(appContext, shortcodes).associateBy { it.shortcode }
            val deviceLocal = readLocalEmojiArtwork(File(appContext.filesDir, CustomEmojiStore.DIRECTORY), shortcodes)
            (deviceLocal.associateBy { it.shortcode } + bundled).values.toList()
        }.associateBy { it.shortcode }
    val missing = shortcodes.filter { it !in local && it !in EmojiShortcodes.builtins }
    return if (missing.isEmpty()) {
        shortcodes.mapNotNull(local::get)
    } else {
        marmotIo {
            val installed = installedEmojiByCode(installedCustomEmoji(account))
            val resolved = shortcodes.filter { it in local || it in installed }
            val bytes =
                resolved.sumOf { code -> local[code]?.bytes?.size?.toULong() ?: installed.getValue(code).byteLen }
            if (resolved.size > Nip30Emoji.MAX_TAGS || bytes > EMOJI_SEND_MAX_BYTES) {
                throw EmojiSendLimitException("too much custom emoji artwork for one message")
            }
            resolved.map { code ->
                local[code] ?: readInstalledEmojiArtwork(account, code, installed.getValue(code))
            }
        }
    }
}

/**
 * Composer messages and replies carry artwork unless a test or host injects its own text publisher.
 */
internal fun emojiSendApplies(customPublisherInjected: Boolean): Boolean = !customPublisherInjected
