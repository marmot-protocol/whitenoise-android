package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

@Composable
@Suppress("FunctionNaming") // Unit-returning Compose effects use component-style names.
internal fun ConversationNotificationOwnershipEffect(
    selectedChatId: String?,
    selectedGroupIdHex: String?,
    selectedPinnedAccountRef: String?,
    activeAccountRef: String?,
    onOwnershipChanged: suspend (accountRef: String?, groupIdHex: String?) -> Unit,
) {
    val resolvedAccountRef =
        selectedChatId?.let {
            conversationControllerAccountRef(
                selectedPinnedAccountRef = selectedPinnedAccountRef,
                pendingAccountRef = null,
                exitingAccountRef = null,
                activeAccountRef = activeAccountRef,
            )
        }
    val currentOnOwnershipChanged by rememberUpdatedState(onOwnershipChanged)
    LaunchedEffect(selectedChatId, selectedGroupIdHex, resolvedAccountRef) {
        currentOnOwnershipChanged(
            resolvedAccountRef,
            selectedGroupIdHex.takeIf { selectedChatId != null },
        )
    }
}
