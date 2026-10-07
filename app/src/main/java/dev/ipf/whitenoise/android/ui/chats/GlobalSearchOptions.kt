package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ProfilePresentationRevision
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class GlobalSearchOptionsRequest

private class GlobalSearchOptionsBoundaryOwner

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
    val boundary: GlobalSearchOptionsBoundaryOwner,
    val options: GlobalSearchFilterOptions,
)

private data class GlobalSearchOptionsBoundary(
    val accountScope: String,
    val accountRef: String?,
    val selfId: String?,
    val enabled: Boolean,
    val chatIds: Set<String>,
    val senderChatIds: Set<String>,
)

/** Excludes message previews, unread counters and ordering from picker projection ownership. */
private data class GlobalSearchChatChoiceKey(
    val group: AppGroupRecordFfi,
    val presentation: ConversationPresentationFfi?,
    val projectedTitle: String?,
    val kind: ChatConversationKindFfi?,
    val peer: String?,
    val memberCount: Int,
    val soleSelfMember: Boolean,
)

private fun ChatListItem.choiceKey(): GlobalSearchChatChoiceKey =
    GlobalSearchChatChoiceKey(
        group,
        selectedPresentation,
        projectedTitle,
        projection?.conversationKind,
        presentationOtherMemberAccount,
        presentationMemberCount,
        presentationActiveAccountIsSoleMember,
    )

/** Project large local rosters off-main; request identity rejects old account/scope and A–B–A results. */
@Composable
internal fun rememberGlobalSearchFilterOptions(
    appState: WhiteNoiseAppState,
    scope: GlobalSearchFilterScope,
    enabled: Boolean = true,
): GlobalSearchFilterOptions =
    rememberProjectedGlobalSearchFilterOptions(scope, appState.profileRevisionForCompose, enabled) {
        globalSearchFilterOptions(appState, it)
    }

/** Keeps current-scope UI choices during refresh, but never across account, scope or close boundaries. */
@Composable
internal fun rememberProjectedGlobalSearchFilterOptions(
    scope: GlobalSearchFilterScope,
    profileRevision: ProfilePresentationRevision,
    enabled: Boolean = true,
    project: suspend (GlobalSearchFilterScope) -> GlobalSearchFilterOptions,
): GlobalSearchFilterOptions {
    val boundary =
        GlobalSearchOptionsBoundary(
            scope.accountScope,
            scope.accountRef,
            scope.selfId,
            enabled,
            scope.chatChoices.mapTo(mutableSetOf()) { canonicalChatListGroupId(it.id) },
            scope.senderChats.mapTo(mutableSetOf()) { canonicalChatListGroupId(it.id) },
        )
    val choices = scope.chatChoices.map { it.choiceKey() }.toSet()
    val rosters = scope.senderChats.associate { canonicalChatListGroupId(it.id) to it.memberSnapshot }
    val boundaryOwner = remember(boundary) { GlobalSearchOptionsBoundaryOwner() }
    val request =
        remember(boundaryOwner, choices, rosters, scope.folders, scope.titleCopy, scope.selfLabel, profileRevision) {
            GlobalSearchOptionsRequest()
        }
    var result by remember { mutableStateOf<GlobalSearchOptionsResult?>(null) }
    LaunchedEffect(request) {
        if (!enabled) return@LaunchedEffect
        val options =
            withContext(Dispatchers.Default) {
                project(scope)
            }
        result = GlobalSearchOptionsResult(request, boundaryOwner, options)
    }
    return result?.takeIf { it.boundary === boundaryOwner && enabled }?.let {
        it.options.copy(folders = scope.folders, loading = it.request !== request)
    }
        ?: GlobalSearchFilterOptions(folders = scope.folders, loading = true)
}
