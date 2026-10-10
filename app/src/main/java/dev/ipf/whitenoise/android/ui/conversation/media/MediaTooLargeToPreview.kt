@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.outlined.OpenInFull
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha

private val TooLargeGlyphSize = 22.dp
private val TooLargeCaptionGap = 6.dp

/**
 * The tile control for a verified image whose plaintext exceeds the presentation budget
 * ([dev.ipf.whitenoise.android.state.ATTACHMENT_PRESENTATION_MAX_BYTES]).
 *
 * It deliberately offers no Retry, because another transfer cannot make the image smaller. The control opens the
 * viewer instead, where Save and Share hand the verified file on without decoding it here. With [showCaption] the
 * explanation is also drawn for sighted readers, for tiles large enough.
 */
@Composable
internal fun MediaTooLargeToPreviewControl(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showCaption: Boolean = false,
) {
    val description = stringResource(R.string.media_too_large_to_preview)
    val openLabel = stringResource(R.string.media_open)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TooLargeCaptionGap),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(TILE_TRANSFER_CONTROL_SIZE)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE))
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Icon(
                Icons.Outlined.OpenInFull,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(TooLargeGlyphSize),
            )
        }
        if (showCaption) TileTransferCaption(description)
    }
}
