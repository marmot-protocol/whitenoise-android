package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    onOwnershipChanged: suspend (accountRef: String?, groupIdHex: String?) -> Unit,
) {
    val resolvedAccountRef =
        renderedAccountRef.takeIf {
            navigationAccountStable &&
                timelineVisible &&
                selectedChatId != null &&
                selectedChatId == renderedChatId
        }
    val currentOnOwnershipChanged by rememberUpdatedState(onOwnershipChanged)
    LaunchedEffect(selectedChatId, selectedGroupIdHex, renderedChatId, resolvedAccountRef, timelineVisible) {
        currentOnOwnershipChanged(
            resolvedAccountRef,
            selectedGroupIdHex.takeIf { resolvedAccountRef != null },
        )
    }
}
