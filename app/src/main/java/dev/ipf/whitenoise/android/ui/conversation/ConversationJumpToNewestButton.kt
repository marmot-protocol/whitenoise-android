package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.unreadBadgeLabel
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag

/**
 * The floating arrow that returns the reader to the newest message, badged with the unread count.
 *
 * Its accessibility text announces the jump action followed by the actual unread count, so a screen reader
 * hears "1030 unread messages" even while the badge shows the capped `999+`.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationJumpToNewestButton(
    unreadIncomingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val jumpToNewestAction = stringResource(R.string.jump_to_newest)
    val unreadCountText =
        if (unreadIncomingCount > 0) {
            pluralStringResource(R.plurals.unread_messages_count, unreadIncomingCount, unreadIncomingCount)
        } else {
            null
        }
    val jumpToNewestLabel = unreadCountText?.let { "$jumpToNewestAction, $it" } ?: jumpToNewestAction

    Box(
        modifier =
            modifier
                .size(42.dp)
                .semantics { contentDescription = jumpToNewestLabel }
                .performanceTestTag(PerformanceTestTags.JUMP_TO_NEWEST)
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
                    painterResource(R.drawable.ic_jump_to_edge),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f },
                )
            }
        }
        if (unreadIncomingCount > 0) {
            // Content-width badge: Material grows it past its two-digit circle for `999` and `999+`, and
            // measuring it unbounded lets it hang past the 42 dp tap target at large text instead of clipping.
            Badge(modifier = Modifier.align(Alignment.TopEnd).wrapContentWidth(Alignment.End, unbounded = true)) {
                Text(unreadBadgeLabel(unreadIncomingCount))
            }
        }
    }
}
