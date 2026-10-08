package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.decodeGlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.encodeGlobalSearchState

private const val SAVED_SEARCH_STATE_INDEX = 6

/**
 * Controller-scoped presentation shared by the shell and its conversation.
 *
 * Details and search controls enter Android saved state for configuration recreation.
 * Selection, navigation jobs and backfill remain transient; none enters protocol storage.
 */
internal class ConversationSurfaceState(
    showDetailsInitially: Boolean = false,
    searchInitially: GlobalSearchState = GlobalSearchState(),
) {
    val showDetails = mutableStateOf(showDetailsInitially)
    val searchOpen = mutableStateOf(searchInitially.isOpen)
    val searchState = mutableStateOf(searchInitially)
    val initialTimelineBackfillNoProgress = mutableStateOf(false)
    val selectedMessages = mutableStateMapOf<String, BatchMessageSelection>()
}

/**
 * Saves the details destination together with its owning identity.
 * A bundle restored into another account, chat, or runtime starts at the conversation instead of leaking details.
 */
internal fun conversationSurfaceStateSaver(
    accountRef: String?,
    chatId: String?,
    runtimeGeneration: Int,
): Saver<ConversationSurfaceState, Any> =
    listSaver(
        save = { state ->
            listOf(
                accountRef != null,
                accountRef.orEmpty(),
                chatId != null,
                chatId.orEmpty(),
                runtimeGeneration,
                state.showDetails.value,
                encodeGlobalSearchState(state.searchState.value.copy(isOpen = state.searchOpen.value)),
            )
        },
        restore = { saved ->
            val savedAccountRef = (saved[1] as String).takeIf { saved[0] as Boolean }
            val savedChatId = (saved[3] as String).takeIf { saved[2] as Boolean }
            val identityMatches =
                savedAccountRef == accountRef &&
                    savedChatId == chatId &&
                    saved[4] == runtimeGeneration
            ConversationSurfaceState(
                showDetailsInitially = identityMatches && saved[5] as Boolean,
                searchInitially =
                    if (identityMatches && saved.size > SAVED_SEARCH_STATE_INDEX) {
                        decodeGlobalSearchState(saved[SAVED_SEARCH_STATE_INDEX] as String)
                    } else {
                        GlobalSearchState()
                    },
            )
        },
    )

/**
 * Owns the saveable conversation presentation for one stable account/chat/runtime tuple.
 * Changing any tuple component prevents a details route from leaking into another conversation.
 */
@Composable
internal fun rememberConversationSurfaceState(
    controllerIdentity: Any?,
    accountRef: String?,
    chatId: String?,
    runtimeGeneration: Int,
): ConversationSurfaceState =
    rememberSaveable(
        controllerIdentity,
        accountRef,
        chatId,
        runtimeGeneration,
        saver = conversationSurfaceStateSaver(accountRef, chatId, runtimeGeneration),
    ) {
        ConversationSurfaceState()
    }
