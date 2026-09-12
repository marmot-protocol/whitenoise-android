package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatListItemFromProjection

/** Synthetic FFI rows enter the real production projection adapter; no simulated prototype membership is used. */
internal fun leftScopeRow(
    id: String,
    membership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
    archived: Boolean = false,
    leavePending: Boolean = false,
    disbanded: Boolean = false,
): ChatListItem {
    val template = ChatRowPortFixtures.item(membership = membership)
    val projected =
        checkNotNull(template.projection).copy(
            groupIdHex = id,
            title = id,
            groupName = id,
            archived = archived,
            leaveRequestPending = leavePending,
            lifecycleState = if (disbanded) GroupLifecycleStateFfi.DISBANDED else GroupLifecycleStateFfi.STABLE,
        )
    val group = template.group.copy(groupIdHex = id, name = id, archived = archived)
    return chatListItemFromProjection(projected, group = group, activeAccountIdHex = ChatRowPortFixtures.ACCOUNT_HEX)
}

/** Seeds the real controller's completed local snapshot, including its archived source and initial-load state. */
internal fun leftScopeController(
    app: WhiteNoiseAppState,
    rows: List<ChatListItem>,
): ChatsController =
    ChatsController(
        appState = app,
        initialAccountRef = ChatRowPortFixtures.ACCOUNT_REF,
        memberSnapshotLoader = { _, _ -> emptyList() },
        initialLocalSnapshot =
            AccountSwitchLocalSnapshot(
                accountRef = ChatRowPortFixtures.ACCOUNT_REF,
                activeAccountIdHex = ChatRowPortFixtures.ACCOUNT_HEX,
                rows = rows.map { checkNotNull(it.projection) },
                groups = rows.map { it.group },
                memberIds = emptyList(),
                profiles = emptyList(),
            ),
    ).also(app::attachChatsController)
