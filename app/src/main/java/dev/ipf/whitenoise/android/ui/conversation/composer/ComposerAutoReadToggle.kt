package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Shared speaker glyph with a full touch target and checked state exposed to TalkBack. */
@Composable
@Suppress("FunctionNaming")
internal fun ComposerAutoReadToggle(
    checked: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconToggleButton(
        checked = checked,
        enabled = enabled,
        onCheckedChange = { onClick() },
        modifier = Modifier.size(48.dp),
        colors =
            IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = Color.Transparent,
                checkedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Icon(
            painter = painterResource(if (checked) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
            contentDescription = stringResource(R.string.tts_auto_read_title),
            modifier = Modifier.size(20.dp),
        )
    }
}
