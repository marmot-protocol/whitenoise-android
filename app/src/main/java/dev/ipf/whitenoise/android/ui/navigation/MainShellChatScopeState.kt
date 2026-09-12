package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.chats.ChatScope

/** Receipt scopes saved UI choice to one native account/runtime; it never stores folder IDs or message content. */
internal data class ChatScopeReceipt(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val scope: ChatScope,
)

private val ChatScopeReceiptSaver =
    listSaver<ChatScopeReceipt, Any>(
        save = { listOf(it.accountRef.orEmpty(), it.runtimeGeneration, it.scope.name) },
        restore = { values ->
            val account = (values.getOrNull(0) as? String)?.takeIf(String::isNotEmpty)
            val runtime = values.getOrNull(1) as? Int ?: 0
            val scope = ChatScope.entries.firstOrNull { it.name == values.getOrNull(2) } ?: ChatScope.Chats
            ChatScopeReceipt(account, runtime, scope)
        },
    )

internal data class MainShellChatScopeState(
    val scope: ChatScope,
    val select: (ChatScope) -> Boolean,
)

/** Conversation navigation and saved-state restoration retain choice only while the account/runtime receipt matches. */
@Composable
internal fun rememberMainShellChatScope(
    accountRef: String?,
    runtimeGeneration: Int,
): MainShellChatScopeState {
    val owner = ChatScopeReceipt(accountRef, runtimeGeneration, ChatScope.Chats)
    val currentOwner by rememberUpdatedState(owner)
    var saved by rememberSaveable(stateSaver = ChatScopeReceiptSaver) { mutableStateOf(owner) }
    val owned = saved.accountRef == accountRef && saved.runtimeGeneration == runtimeGeneration
    val visible = if (owned) saved.scope else ChatScope.Chats
    LaunchedEffect(accountRef, runtimeGeneration) {
        if (saved.accountRef != accountRef || saved.runtimeGeneration != runtimeGeneration) saved = owner
    }
    return MainShellChatScopeState(visible) { next ->
        if (currentOwner == owner && accountRef != null) {
            saved = owner.copy(scope = next)
            true
        } else {
            false
        }
    }
}
