package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
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
        NostrEventCardKind.Generic -> stringResource(R.string.nostr_event_type_generic, eventKind)
    }

@Composable
internal fun eventByline(
    card: NostrEventCardModel,
    authorDisplayName: (String) -> String,
): String {
    val locale = LocalConfiguration.current.locales[0]
    val author =
        authorDisplayName(card.authorPubkeyHex).takeIf(String::isNotBlank)
            ?: IdentityFormatter.short(card.authorPubkeyHex)
    val date =
        runCatching {
            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(Instant.ofEpochSecond(card.createdAt).atZone(ZoneId.systemDefault()))
        }.getOrNull()
    return listOfNotNull(author, date).joinToString(" · ")
}
