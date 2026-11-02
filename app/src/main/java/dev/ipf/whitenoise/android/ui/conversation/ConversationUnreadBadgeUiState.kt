package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val badge =
        rememberConversationUnreadBadge(identity, anchored) { unseeded ->
            unseeded.reconcile(timeline, readAnchorMessageId, projectionUnread, windowReachesTail)
        }
    ConversationUnreadBadgeReconcileEffect(
        badge = badge,
        anchored = anchored,
        timeline = timeline,
        readAnchorMessageId = readAnchorMessageId,
        projectionUnread = projectionUnread,
        windowReachesTail = windowReachesTail,
        onTransition = onTransition,
    )
    return badge.value.uiState(anchored)
}

/**
 * The badge owner's state for [identity]. It starts afresh for a new identity and whenever [anchored] changes, and
 * a chat that is already anchored is seeded synchronously through [seed], so its first anchored frame carries the
 * number instead of a 0 that merely means "not yet". [seed] runs only when the owner starts, so a caller can hold
 * the state without reading the seed's inputs on every composition.
 */
@Composable
internal fun rememberConversationUnreadBadge(
    identity: Any,
    anchored: Boolean,
    seed: (ConversationUnreadBadge) -> ConversationUnreadBadge,
): MutableState<ConversationUnreadBadge> =
    remember(identity, anchored) {
        mutableStateOf(if (anchored) seed(ConversationUnreadBadge()) else ConversationUnreadBadge())
    }

/**
 * Reconciles [badge] whenever an input changes, so a history page can never switch the count between the rows and
 * the projection or count the loaded window itself. [onTransition] sees every change of number or source.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // One effect over every input the count depends on.
internal fun ConversationUnreadBadgeReconcileEffect(
    badge: MutableState<ConversationUnreadBadge>,
    anchored: Boolean,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    projectionUnread: Int?,
    windowReachesTail: Boolean,
    onTransition: (before: ConversationUnreadBadge, after: ConversationUnreadBadge) -> Unit,
) {
    LaunchedEffect(badge, anchored, timeline, readAnchorMessageId, projectionUnread, windowReachesTail) {
        if (!anchored) return@LaunchedEffect
        val before = badge.value
        val next = before.reconcile(timeline, readAnchorMessageId, projectionUnread, windowReachesTail)
        onTransition(before, next)
        badge.value = next
    }
}

/** What the badge shows: its count once reconciled for an [anchored] timeline, and 0 before. */
internal fun ConversationUnreadBadge.uiState(anchored: Boolean): ConversationUnreadBadgeUiState {
    val reconciled = anchored && source != ConversationUnreadBadge.Source.NONE
    return ConversationUnreadBadgeUiState(count = if (reconciled) count else 0, reconciled = reconciled)
}
