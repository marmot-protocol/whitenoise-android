package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ConversationUnreadBadge
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.reconcile

/** The badge's number for the mounted conversation, and whether it has been reconciled at least once. */
internal data class ConversationUnreadBadgeUiState(
    val count: Int,
    val reconciled: Boolean,
)

/**
 * One owner for the jump-to-newest badge's number (#2726), wired the same way for the screen and
 * its tests: seeded synchronously when the timeline is already [anchored], so a mid-history open
 * carries its number in the first frame; reconciled whenever an input changes, so a history page
 * can never switch the count between the rows and the projection or count the loaded window itself;
 * and exposed only once reconciled, so no consumer ever sees a 0 that merely means "not yet".
 *
 * [identity] is what the number belongs to — controller, chat and entry session — and a new one
 * starts the owner afresh. [onTransition] sees every change of number or source.
 */
@Composable
internal fun rememberConversationUnreadBadgeCount(
    identity: Any,
    anchored: Boolean,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    projectionUnread: Int?,
    windowReachesTail: Boolean,
    onTransition: (before: ConversationUnreadBadge, after: ConversationUnreadBadge) -> Unit = { _, _ -> },
): ConversationUnreadBadgeUiState {
    var badge by
        remember(identity) {
            val seed =
                if (anchored) {
                    ConversationUnreadBadge()
                        .reconcile(timeline, readAnchorMessageId, projectionUnread, windowReachesTail)
                } else {
                    ConversationUnreadBadge()
                }
            mutableStateOf(seed)
        }
    LaunchedEffect(identity, anchored, timeline, readAnchorMessageId, projectionUnread, windowReachesTail) {
        if (!anchored) return@LaunchedEffect
        val next = badge.reconcile(timeline, readAnchorMessageId, projectionUnread, windowReachesTail)
        onTransition(badge, next)
        badge = next
    }
    val reconciled = anchored && badge.source != ConversationUnreadBadge.Source.NONE
    return ConversationUnreadBadgeUiState(count = if (reconciled) badge.count else 0, reconciled = reconciled)
}
