package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent

/** Maps a verified Nostr event into the bounded presentation model used by cards and readers. */
internal fun NostrEvent.toCardModel(): NostrEventCardModel {
    val cardKind = cardKind()
    val videoMetadata = takeIf { cardKind == NostrEventCardKind.Video }?.videoMetadata()
    val tagTitle = firstSafeTag("title") ?: firstSafeTag("name")
    val tagSummary = firstSafeExcerptTag("summary") ?: firstSafeExcerptTag("description")
    return NostrEventCardModel(
        kind = cardKind,
        eventIdHex = id,
        authorPubkeyHex = pubkey,
        createdAt = createdAt,
        eventKind = kind,
        title = cardTitle(cardKind, tagTitle)?.safeField(),
        summary = cardSummary(cardKind, tagSummary).safeExcerpt(),
        metadata = cardMetadata(cardKind, videoMetadata).map(String::safeField),
        readerBody =
            content
                .safeReaderBody()
                .takeIf {
                    (cardKind == NostrEventCardKind.Note || cardKind == NostrEventCardKind.Article) && it.isNotBlank()
                },
        mediaUrl = videoMetadata?.url,
        mediaMimeType = videoMetadata?.mimeType,
    )
}

private fun NostrEvent.cardKind(): NostrEventCardKind =
    when (kind) {
        KIND_TEXT_NOTE -> NostrEventCardKind.Note
        KIND_LONG_FORM_CONTENT -> NostrEventCardKind.Article
        KIND_VIDEO,
        KIND_SHORT_VIDEO,
        KIND_VERTICAL_VIDEO,
        KIND_HORIZONTAL_VIDEO,
        -> NostrEventCardKind.Video
        KIND_SOFTWARE_RELEASE -> NostrEventCardKind.Release
        KIND_FILE_METADATA -> NostrEventCardKind.File
        else -> NostrEventCardKind.Generic
    }

private fun NostrEvent.cardTitle(
    cardKind: NostrEventCardKind,
    tagTitle: String?,
): String? =
    when (cardKind) {
        NostrEventCardKind.Note -> null
        NostrEventCardKind.Article,
        NostrEventCardKind.Video,
        NostrEventCardKind.Generic,
        -> tagTitle
        NostrEventCardKind.Release -> tagTitle ?: firstSafeTag("i")
        NostrEventCardKind.File -> tagTitle ?: firstSafeTag("filename")
    }

private fun NostrEvent.cardSummary(
    cardKind: NostrEventCardKind,
    tagSummary: String?,
): String =
    if (cardKind == NostrEventCardKind.Release) {
        firstSafeExcerptTag("changelog") ?: tagSummary ?: content.safeExcerpt()
    } else {
        tagSummary ?: content.safeExcerpt()
    }

private fun NostrEvent.cardMetadata(
    cardKind: NostrEventCardKind,
    videoMetadata: NostrEventVideoMetadata?,
): List<String> =
    when (cardKind) {
        NostrEventCardKind.Article -> listOfNotNull(firstSafeTag("published_at")?.asEpochLabel())
        NostrEventCardKind.Video ->
            listOfNotNull(
                (videoMetadata?.duration ?: firstSafeTag("duration"))?.asDurationLabel(),
                videoMetadata?.dimensions ?: firstSafeTag("dim"),
            )
        NostrEventCardKind.Release -> listOfNotNull(firstSafeTag("version"))
        NostrEventCardKind.File ->
            listOfNotNull(firstSafeTag("m"), firstSafeTag("size")?.asByteCountLabel())
        NostrEventCardKind.Note,
        NostrEventCardKind.Generic,
        -> emptyList()
    }

@Suppress("MaxLineLength")
private fun NostrEvent.firstSafeTag(name: String): String? = firstTagValue(name)?.safeField()?.takeIf(String::isNotBlank)

@Suppress("MaxLineLength")
private fun NostrEvent.firstSafeExcerptTag(name: String): String? = firstTagValue(name)?.safeExcerpt()?.takeIf(String::isNotBlank)

private const val KIND_TEXT_NOTE = 1
private const val KIND_VIDEO = 21
private const val KIND_SHORT_VIDEO = 22
private const val KIND_FILE_METADATA = 1_063
private const val KIND_LONG_FORM_CONTENT = 30_023
private const val KIND_SOFTWARE_RELEASE = 30_063
private const val KIND_VERTICAL_VIDEO = 34_235
private const val KIND_HORIZONTAL_VIDEO = 34_236
