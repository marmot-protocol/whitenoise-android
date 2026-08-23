package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Publishes notification ownership only for the visible timeline on the stable
 * shell route. Account changes can leave the old selection alive for one
 * composition, and an outgoing screen can remain composed for its animation;
 * matching selected/rendered IDs, timeline visibility, and route stability
 * prevents either stale surface from dismissing another account's cards.
 */
@Composable
@Suppress("FunctionNaming") // Unit-returning Compose effects use component-style names.
internal fun ConversationNotificationOwnershipEffect(
    selectedChatId: String?,
    selectedGroupIdHex: String?,
    renderedChatId: String?,
    renderedAccountRef: String?,
    navigationAccountStable: Boolean,
    timelineVisible: Boolean,
    onOwnershipChanged: (accountRef: String?, groupIdHex: String?) -> Unit,
) {
    val resolvedAccountRef =
        renderedAccountRef.takeIf {
            navigationAccountStable &&
                timelineVisible &&
                selectedChatId != null &&
                selectedChatId == renderedChatId
        }
    val ownership =
        ConversationNotificationOwnership(
            accountRef = resolvedAccountRef,
            groupIdHex = selectedGroupIdHex.takeIf { resolvedAccountRef != null },
        )
    val currentOnOwnershipChanged by rememberUpdatedState(onOwnershipChanged)
    val lastPublishedOwnership = remember { arrayOfNulls<ConversationNotificationOwnership>(1) }
    // Ownership is part of the committed UI state. Publish it synchronously
    // after a successful composition instead of queueing a coroutine that can
    // lag behind an already-visible conversation on a saturated dispatcher.
    SideEffect {
        if (lastPublishedOwnership[0] != ownership) {
            lastPublishedOwnership[0] = ownership
            currentOnOwnershipChanged(ownership.accountRef, ownership.groupIdHex)
        }
    }
}

private data class ConversationNotificationOwnership(
    val accountRef: String?,
    val groupIdHex: String?,
)
