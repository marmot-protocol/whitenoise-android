package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.outlined.Article
import dev.ipf.whitenoise.android.ui.icons.outlined.ChatBubbleOutline
import dev.ipf.whitenoise.android.ui.icons.outlined.Description
import dev.ipf.whitenoise.android.ui.icons.outlined.Fullscreen
import dev.ipf.whitenoise.android.ui.icons.outlined.NewReleases
import dev.ipf.whitenoise.android.ui.icons.outlined.PlayCircleOutline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun NostrEventCardKind.icon(): ImageVector =
    when (this) {
        NostrEventCardKind.Note -> Icons.Outlined.ChatBubbleOutline
        NostrEventCardKind.Article -> Icons.AutoMirrored.Outlined.Article
        NostrEventCardKind.Video -> Icons.Outlined.PlayCircleOutline
        NostrEventCardKind.Release -> Icons.Outlined.NewReleases
        NostrEventCardKind.File,
        NostrEventCardKind.Generic,
        -> Icons.Outlined.Description
    }

@Composable
internal fun NostrEventCardKind.label(eventKind: Int): String =
    when (this) {
        NostrEventCardKind.Note -> stringResource(R.string.nostr_event_type_note)
        NostrEventCardKind.Article -> stringResource(R.string.nostr_event_type_article)
        NostrEventCardKind.Video -> stringResource(R.string.nostr_event_type_video)
        NostrEventCardKind.Release -> stringResource(R.string.nostr_event_type_release)
        NostrEventCardKind.File -> stringResource(R.string.nostr_event_type_file)
        NostrEventCardKind.Generic ->
            if (eventKind == KIND_IMAGE_EVENT) {
                stringResource(R.string.nostr_event_type_image)
            } else {
                stringResource(R.string.nostr_event_type_generic, eventKind)
            }
    }

private const val KIND_IMAGE_EVENT = 20

@Composable
internal fun eventByline(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
): String = listOf(eventAuthorLabel(card, authorDisplayName), eventDateLabel(card.createdAt)).joinToString(" · ")

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
    return runCatching {
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(Instant.ofEpochSecond(createdAt).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
}

internal data class EventCardPrimaryAction(
    val icon: ImageVector,
    val description: String,
)

@Composable
internal fun NostrEventCardModel.primaryAction(): EventCardPrimaryAction =
    when {
        kind == NostrEventCardKind.Article && !readerBody.isNullOrBlank() ->
            EventCardPrimaryAction(
                icon = Icons.AutoMirrored.Outlined.Article,
                description = stringResource(R.string.nostr_event_read_article),
            )
        else ->
            EventCardPrimaryAction(
                icon = Icons.Outlined.Fullscreen,
                description = stringResource(R.string.nostr_event_expand),
            )
    }
