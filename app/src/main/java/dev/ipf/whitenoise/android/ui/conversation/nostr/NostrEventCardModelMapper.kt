package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun NostrEvent.toCardModel(): NostrEventCardModel {
    val cardKind = cardKind()
    val tagTitle = firstSafeTag("title") ?: firstSafeTag("name")
    val tagSummary = firstSafeTag("summary") ?: firstSafeTag("description")
    return NostrEventCardModel(
        kind = cardKind,
        eventIdHex = id,
        authorPubkeyHex = pubkey,
        createdAt = createdAt,
        eventKind = kind,
        title = cardTitle(cardKind, tagTitle)?.safeField(),
        summary = cardSummary(cardKind, tagSummary).safeExcerpt(),
        metadata = cardMetadata(cardKind).map(String::safeField),
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
        firstSafeTag("changelog") ?: tagSummary ?: content.safeExcerpt()
    } else {
        tagSummary ?: content.safeExcerpt()
    }

private fun NostrEvent.cardMetadata(cardKind: NostrEventCardKind): List<String> =
    when (cardKind) {
        NostrEventCardKind.Article -> listOfNotNull(firstSafeTag("published_at")?.asEpochLabel())
        NostrEventCardKind.Video ->
            listOfNotNull(firstSafeTag("duration")?.asDurationLabel(), firstSafeTag("dim"))
        NostrEventCardKind.Release -> listOfNotNull(firstSafeTag("version"))
        NostrEventCardKind.File ->
            listOfNotNull(firstSafeTag("m"), firstSafeTag("size")?.asByteCountLabel())
        NostrEventCardKind.Note,
        NostrEventCardKind.Generic,
        -> emptyList()
    }

@Suppress("MaxLineLength")
private fun NostrEvent.firstSafeTag(name: String): String? = firstTagValue(name)?.safeField()?.takeIf(String::isNotBlank)

private fun String.safeField(): String =
    filterNot { it == '\u0000' || (it.isISOControl() && !it.isWhitespace()) }
        .trim()
        .take(MAX_FIELD_CHARS)

private fun String.safeExcerpt(): String = safeField().take(MAX_EXCERPT_CHARS)

private fun String.asDurationLabel(): String? {
    val seconds = toLongOrNull()?.takeIf { it in 0..MAX_DURATION_SECONDS } ?: return null
    val minutes = seconds / SECONDS_PER_MINUTE
    val remainder = seconds % SECONDS_PER_MINUTE
    return if (minutes == 0L) "${remainder}s" else "$minutes:${remainder.toString().padStart(2, '0')}"
}

private fun String.asByteCountLabel(): String? {
    val bytes = toLongOrNull()?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null
    return when {
        bytes >= BYTES_PER_MEBIBYTE ->
            String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MEBIBYTE.toDouble())
        bytes >= BYTES_PER_KIBIBYTE ->
            String.format(Locale.ROOT, "%.1f KB", bytes / BYTES_PER_KIBIBYTE.toDouble())
        else -> "$bytes B"
    }
}

private fun String.asEpochLabel(): String? =
    toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { seconds ->
            runCatching {
                DateTimeFormatter.ISO_LOCAL_DATE.format(
                    Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC),
                )
            }.getOrNull()
        }

private const val MAX_FIELD_CHARS = 160
private const val MAX_EXCERPT_CHARS = 420
private const val KIND_TEXT_NOTE = 1
private const val KIND_VIDEO = 21
private const val KIND_SHORT_VIDEO = 22
private const val KIND_FILE_METADATA = 1_063
private const val KIND_LONG_FORM_CONTENT = 30_023
private const val KIND_SOFTWARE_RELEASE = 30_063
private const val KIND_VERTICAL_VIDEO = 34_235
private const val KIND_HORIZONTAL_VIDEO = 34_236
private const val SECONDS_PER_MINUTE = 60L
private const val BYTES_PER_KIBIBYTE = 1_024L
private const val BYTES_PER_MEBIBYTE = BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE
private const val MAX_DURATION_SECONDS = 7 * 24 * SECONDS_PER_MINUTE * SECONDS_PER_MINUTE
private const val MAX_FILE_BYTES = 16L * BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE
