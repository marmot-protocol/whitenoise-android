package dev.ipf.whitenoise.android.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.PersonAvatar
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSizes

/**
 * Shows the available profile metadata and opens the profile on tap. The shortened key copies the complete
 * [npub] on its own tap (#3565). [followedAccount] supplies the canonical hex id used by the avatar frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun GlobalPersonRow(
    npub: String,
    displayName: String? = null,
    pictureUrl: String? = null,
    followedAccount: String? = null,
    onOpen: (String) -> Unit,
) {
    ListItem(
        onClick = { onOpen(npub) },
        shapes = WhiteNoiseListItemDefaults.shapes(),
        modifier = Modifier.testTag("global.person." + npub),
        leadingContent = {
            PersonAvatar(
                displayName ?: IdentityFormatter.short(npub),
                npub,
                WhiteNoiseSizes.AvatarCompact,
                pictureUrl,
                followedAccount,
            )
        },
    ) {
        Column {
            EmojiLabel(displayName ?: stringResource(R.string.chat_list_search_open_profile))
            PublicKeyCopyText(
                IdentityFormatter.short(npub, prefix = 12, suffix = 8),
                npub,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
