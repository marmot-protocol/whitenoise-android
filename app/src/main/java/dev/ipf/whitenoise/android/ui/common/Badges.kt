package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

private val ChatRowBadgeMinimumDiameter = 16.dp
private val ChatRowBadgeLabelInset = 8.dp
private const val FAILED_BADGE_GLYPH_SCALE = 1.2f

/**
 * Material's content badge has a 16 dp minimum and 4 dp horizontal padding; measuring one label digit lets the
 * count, the marked-unread disc and the invitation badge grow together at every font scale.
 */
@Composable
internal fun chatRowBadgeDiameter(): Dp {
    val labelSize = rememberTextMeasurer().measure("0", MaterialTheme.typography.labelSmall).size
    return with(LocalDensity.current) {
        maxOf(ChatRowBadgeMinimumDiameter, labelSize.height.toDp(), labelSize.width.toDp() + ChatRowBadgeLabelInset)
    }
}

/** Unread count disc with the shared chat-row badge diameter. */
@Composable
internal fun UnreadCountBadge(
    unreadCount: ULong,
    modifier: Modifier = Modifier,
    actionColors: AccountActionColors? = null,
) {
    val accessibleCount = unreadCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val description = pluralStringResource(R.plurals.unread_messages_count, accessibleCount, accessibleCount)
    val diameter = chatRowBadgeDiameter()
    // Default Badge is error-red, which reads as an alert not a count.
    Badge(
        modifier =
            modifier
                .sizeIn(minWidth = diameter, minHeight = diameter)
                .semantics { contentDescription = description },
        containerColor = actionColors?.container ?: MaterialTheme.colorScheme.primary,
        contentColor = actionColors?.content ?: MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(if (unreadCount > 99uL) "99+" else unreadCount.toString())
    }
}

/** A manually marked-unread chat carries the same disc as a count, without a number. */
@Suppress("FunctionNaming")
@Composable
internal fun ManualUnreadDot(
    modifier: Modifier = Modifier,
    actionColors: AccountActionColors? = null,
) {
    val description = stringResource(R.string.chat_row_marked_unread)
    val diameter = chatRowBadgeDiameter()
    Badge(
        modifier =
            modifier
                .sizeIn(minWidth = diameter, minHeight = diameter)
                .semantics { contentDescription = description },
        containerColor = actionColors?.container ?: MaterialTheme.colorScheme.primary,
        contentColor = actionColors?.content ?: MaterialTheme.colorScheme.onPrimary,
    )
}

/** A pending invitation shows the plus glyph inside the same disc; its artboard carries the optical padding. */
@Suppress("FunctionNaming")
@Composable
internal fun InvitationBadge(
    modifier: Modifier = Modifier,
    actionColors: AccountActionColors? = null,
) {
    val description = stringResource(R.string.invitation_pending)
    val diameter = chatRowBadgeDiameter()
    val contentColor = actionColors?.content ?: MaterialTheme.colorScheme.onPrimary
    Box(modifier.semantics { contentDescription = description }) {
        Badge(
            modifier = Modifier.sizeIn(minWidth = diameter, minHeight = diameter),
            containerColor = actionColors?.container ?: MaterialTheme.colorScheme.primary,
            contentColor = contentColor,
        )
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            tint = contentColor,
        )
    }
}

/** The last outgoing message failed: the error symbol fills the badge's visible circle, not its artboard. */
@Suppress("FunctionNaming")
@Composable
internal fun FailedDeliveryBadge(modifier: Modifier = Modifier) {
    val diameter = chatRowBadgeDiameter()
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = stringResource(R.string.send_failed),
            modifier =
                Modifier.size(diameter).graphicsLayer {
                    scaleX = FAILED_BADGE_GLYPH_SCALE
                    scaleY = FAILED_BADGE_GLYPH_SCALE
                },
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
