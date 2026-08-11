package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchAccountScope
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchStateSaver
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTransitions

internal data class MainShellGlobalSearchStateHolder(
    val scopedState: GlobalSearchState,
    val update: ((GlobalSearchState) -> GlobalSearchState) -> Unit,
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
    LaunchedEffect(globalSearchAccountScope) {
        if (globalSearchState != scopedGlobalSearchState) {
            globalSearchState = scopedGlobalSearchState
        }
    }
    return MainShellGlobalSearchStateHolder(
        scopedState = scopedGlobalSearchState,
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
