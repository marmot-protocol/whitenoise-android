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
