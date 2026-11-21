package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DateTimeFormatState
import dev.ipf.whitenoise.android.core.IdentityFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.FormatStyle

@DrawableRes
internal fun NostrEventCardKind.iconRes(eventKind: Int): Int =
    when (this) {
        NostrEventCardKind.Note -> R.drawable.ic_chat
        NostrEventCardKind.Article -> R.drawable.ic_menu_book
        NostrEventCardKind.Video -> R.drawable.ic_videocam
        NostrEventCardKind.Release -> R.drawable.ic_package_2
        NostrEventCardKind.Git -> R.drawable.ic_code
        NostrEventCardKind.File -> R.drawable.ic_description
        NostrEventCardKind.Generic -> if (eventKind == KIND_IMAGE_EVENT) R.drawable.ic_image else R.drawable.ic_category
    }

@Composable
internal fun NostrEventCardKind.label(eventKind: Int): String =
    when (this) {
        NostrEventCardKind.Note -> stringResource(R.string.nostr_event_type_note)
        NostrEventCardKind.Article -> stringResource(R.string.nostr_event_type_article)
        NostrEventCardKind.Video -> stringResource(R.string.nostr_event_type_video)
        NostrEventCardKind.Release -> stringResource(R.string.nostr_event_type_release)
        NostrEventCardKind.File -> stringResource(R.string.nostr_event_type_file)
        NostrEventCardKind.Git -> stringResource(gitCardLabel(eventKind))
        NostrEventCardKind.Generic ->
            if (eventKind == KIND_IMAGE_EVENT) {
                stringResource(R.string.nostr_event_type_image)
            } else {
                stringResource(R.string.nostr_event_type_generic, eventKind)
            }
    }

private const val KIND_IMAGE_EVENT = 20

/** The author and the one date of [card], an article's publication date when it has a valid one (#3317). */
@Composable
internal fun eventByline(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
): String {
    val date = eventDateLabel(card.displayEpochSeconds())
    return listOf(eventAuthorLabel(card, authorDisplayName), date).joinToString(" · ")
}

internal fun eventAuthorLabel(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
): String =
    card.authorMetadata?.displayName
        ?: authorDisplayName(card.authorPubkeyHex).takeIf(String::isNotBlank)
        ?: IdentityFormatter.short(card.authorPubkeyHex)

@Composable
internal fun eventDateLabel(createdAt: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateTimeFormatState.dateFormat
    return runCatching {
        val date = Instant.ofEpochSecond(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        IdentityFormatter.date(date, locale, dateFormat, FormatStyle.MEDIUM)
    }.getOrDefault("")
}

@Composable
internal fun NostrEventCardModel.openDescription(): String =
    stringResource(
        if (kind == NostrEventCardKind.Article && !readerBody.isNullOrBlank()) {
            R.string.nostr_event_read_article
        } else {
            R.string.nostr_event_expand
        },
    )
