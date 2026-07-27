package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

@Suppress("FunctionNaming")
@Composable
internal fun ConversationJumpToNewestButton(
    unreadIncomingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val jumpToNewestLabel = stringResource(R.string.jump_to_newest)

    Box(
        modifier =
            modifier
                .size(42.dp)
                .semantics { contentDescription = jumpToNewestLabel }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 2.dp,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (unreadIncomingCount > 0) {
            Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                Text(
                    if (unreadIncomingCount > MAX_BADGE_COUNT) {
                        "$MAX_BADGE_COUNT+"
                    } else {
                        unreadIncomingCount.toString()
                    },
                )
            }
        }
    }
}

private const val MAX_BADGE_COUNT = 99
