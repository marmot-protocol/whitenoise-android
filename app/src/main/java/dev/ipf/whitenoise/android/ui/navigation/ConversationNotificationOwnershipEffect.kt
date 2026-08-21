package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Publishes notification ownership only for the conversation the stable shell
 * route is actually rendering. Account changes can leave the old selection
 * alive for one composition; matching selected/rendered IDs and the stability
 * gate prevents that stale row from dismissing the arriving account's cards.
 */
@Composable
@Suppress("FunctionNaming") // Unit-returning Compose effects use component-style names.
internal fun ConversationNotificationOwnershipEffect(
    selectedChatId: String?,
    selectedGroupIdHex: String?,
    renderedChatId: String?,
    renderedAccountRef: String?,
    navigationAccountStable: Boolean,
    onOwnershipChanged: suspend (accountRef: String?, groupIdHex: String?) -> Unit,
) {
    val resolvedAccountRef =
        renderedAccountRef.takeIf {
            navigationAccountStable &&
                selectedChatId != null &&
                selectedChatId == renderedChatId
        }
    val currentOnOwnershipChanged by rememberUpdatedState(onOwnershipChanged)
    LaunchedEffect(selectedChatId, selectedGroupIdHex, renderedChatId, resolvedAccountRef) {
        currentOnOwnershipChanged(
            resolvedAccountRef,
            selectedGroupIdHex.takeIf { resolvedAccountRef != null },
        )
    }
}
