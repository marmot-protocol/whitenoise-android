package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseIdentity
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** The avatar size for a screen [width]: a fixed share of it, kept within the identity header's bounds. */
internal fun identityAvatarSize(width: Dp): Dp =
    (width * WhiteNoiseIdentity.AVATAR_WIDTH_FRACTION).coerceIn(
        WhiteNoiseIdentity.AvatarMinSize,
        WhiteNoiseIdentity.AvatarMaxSize,
    )

/**
 * The identity block at the top of group details, a person's profile and both edit screens: optional [top] content,
 * the avatar, then [content], centred inside the screen margin with one gap between parts. The avatar size depends
 * only on the screen width, so it does not change when a screen moves between reading and editing.
 *
 * [outerInset] is margin the caller already applied on each side; it is added back so the size rule always sees the
 * screen width. [top] and [avatar] receive the content width and avatar size.
 */
@Suppress("FunctionNaming")
@Composable
internal fun IdentityHeader(
    modifier: Modifier = Modifier,
    outerInset: Dp = 0.dp,
    top: @Composable ColumnScope.(contentWidth: Dp) -> Unit = {},
    avatar: @Composable (size: Dp) -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        val margin = WhiteNoiseSpacing.CompactScreenMargin - outerInset
        val avatarSize = identityAvatarSize(width + outerInset * 2)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = margin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            top(width - margin * 2)
            avatar(avatarSize)
            content()
        }
    }
}

/** The title of a group or person in the identity header: one style and line limit on every screen. */
@Suppress("FunctionNaming")
@Composable
internal fun IdentityName(
    title: String,
    modifier: Modifier = Modifier,
) {
    EmojiLabel(
        title,
        modifier,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = WhiteNoiseIdentity.NAME_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}
