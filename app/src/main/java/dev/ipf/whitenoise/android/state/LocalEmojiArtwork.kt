package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One of the user's own emoji images, read from its device-local file and ready to upload. */
class LocalEmojiArtwork(
    val shortcode: String,
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
) {
    /** Lowercase hex SHA-256 of the image bytes, the identity of an upload. */
    val sha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private val EMOJI_MEDIA_TYPES =
    mapOf("png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "jpg" to "image/jpeg")

/**
 * The user's emoji files for [shortcodes] in [directory], in the order asked. A shortcode without
 * a readable, supported, sendable image file is left out, so it stays literal text instead of
 * failing a send.
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
        if (!Nip30Emoji.isSendable(shortcode) || file.length() > CustomEmojiStore.MAX_BYTES) {
            return@mapNotNull null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
        LocalEmojiArtwork(shortcode, file.name, mediaType, bytes)
    }
}

/** The user's own emoji that [text] uses, read off the main thread, or empty when it uses none. */
internal suspend fun WhiteNoiseAppState.localEmojiArtworkIn(text: String): List<LocalEmojiArtwork> {
    val shortcodes = Nip30Emoji.shortcodesIn(text)
    if (shortcodes.isEmpty()) return emptyList()
    return withContext(Dispatchers.IO) {
        readLocalEmojiArtwork(File(appContext.filesDir, CustomEmojiStore.DIRECTORY), shortcodes)
    }
}

/**
 * Which emoji a send carries: only a plain, non-reply text send does, and only when the app did not
 * inject its own publisher. Replies and injected publishers send plain text.
 */
internal fun emojiSendApplies(
    replyTarget: String?,
    customPublisherInjected: Boolean,
): Boolean = replyTarget == null && !customPublisherInjected

/**
 * Refuses a send that MDK would reject for its tag rows, before any image is uploaded. The row
 * count is one emoji tag per shortcode. The byte limit is checked again once the URLs are known.
 */
internal fun precheckEmojiSend(artwork: List<LocalEmojiArtwork>) {
    if (artwork.size > Nip30Emoji.MAX_TAGS) {
        throw EmojiSendLimitException("too many custom emoji: ${artwork.size} of ${Nip30Emoji.MAX_TAGS}")
    }
    artwork.firstOrNull { !Nip30Emoji.isSendable(it.shortcode) }?.let {
        throw EmojiSendLimitException("shortcode cannot be sent as an emoji tag")
    }
}

/** Refuses tag rows whose combined values exceed MDK's byte limit, before they are sent. */
internal fun checkEmojiTagBytes(tags: List<List<String>>) {
    val bytes = tags.sumOf { row -> row.sumOf { it.toByteArray(Charsets.UTF_8).size } }
    if (bytes > Nip30Emoji.MAX_TAG_VALUE_BYTES) {
        throw EmojiSendLimitException("emoji tag values are $bytes bytes of ${Nip30Emoji.MAX_TAG_VALUE_BYTES}")
    }
}

private data class EmojiUploadKey(
    val account: String,
    val group: String,
    val plaintextSha256: String,
    val epoch: ULong,
)

/**
 * Uploaded emoji references, reused while the group's epoch is the one they were encrypted under.
 * One lock per (account, group) serializes that chat's uploads, so a retry or a concurrent send
 * waits for the in-flight upload and then reuses it instead of uploading the same image twice,
 * while other chats upload independently. Only references live here, never plaintext, and a
 * rejected reference is dropped with [invalidate].
 */
internal class EmojiUploadCache {
    private val locks = HashMap<Pair<String, String>, Mutex>()
    private val references = LinkedHashMap<EmojiUploadKey, MediaAttachmentReferenceFfi>()

    /** The lock serializing uploads for one chat. */
    private fun lockFor(
        account: String,
        group: String,
    ): Mutex = synchronized(locks) { locks.getOrPut(account to group) { Mutex() } }

    /** One reference per distinct image in [artwork], uploading only those not already cached. */
    suspend fun referencesFor(
        engine: MarmotInterface,
        account: String,
        group: String,
        artwork: List<LocalEmojiArtwork>,
        currentEpoch: suspend () -> ULong,
    ): Map<String, MediaAttachmentReferenceFfi> =
        lockFor(account, group).withLock {
            val epoch = currentEpoch()

            fun key(emoji: LocalEmojiArtwork) = EmojiUploadKey(account, group, emoji.sha256, epoch)
            val distinct = artwork.distinctBy { it.sha256 }
            val missing = synchronized(references) { distinct.filter { key(it) !in references } }
            val uploaded = if (missing.isEmpty()) emptyList() else engine.uploadEmojiArtwork(account, group, missing)
            synchronized(references) {
                missing.zip(uploaded).forEach { (emoji, reference) -> references[key(emoji)] = reference }
                // Build the result first, then evict, so eviction can never remove a reference this send needs.
                val result = artwork.associate { it.sha256 to references.getValue(key(it)) }
                // Touch the batch so it is the newest, then drop the oldest entries beyond the limit.
                result.forEach { (sha, reference) ->
                    val touched = EmojiUploadKey(account, group, sha, epoch)
                    references.remove(touched)
                    references[touched] = reference
                }
                while (references.size > MAX_ENTRIES) references.remove(references.keys.first())
                result
            }
        }

    /** Forgets every reference for [account] and [group], so the next send re-encrypts under the current epoch. */
    suspend fun invalidate(
        account: String,
        group: String,
    ) {
        lockFor(account, group).withLock {
            synchronized(references) { references.keys.removeAll { it.account == account && it.group == group } }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}

/**
 * Encrypts and uploads [artwork] without sending anything, returning one reference per image in
 * order. Upload-only (`send = false`), so the references can be named by the message tags that
 * MDK validates when it publishes them.
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

/**
 * `["emoji", code, url]` rows naming each emoji by the first locator of its image, in text order.
 * Aliases of one image share a reference, so [referenceBySha] is keyed by image digest.
 */
internal fun emojiTags(
    artwork: List<LocalEmojiArtwork>,
    referenceBySha: Map<String, MediaAttachmentReferenceFfi>,
): List<List<String>> =
    artwork.map { emoji ->
        val url =
            referenceBySha
                .getValue(emoji.sha256)
                .locators
                .first()
                .value
        check(url.isNotBlank()) { "uploaded emoji has no locator" }
        Nip30Emoji.tag(emoji.shortcode, url)
    }

/** Test seam for the emoji send: account, text, emoji, the scope check, then the lock around the publish. */
typealias CustomEmojiSender =
    suspend (String, String, List<LocalEmojiArtwork>, suspend () -> Unit, EmojiPublishLock) -> SendSummaryFfi

/** Runs the given publish while holding whatever lock serializes group commits. */
typealias EmojiPublishLock = suspend (suspend () -> SendSummaryFfi) -> SendSummaryFfi

/**
 * Uploads (or reuses) the images, wrapping any failure as [EmojiUploadFailure] because nothing has
 * been published at that point. Cancellation passes through.
 */
private suspend fun MarmotInterface.uploadStage(
    cache: EmojiUploadCache,
    account: String,
    group: String,
    artwork: List<LocalEmojiArtwork>,
    currentEpoch: suspend () -> ULong,
): Map<String, MediaAttachmentReferenceFfi> =
    try {
        cache.referencesFor(this, account, group, artwork, currentEpoch)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
        throw EmojiUploadFailure(failure)
    }

/**
 * One upload-then-publish pass. The upload and tag checks run outside any lock, so a slow upload
 * never blocks cancel or other mutations. Only the publish runs inside [publishLock], after
 * [ensureCurrent] confirms again that the send was not cancelled. A reference MDK rejects drops
 * the cache and surfaces as [EmojiChatChangedException], and any other publish failure
 * propagates unchanged.
 */
@Suppress("LongParameterList")
private suspend fun MarmotInterface.attemptEmojiSend(
    account: String,
    group: String,
    text: String,
    artwork: List<LocalEmojiArtwork>,
    cache: EmojiUploadCache,
    currentEpoch: suspend () -> ULong,
    ensureCurrent: suspend () -> Unit,
    publishLock: EmojiPublishLock,
): SendSummaryFfi {
    val referenceBySha = uploadStage(cache, account, group, artwork, currentEpoch)
    ensureCurrent()
    val tags = emojiTags(artwork, referenceBySha)
    checkEmojiTagBytes(tags)
    val attachments = artwork.map { it.sha256 }.distinct().map { referenceBySha.getValue(it) }
    return try {
        publishLock {
            ensureCurrent()
            sendTaggedMedia(account, group, attachments, text, tags)
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
        if (failure is CancellationException || !isStaleEmojiReference(failure)) throw failure
        cache.invalidate(account, group)
        throw EmojiChatChangedException(failure)
    }
}

/**
 * Sends [text] as a chat message carrying the images of the emoji it uses: upload-only first, then
 * one tagged media send whose caption is the text. Limits are checked before any upload. An upload
 * failure is an [EmojiUploadFailure] (nothing published). A reference MDK rejects is re-uploaded
 * once, then reported as [EmojiChatChangedException]. [ensureCurrent] runs after the upload and
 * throws when the account, chat or send was cancelled meanwhile, and runs again inside [publishLock].
 * A failure of the send itself
 * propagates unchanged, because the event may have reached a relay.
 */
@Suppress("LongParameterList")
internal suspend fun MarmotInterface.sendTextWithCustomEmoji(
    account: String,
    group: String,
    text: String,
    artwork: List<LocalEmojiArtwork>,
    cache: EmojiUploadCache,
    currentEpoch: suspend () -> ULong = { groupMlsState(account, group).epoch },
    ensureCurrent: suspend () -> Unit = {},
    publishLock: EmojiPublishLock = { it() },
): SendSummaryFfi {
    precheckEmojiSend(artwork)
    var attempt = 1
    while (true) {
        try {
            return attemptEmojiSend(account, group, text, artwork, cache, currentEpoch, ensureCurrent, publishLock)
        } catch (stale: EmojiChatChangedException) {
            if (attempt++ >= 2) throw stale
        }
    }
}
