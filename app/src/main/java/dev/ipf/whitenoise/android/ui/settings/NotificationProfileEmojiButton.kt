package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

private val EmojiButtonSize = 56.dp
private val EmojiIconSize = 28.dp
private val EmojiGlyphSize = 28.sp
private const val DISABLED_EMOJI_ALPHA = 0.38f

/**
 * The profile's circular emoji badge beside its name. With no emoji it shows the paused-notifications glyph
 * and opens the emoji picker; with one it offers to change or remove it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileEmojiButton(
    emoji: String?,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val descriptionId =
        if (emoji == null) R.string.notification_profile_emoji_choose else R.string.notification_profile_emoji_change
    val description = stringResource(descriptionId)
    Box {
        Surface(
            onClick = { if (emoji == null) onChoose() else menuOpen = true },
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                Modifier
                    .size(EmojiButtonSize)
                    .alpha(if (enabled) 1f else DISABLED_EMOJI_ALPHA)
                    .testTag("notification-profile.emoji")
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                    },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (emoji == null) {
                    Icon(
                        painterResource(R.drawable.ic_notifications_paused),
                        contentDescription = null,
                        modifier = Modifier.size(EmojiIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(emoji, fontSize = EmojiGlyphSize, maxLines = 1)
                }
            }
        }
        WhiteNoiseDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            items =
                listOf(
                    WhiteNoiseMenuItem(
                        stringResource(R.string.notification_profile_emoji_change),
                        onChoose,
                        R.drawable.ic_mood,
                    ),
                    WhiteNoiseMenuItem(
                        stringResource(R.string.notification_profile_emoji_remove),
                        onRemove,
                        R.drawable.ic_delete,
                        destructive = true,
                    ),
                ),
        )
    }
}
