package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Decoration follows the engine's typed attachment kind; text remains the production projected preview. */
@Suppress("FunctionNaming")
@Composable
internal fun PinnedBadge(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_push_pin),
        contentDescription = stringResource(R.string.chat_pinned_badge),
        modifier = modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
