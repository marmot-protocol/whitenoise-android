package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class GlobalSearchOptionsRequest

internal data class GlobalSearchFilterScope(
    val folders: List<GlobalSearchFolderOption>,
    val chatChoices: List<ChatListItem>,
    val senderChats: List<ChatListItem>,
    val titleCopy: GroupTitleCopy,
    val selfId: String?,
    val selfLabel: String,
    val accountRef: String?,
    val accountScope: String,
)

private data class GlobalSearchOptionsResult(
    val request: GlobalSearchOptionsRequest,
    val options: GlobalSearchFilterOptions,
)

/** Project large local rosters off-main; request identity rejects old account/scope and A–B–A results. */
@Composable
internal fun rememberGlobalSearchFilterOptions(
    appState: WhiteNoiseAppState,
    scope: GlobalSearchFilterScope,
    enabled: Boolean = true,
): GlobalSearchFilterOptions {
    val profileRevision = appState.profileRevisionForCompose
    val request =
        remember(scope, profileRevision, enabled) {
            GlobalSearchOptionsRequest()
        }
    var result by remember { mutableStateOf<GlobalSearchOptionsResult?>(null) }
    LaunchedEffect(request) {
        if (!enabled) return@LaunchedEffect
        val options =
            withContext(Dispatchers.Default) {
                globalSearchFilterOptions(appState, scope)
            }
        result = GlobalSearchOptionsResult(request, options)
    }
    return result?.takeIf { it.request === request }?.options
        ?: GlobalSearchFilterOptions(folders = scope.folders, loading = true)
}
