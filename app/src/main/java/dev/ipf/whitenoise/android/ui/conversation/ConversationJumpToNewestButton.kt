package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.unreadBadgeLabel
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlDisc
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag

private const val JUMP_TO_NEWEST_ICON_ROTATION_DEGREES = 180f

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

    FloatingControlDisc(
        icon = painterResource(R.drawable.ic_jump_to_edge),
        contentDescription = jumpToNewestLabel,
        onClick = onClick,
        modifier = modifier.performanceTestTag(PerformanceTestTags.JUMP_TO_NEWEST),
        iconModifier = Modifier.graphicsLayer { rotationZ = JUMP_TO_NEWEST_ICON_ROTATION_DEGREES },
        badge =
            if (unreadIncomingCount > 0) {
                {
                    // The shared unread badge. Its own description would repeat the count the label already
                    // speaks, so only its visible label joins the control, as before. Content-width and
                    // unbounded, so `999+` hangs past the disc at large text instead of clipping.
                    val badgeLabel = unreadBadgeLabel(unreadIncomingCount)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .wrapContentWidth(Alignment.End, unbounded = true)
                            .clearAndSetSemantics { text = AnnotatedString(badgeLabel) },
                    ) {
                        UnreadCountBadge(unreadCount = unreadIncomingCount.toULong())
                    }
                }
            } else {
                null
            },
    )
}
