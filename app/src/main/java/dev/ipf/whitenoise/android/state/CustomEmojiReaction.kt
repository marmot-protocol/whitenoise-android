package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.media.readWithin
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The original native source slot of one active reaction, never a lookup by shortcode or target message. */
internal fun AttachmentEntryFfi.reactionRequest(
    account: String,
    group: String,
): AttachmentTransferRequest? =
    (attachment as? MediaAttachmentOutcomeFfi.Accepted)?.let {
        AttachmentTransferRequest(account, group, messageIdHex, it.attachmentIndex.toInt(), sourceMessageIdHex)
    }

/** Reads bounded verified artwork; a disabled automatic policy permits local retention only. */
internal suspend fun WhiteNoiseAppState.reactionArtworkBytes(
    account: String,
    group: String,
    entry: AttachmentEntryFfi,
    allowNetwork: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
): ByteArray? {
    val request = entry.reactionRequest(account, group) ?: return null
    val reference = (entry.attachment as MediaAttachmentOutcomeFfi.Accepted).reference
    val limit = CustomEmojiStore.MAX_BYTES.toLong()
    val source =
        if (allowNetwork) {
            downloadAttachmentPlaintextSource(request, reference, priority, nativeReadBudget = limit)
        } else {
            openNativeAttachment(request, limit)
        }
    return source?.use { it.readWithin(limit) }
}

/** Captures a local definition or bundled PNG before upload; unresolved codes remain ordinary text. */
internal suspend fun WhiteNoiseAppState.localReactionArtwork(emoji: String): LocalEmojiArtwork? {
    val local = localEmojiArtworkIn(emoji).singleOrNull()
    return local ?: EmojiShortcodes.builtinDrawable(emoji)?.let { bundledReactionArtwork(emoji, it) }
}

/** Encodes the bundled painter's cropped pixels so peers receive the same selected artwork. */
private suspend fun WhiteNoiseAppState.bundledReactionArtwork(
    emoji: String,
    drawable: Int,
): LocalEmojiArtwork =
    withContext(Dispatchers.IO) {
        val region = EmojiShortcodes.builtinArtworkRegion(drawable)
        val bytes =
            appContext.resources.openRawResource(drawable).use { input ->
                if (region == null) {
                    input.readBytes()
                } else {
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                    val cropped = Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height)
                    try {
                        ByteArrayOutputStream().use { sink ->
                            check(cropped.compress(Bitmap.CompressFormat.PNG, 100, sink))
                            sink.toByteArray()
                        }
                    } finally {
                        if (cropped !== bitmap) cropped.recycle()
                        bitmap.recycle()
                    }
                }
            }
        check(bytes.size <= CustomEmojiStore.MAX_BYTES)
        LocalEmojiArtwork(emoji, "${emoji.trim(':')}.png", "image/png", bytes)
    }

/** Captures an exact received definition when no local image defines the chosen code. */
internal suspend fun WhiteNoiseAppState.reactionArtworkForSend(
    emoji: String,
    account: String,
    group: String,
    reactionId: String?,
    ensureCurrent: suspend () -> Unit = {},
): LocalEmojiArtwork? {
    ensureCurrent()
    val local = localReactionArtwork(emoji)
    ensureCurrent()
    val entry =
        if (local == null && EmojiShortcodes.isShortcode(emoji) && !reactionId.isNullOrBlank()) {
            marmotIo { reactionAttachment(account, group, reactionId) }
        } else {
            null
        }
    ensureCurrent()
    val reference = (entry?.attachment as? MediaAttachmentOutcomeFfi.Accepted)?.reference
    return when {
        local != null -> local
        entry == null || reference == null -> null
        else -> {
            val bytes =
                requireNotNull(
                    reactionArtworkBytes(account, group, entry, true, AttachmentDownloadPriority.Interactive),
                )
            ensureCurrent()
            LocalEmojiArtwork(emoji, reference.fileName, reference.mediaType, bytes)
        }
    }
}

/**
 * Publishes one kind-7 with its encrypted image definition. Only a proven stale-reference refusal is retried;
 * uncertain publication propagates without creating another event. Captured bytes stay fixed across re-upload.
 */
@Suppress("LongParameterList")
internal suspend fun MarmotInterface.sendCustomEmojiReaction(
    account: String,
    group: String,
    target: String,
    emoji: String,
    artwork: LocalEmojiArtwork?,
    cache: EmojiUploadCache,
    currentEpoch: suspend () -> ULong = { groupMlsState(account, group).epoch },
    ensureCurrent: suspend () -> Unit = {},
    publishLock: EmojiPublishLock = { it() },
): SendSummaryFfi {
    if (artwork == null) {
        return publishLock {
            ensureCurrent()
            reactToMessage(account, group, target, emoji)
        }
    }
    require(artwork.shortcode == emoji) { "reaction artwork does not define the chosen shortcode" }
    precheckEmojiSend(listOf(artwork))
    repeat(2) { attempt ->
        ensureCurrent()
        val references = cache.referencesFor(this, account, group, listOf(artwork), currentEpoch)
        ensureCurrent()
        val tags = emojiTags(listOf(artwork), references)
        checkEmojiTagBytes(tags)
        try {
            return publishLock {
                ensureCurrent()
                reactWithMedia(account, group, target, emoji, tags, listOf(references.getValue(artwork.sha256)))
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            if (failure is CancellationException || !isStaleMediaReferenceEpoch(failure)) throw failure
            cache.invalidate(account, group)
            if (attempt == 1) throw EmojiChatChangedException(failure)
        }
    }
    error("unreachable emoji reaction retry")
}

/** Captures notification artwork under one runtime generation and uses the same locked publisher as conversations. */
internal suspend fun WhiteNoiseAppState.publishNotificationReaction(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
    emoji: String,
): SendSummaryFfi {
    val generation = runtimeGeneration
    val ensureCurrent: suspend () -> Unit = {
        withContext(Dispatchers.Main.immediate) { check(runtimeGeneration == generation && notificationActionsAllowed) }
    }
    ensureCurrent()
    val localArtwork = localReactionArtwork(emoji)
    ensureCurrent()
    val sourceReactionId =
        if (localArtwork == null &&
            dev.ipf.whitenoise.android.ui.EmojiShortcodes
                .isShortcode(emoji)
        ) {
            marmotIo { messageReactions(accountRef, groupIdHex, messageIdHex) }
                .firstOrNull { it.emoji == emoji }
                ?.reactionMessageIdHex
        } else {
            null
        }
    val artwork = localArtwork ?: reactionArtworkForSend(emoji, accountRef, groupIdHex, sourceReactionId, ensureCurrent)
    val summary =
        marmotIo(MarmotTraceSection.MESSAGE_REACT) {
            sendCustomEmojiReaction(
                accountRef,
                groupIdHex,
                messageIdHex,
                emoji,
                artwork,
                emojiUploads,
                ensureCurrent = ensureCurrent,
                publishLock = { publish -> withGroupCommitLock(accountRef, groupIdHex) { publish() } },
            )
        }
    return summary
}
