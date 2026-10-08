package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchAccountScope
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchStateSaver
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTransitions
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchViewport
import dev.ipf.whitenoise.android.ui.chats.decodeGlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.encodeGlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.rememberGlobalSearchViewport

internal data class ConversationSearchReturn(
    val groupId: String,
    val previousSearch: GlobalSearchState,
    val previousFolderId: String?,
)

private val ConversationSearchReturnSaver =
    listSaver<ConversationSearchReturn?, String>(
        save = { origin ->
            origin?.let {
                listOf(
                    it.groupId,
                    encodeGlobalSearchState(it.previousSearch),
                    it.previousFolderId.orEmpty(),
                )
            } ?: emptyList()
        },
        restore = { saved ->
            if (saved.isEmpty()) {
                null
            } else {
                ConversationSearchReturn(
                    groupId = saved[0],
                    previousSearch = decodeGlobalSearchState(saved[1]),
                    previousFolderId = saved[2].takeIf(String::isNotEmpty),
                )
            }
        },
    )

internal data class MainShellGlobalSearchStateHolder(
    val scopedState: GlobalSearchState,
    val update: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
    val viewport: GlobalSearchViewport,
    val conversationReturn: ConversationSearchReturn?,
    val beginConversationSearch: (String, GlobalSearchState, String?) -> Boolean,
    val finishConversationSearch: () -> ConversationSearchReturn?,
)

/**
 * Saveable global chat-list search owned by [MainShell]. Survives conversation
 * navigation and activity recreation; account/runtime scope changes reconcile
 * account-owned chat/sender filters via [GlobalSearchTransitions.reconcileAccountScope].
 */
@Composable
internal fun rememberMainShellGlobalSearchState(
    accountRef: String?,
    runtimeGeneration: Int,
): MainShellGlobalSearchStateHolder {
    var globalSearchState by rememberSaveable(stateSaver = GlobalSearchStateSaver) {
        mutableStateOf(GlobalSearchState())
    }
    val globalSearchAccountScope =
        remember(accountRef, runtimeGeneration) {
            GlobalSearchAccountScope.from(accountRef, runtimeGeneration)
        }
    val scopedGlobalSearchState =
        GlobalSearchTransitions.reconcileAccountScope(globalSearchState, globalSearchAccountScope)
    val viewport = key(globalSearchAccountScope) { rememberGlobalSearchViewport() }
    var conversationReturn by key(globalSearchAccountScope) {
        rememberSaveable(stateSaver = ConversationSearchReturnSaver) { mutableStateOf<ConversationSearchReturn?>(null) }
    }
    // The saved state can outlive an Activity. It must never restore an origin into a new account/runtime.
    val ownedReturn =
        conversationReturn?.takeIf {
            it.previousSearch.accountScopeToken == globalSearchAccountScope.encodeToken()
        }
    LaunchedEffect(globalSearchAccountScope) {
        if (globalSearchState != scopedGlobalSearchState) {
            globalSearchState = scopedGlobalSearchState
        }
    }
    return MainShellGlobalSearchStateHolder(
        scopedState = scopedGlobalSearchState,
        viewport = viewport,
        conversationReturn = ownedReturn,
        beginConversationSearch = { groupId, request, folderId ->
            if (request.accountScopeToken == globalSearchAccountScope.encodeToken()) {
                conversationReturn = ConversationSearchReturn(groupId, scopedGlobalSearchState, folderId)
                globalSearchState = request.copy(isOpen = true, openFilterCategory = null)
                true
            } else {
                false
            }
        },
        finishConversationSearch = {
            conversationReturn = null
            if (ownedReturn != null) globalSearchState = ownedReturn.previousSearch
            ownedReturn
        },
        update = { transform ->
            val currentState =
                GlobalSearchTransitions.reconcileAccountScope(
                    globalSearchState,
                    globalSearchAccountScope,
                )
            globalSearchState = transform(currentState)
        },
    )
}
