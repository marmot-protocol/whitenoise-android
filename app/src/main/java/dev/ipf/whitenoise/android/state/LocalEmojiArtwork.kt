package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One of the user's own emoji images, read from its device-local file and ready to upload. */
internal class LocalEmojiArtwork(
    val shortcode: String,
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
)

private val EMOJI_MEDIA_TYPES =
    mapOf("png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "jpg" to "image/jpeg")

/**
 * The user's emoji files for [shortcodes] in [directory], in the order asked. A shortcode without
 * a readable, supported image file is left out, so it stays literal text instead of failing a send.
 */
internal fun readLocalEmojiArtwork(
    directory: File,
    shortcodes: List<String>,
): List<LocalEmojiArtwork> {
    val files = directory.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith('.') }
    return shortcodes.mapNotNull { shortcode ->
        val code = shortcode.trim(':')
        val file = files.firstOrNull { it.nameWithoutExtension == code } ?: return@mapNotNull null
        val mediaType = EMOJI_MEDIA_TYPES[file.extension.lowercase(Locale.ROOT)] ?: return@mapNotNull null
        if (file.length() > CustomEmojiStore.MAX_BYTES) return@mapNotNull null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
        LocalEmojiArtwork(shortcode, file.name, mediaType, bytes)
    }
}

/** The user's own emoji that [text] uses, read off the main thread; empty when it uses none. */
internal suspend fun WhiteNoiseAppState.localEmojiArtworkIn(text: String): List<LocalEmojiArtwork> {
    val shortcodes = Nip30Emoji.shortcodesIn(text)
    if (shortcodes.isEmpty()) return emptyList()
    return withContext(Dispatchers.IO) {
        readLocalEmojiArtwork(File(appContext.filesDir, CustomEmojiStore.DIRECTORY), shortcodes)
    }
}

/**
 * Encrypts and uploads [artwork] without sending anything, returning one reference per image in
 * order. Upload-only (`send = false`), so the references can be named by the message or reaction
 * tags that MDK validates when it publishes them.
 */
internal suspend fun MarmotInterface.uploadEmojiArtwork(
    account: String,
    group: String,
    artwork: List<LocalEmojiArtwork>,
): List<MediaAttachmentReferenceFfi> {
    val request =
        MediaUploadRequestFfi(
            attachments =
                artwork.map {
                    MediaUploadAttachmentRequestFfi(it.fileName, it.mediaType, it.bytes, null, null)
                },
            caption = null,
            send = false,
            blossomServer = null,
        )
    val references = uploadMedia(account, group, request).attachments.map { it.reference }
    check(references.size == artwork.size) { "emoji upload returned ${references.size} of ${artwork.size} images" }
    return references
}

/** `["emoji", code, url]` rows naming each uploaded image by its first locator, in upload order. */
internal fun emojiTags(
    artwork: List<LocalEmojiArtwork>,
    references: List<MediaAttachmentReferenceFfi>,
): List<List<String>> =
    artwork.zip(references).map { (emoji, reference) ->
        Nip30Emoji.tag(emoji.shortcode, reference.locators.first().value)
    }

/**
 * Sends [text] as a chat message carrying the images of the emoji it uses: upload-only first,
 * then one tagged media send whose caption is the text.
 */
internal suspend fun MarmotInterface.sendTextWithCustomEmoji(
    account: String,
    group: String,
    text: String,
    artwork: List<LocalEmojiArtwork>,
): SendSummaryFfi {
    val references = uploadEmojiArtwork(account, group, artwork)
    return sendTaggedMedia(account, group, references, text, emojiTags(artwork, references))
}
